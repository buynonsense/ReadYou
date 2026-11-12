package me.ash.reader.infrastructure.net

import android.app.Application
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException
import me.ash.reader.infrastructure.di.cachingHttpClient
import me.ash.reader.infrastructure.di.withServerTrust
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class ServerSecurityTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() { context.getSharedPreferences("server_security", Application.MODE_PRIVATE).edit().clear().commit() }

    @Test fun plainHttpRequiresApprovalForTheExactOrigin() {
        MockWebServer().use { first ->
            MockWebServer().use { second ->
                first.start(); second.start()
                val client = cachingHttpClient(context)
                val url = first.url("/").toString()
                assertTrue(runCatching { client.newCall(Request.Builder().url(url).build()).execute().close() }.isFailure)
                ServerSecurityStore(context).setAllowsHttp(url, true)
                first.enqueue(MockResponse().setBody("ok"))
                client.newCall(Request.Builder().url(url).build()).execute().use { assertTrue(it.isSuccessful) }
                assertTrue(runCatching { client.newCall(Request.Builder().url(second.url("/")).build()).execute().close() }.isFailure)
            }
        }
    }

    @Test fun privateCertificateRequiresExplicitServerTrust() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("localhost").addSubjectAlternativeName("127.0.0.1").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.start()
            val url = server.url("/").toString()
            val client = cachingHttpClient(context).newBuilder().dns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }.build()
            val failure = runCatching { client.newCall(Request.Builder().url(url).build()).execute().close() }.exceptionOrNull()
            assertTrue(failure.toString(), failure is SSLHandshakeException)
            ServerSecurityStore(context).setCertificate(url, certificate.certificatePem())
            server.enqueue(MockResponse().setBody("ok"))
            client.withServerTrust(context, url).newCall(Request.Builder().url(url).build()).execute().use { assertTrue(it.isSuccessful) }
        }
    }

    @Test fun trustingACertificateDoesNotDisableHostnameValidation() {
        val certificate = HeldCertificate.Builder().addSubjectAlternativeName("another-host.example").build()
        val tls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        MockWebServer().use { server ->
            server.useHttps(tls.sslSocketFactory(), false)
            server.start()
            val url = server.url("/").toString()
            ServerSecurityStore(context).setCertificate(url, certificate.certificatePem())
            val failure = runCatching {
                cachingHttpClient(context).newBuilder().dns { listOf(java.net.InetAddress.getByName("127.0.0.1")) }.build()
                    .withServerTrust(context, url).newCall(Request.Builder().url(url).build()).execute().close()
            }.exceptionOrNull()
            assertTrue(failure.toString(), failure is SSLPeerUnverifiedException)
        }
    }
}
