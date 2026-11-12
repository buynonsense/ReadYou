package me.ash.reader.infrastructure.net

import android.content.Context
import java.io.IOException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Response

/** Explicit exceptions are scoped to the full server origin, including port. */
class ServerSecurityStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences("server_security", Context.MODE_PRIVATE)

    fun origin(url: String): String? = url.toHttpUrlOrNull()?.let(::origin)
    private fun origin(url: HttpUrl) = "${url.scheme}://${url.host}:${url.port}"

    fun allowsHttp(url: HttpUrl): Boolean = preferences.getBoolean("http.${origin(url)}", false)
    fun allowsHttp(url: String): Boolean = url.toHttpUrlOrNull()?.let(::allowsHttp) ?: false

    fun setAllowsHttp(url: String, allowed: Boolean) {
        val server = requireNotNull(url.toHttpUrlOrNull()) { "Invalid server URL" }
        require(server.scheme == "http")
        preferences.edit().putBoolean("http.${origin(server)}", allowed).apply()
    }

    fun certificatePem(url: String): String? = origin(url)?.let { preferences.getString("certificate.$it", null) }

    fun setCertificate(url: String, pem: String) {
        val server = requireNotNull(url.toHttpUrlOrNull()) { "Invalid server URL" }
        require(server.isHttps) { "A server certificate requires HTTPS" }
        if (pem.isNotBlank()) parseCertificate(pem).checkValidity()
        preferences.edit().apply {
            if (pem.isBlank()) remove("certificate.${origin(server)}")
            else putString("certificate.${origin(server)}", pem.trim())
        }.apply()
    }

    companion object {
        fun parseCertificate(pem: String): X509Certificate {
            require(pem.length <= 65_536) { "Certificate is too large" }
            return pem.byteInputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
        }
    }
}

class CleartextServerInterceptor(private val store: ServerSecurityStore) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (!request.url.isHttps && !store.allowsHttp(request.url)) {
            throw IOException("HTTP is disabled for ${request.url.host}:${request.url.port}. Enable HTTP explicitly in this server's security settings.")
        }
        return chain.proceed(request)
    }
}
