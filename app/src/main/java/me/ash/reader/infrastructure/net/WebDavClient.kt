package me.ash.reader.infrastructure.net

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import me.ash.reader.infrastructure.di.withServerTrust
import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody

/**
 * Minimal WebDAV client built on OkHttp, providing the subset of methods
 * needed by [me.ash.reader.domain.service.WebDavSyncService]:
 * PROPFIND (access check), MKCOL (create collections) and GET/PUT.
 */
class WebDavClient
@Inject
constructor(
    private val okHttpClient: OkHttpClient,
    @ApplicationContext private val context: Context,
) {

    /**
     * Checks that [url] is reachable with the given credentials.
     * Returns normally on any HTTP answer (2xx/207 ok, 404 absent),
     * throws on network errors.
     */
    fun propfind(url: String, username: String?, password: String?): Response =
        execute(
            request(url, username, password) {
                method("PROPFIND", null)
                header("Depth", "0")
            }
        )

    fun get(url: String, username: String?, password: String?): Response =
        execute(request(url, username, password) {})

    /**
     * Puts [body] at [url], creating missing parent collections first.
     * Returns the final response; callers should check [Response.isSuccessful].
     */
    fun put(url: String, username: String?, password: String?, body: ByteArray): Response {
        val requestBody = body.toRequestBody(JSON_MEDIA_TYPE)
        return putBody(url, username, password, requestBody)
    }

    fun putFile(url: String, username: String?, password: String?, file: File, ifMatch: String? = null, ifAbsent: Boolean = false): Response =
        putBody(url, username, password, file.asRequestBody("application/octet-stream".toMediaType()), ifMatch, ifAbsent)

    fun putConditional(url: String, username: String?, password: String?, body: ByteArray, ifMatch: String? = null, ifAbsent: Boolean = false): Response =
        putBody(url, username, password, body.toRequestBody(JSON_MEDIA_TYPE), ifMatch, ifAbsent)

    private fun putBody(url: String, username: String?, password: String?, requestBody: RequestBody, ifMatch: String? = null, ifAbsent: Boolean = false): Response {
        val build: Request.Builder.() -> Unit = {
            put(requestBody)
            ifMatch?.let { header("If-Match", it) }
            if (ifAbsent) header("If-None-Match", "*")
        }
        var response = execute(request(url, username, password, build))
        // Some servers answer 409 when intermediate collections do not exist.
        if (response.code == 409) {
            response.close()
            createParentCollections(url, username, password)
            response = execute(request(url, username, password, build))
        }
        return response
    }

    fun mkcol(url: String, username: String?, password: String?): Response =
        execute(request(url, username, password) { method("MKCOL", null) })

    /** Creates every missing collection between the server root and [url]'s parent. */
    private fun createParentCollections(url: String, username: String?, password: String?) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val segments = httpUrl.encodedPath.trim('/').split('/').filter { it.isNotBlank() }.dropLast(1)
        var current = httpUrl.newBuilder().encodedPath("/").build().toString()
        for (segment in segments) {
            current = if (current.endsWith("/")) current + segment else "$current/$segment"
            // 405 means the collection already exists, which is fine.
            mkcol(current, username, password).use {
                check(it.isSuccessful || it.code == 405) { "Could not create WebDAV collection: HTTP ${it.code}" }
            }
        }
    }

    private fun request(
        url: String,
        username: String?,
        password: String?,
        block: Request.Builder.() -> Unit,
    ): Request =
        Request.Builder()
            .url(url)
            .apply {
                if (!username.isNullOrEmpty() && !password.isNullOrEmpty()) {
                    header("Authorization", Credentials.basic(username, password))
                }
            }
            .apply(block)
            .build()

    private fun execute(request: Request): Response = client.withServerTrust(context, request.url.toString()).newCall(request).execute()

    // WebDAV servers can be slow or return big payloads; reuse the app client
    // but make the timeouts explicit.
    private val client: OkHttpClient by lazy {
        okHttpClient
            .newBuilder()
            .connectTimeout(30L, TimeUnit.SECONDS)
            .readTimeout(120L, TimeUnit.SECONDS)
            .writeTimeout(120L, TimeUnit.SECONDS)
            .build()
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
