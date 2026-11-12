package me.ash.reader.infrastructure.net

import android.app.Application
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WebDavClientTest {
    private val server = MockWebServer()
    private lateinit var client: WebDavClient

    @Before
    fun setUp() {
        client = WebDavClient(OkHttpClient(), RuntimeEnvironment.getApplication())
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun putDoesNotTryToCreateExistingServerCollections() {
        val putAttempts = AtomicInteger()
        val createdCollections = CopyOnWriteArrayList<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl!!.encodedPath
                return when {
                    request.method == "PUT" && path.endsWith("/chunks/chunk.json.gz") ->
                        if (putAttempts.getAndIncrement() == 0) MockResponse().setResponseCode(409)
                        else MockResponse().setResponseCode(201)
                    request.method == "PROPFIND" && path == "/dav" ->
                        MockResponse().setResponseCode(403)
                    request.method == "PROPFIND" && path == "/dav/ReadYou" ->
                        MockResponse().setResponseCode(207)
                    request.method == "PROPFIND" -> MockResponse().setResponseCode(404)
                    request.method == "MKCOL" && path == "/dav" -> {
                        createdCollections += path
                        MockResponse().setResponseCode(403)
                    }
                    request.method == "MKCOL" -> {
                        createdCollections += path
                        MockResponse().setResponseCode(201)
                    }
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

        val response = client.putConditional(
            server.url("/dav/ReadYou/accounts/pair/chunks/chunk.json.gz").toString(),
            username = "user",
            password = "password",
            body = byteArrayOf(1, 2, 3),
            ifAbsent = true,
        )

        response.use { assertTrue(it.isSuccessful) }
        assertTrue(putAttempts.get() >= 2)
        assertTrue("MKCOL was sent for an existing server-owned collection", "/dav" !in createdCollections)
        assertTrue("Only missing remote collections should be created: $createdCollections",
            createdCollections == listOf(
                "/dav/ReadYou/accounts",
                "/dav/ReadYou/accounts/pair",
                "/dav/ReadYou/accounts/pair/chunks",
            ))
    }
}
