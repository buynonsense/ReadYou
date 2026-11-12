package me.ash.reader.infrastructure.net

import java.util.concurrent.TimeUnit
import javax.inject.Inject
import android.content.Context
import android.util.Xml
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
import org.xmlpull.v1.XmlPullParser

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

    /** Lists immediate child collections, used to discover backups made before directory pairing. */
    fun collectionNames(url: String, username: String?, password: String?): List<String> {
        val base = requireNotNull(url.toHttpUrlOrNull())
        val parent = base.newBuilder().encodedPath(base.encodedPath.trimEnd('/') + "/").build()
        return execute(request(parent.toString(), username, password) {
            method("PROPFIND", """<d:propfind xmlns:d="DAV:"><d:prop><d:resourcetype/></d:prop></d:propfind>"""
                .toRequestBody("application/xml; charset=utf-8".toMediaType()))
            header("Depth", "1")
        }).use { response ->
            if (response.code == 404) return@use emptyList()
            check(response.isSuccessful) { "Could not discover WebDAV data: HTTP ${response.code}" }
            val source = requireNotNull(response.body).source()
            source.request(1_048_577)
            check(source.buffer.size <= 1_048_576) { "WebDAV directory listing is too large" }
            val parser = Xml.newPullParser().apply {
                setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
                setInput(source.inputStream(), null)
            }
            val names = linkedSetOf<String>()
            var href: String? = null
            var collection = false
            var inPropstat = false
            var propstatCollection = false
            var propstatSuccessful = false
            var multistatus = false
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.END_DOCUMENT -> break
                    XmlPullParser.DOCDECL -> error("Invalid WebDAV directory listing")
                    XmlPullParser.START_TAG -> if (parser.namespace == "DAV:") when (parser.name) {
                        "multistatus" -> multistatus = true
                        "response" -> { href = null; collection = false }
                        "propstat" -> { inPropstat = true; propstatCollection = false; propstatSuccessful = false }
                        "href" -> href = parser.nextText().trim()
                        "collection" -> if (inPropstat) propstatCollection = true
                        "status" -> if (inPropstat) propstatSuccessful = parser.nextText().trim().matches(Regex("HTTP/\\S+ 2[0-9]{2}(?: .*)?"))
                    }
                    XmlPullParser.END_TAG -> if (parser.namespace == "DAV:") when (parser.name) {
                        "propstat" -> {
                            if (propstatSuccessful && propstatCollection) collection = true
                            inPropstat = false
                        }
                        "response" -> if (collection) {
                            val child = href?.let(parent::resolve) ?: continue
                            if (child.scheme == parent.scheme && child.host == parent.host && child.port == parent.port &&
                                child.query == null && child.fragment == null &&
                                child.encodedPath.trimEnd('/').substringBeforeLast('/') == parent.encodedPath.trimEnd('/')) {
                                names += child.pathSegments.last { it.isNotEmpty() }
                            }
                        }
                    }
                }
            }
            check(multistatus) { "Invalid WebDAV directory listing" }
            names.toList()
        }
    }

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

    /** Creates only missing collections between the server root and [url]'s parent. */
    private fun createParentCollections(url: String, username: String?, password: String?) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        val segments = httpUrl.encodedPath.trim('/').split('/').filter { it.isNotBlank() }.dropLast(1)
        var current = httpUrl.newBuilder().encodedPath("/").build().toString()
        for (segment in segments) {
            current = if (current.endsWith("/")) current + segment else "$current/$segment"
            // Existing server-owned collections (for example Nutstore's /dav)
            // may reject MKCOL with 403 even though they are usable.
            val existsOrInaccessible = propfind(current, username, password).use { response ->
                response.isSuccessful || response.code == 207 || response.code == 403
            }
            if (existsOrInaccessible) continue

            mkcol(current, username, password).use {
                check(it.isSuccessful || it.code == 405 || it.code == 409) {
                    "Could not create WebDAV collection: HTTP ${it.code}"
                }
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
