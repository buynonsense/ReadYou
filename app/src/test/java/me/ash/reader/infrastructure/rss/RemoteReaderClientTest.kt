package me.ash.reader.infrastructure.rss

import android.app.Application
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.account.security.RemoteReaderSecurityKey
import me.ash.reader.infrastructure.rss.provider.remote.RemoteArticle
import me.ash.reader.infrastructure.rss.provider.remote.RemoteReaderClient
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class RemoteReaderClientTest {
    private val server = MockWebServer()
    @After fun close() { server.shutdown() }
    private fun reply(json: String, code: Int = 200) { server.enqueue(MockResponse().setResponseCode(code).setBody(json)) }
    private fun client(type: AccountType) = RemoteReaderClient(type,
        RemoteReaderSecurityKey(server.url("/").toString(), "reader", "password", "access-token", "application", "key"),
        OkHttpClient(), RuntimeEnvironment.getApplication())

    @Test fun feedlyConsumesContinuationsAndWritesMarkersWithBearerToken() = runBlocking {
        val api = client(AccountType.Feedly)
        reply("""{"id":"user-id"}""")
        assertTrue(api.validate())
        reply("""{"continuation":"next page","items":[{"id":"one","origin":{"streamId":"feed/https://example.org/rss"},"canonical":[{"href":"https://example.org/1"}],"published":1000,"unread":true,"summary":{"content":"body"}}]}""")
        reply("""{"items":[]}""")
        reply("""{"items":[]}""")
        val articles = mutableListOf<RemoteArticle>()
        api.articles { articles += it }
        assertEquals(listOf("one"), articles.map { it.id })
        assertEquals("body", articles.single().body)
        reply("{}")
        api.setState(listOf("one"), unread = false)
        assertEquals("Bearer access-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("/v3/streams/contents", server.takeRequest().requestUrl!!.encodedPath)
        assertEquals("next page", server.takeRequest().requestUrl!!.queryParameter("continuation"))
        server.takeRequest()
        val marker = JsonParser.parseString(server.takeRequest().body.readUtf8()).asJsonObject
        assertEquals("markAsRead", marker["action"].asString)
    }

    @Test fun inoreaderUsesRepeatedItemFieldsAndApplicationHeaders() {
        val api = client(AccountType.Inoreader)
        reply("OK")
        api.setState(listOf("123", "456"), starred = true)
        val request = server.takeRequest()
        assertEquals("application", request.getHeader("AppId"))
        assertEquals("key", request.getHeader("AppKey"))
        assertEquals("Bearer access-token", request.getHeader("Authorization"))
        assertEquals("a=user%2F-%2Fstate%2Fcom.google%2Fstarred&i=123&i=456", request.body.readUtf8())
    }

    @Test fun minifluxUsesTokenAndAssignsExplicitStarsOnSupportedServers() = runBlocking {
        val api = client(AccountType.Miniflux)
        reply("""{"total":1,"entries":[{"id":7,"feed_id":3,"title":"title","content":"正文","url":"https://example.org/a","published_at":"2026-11-12T00:00:00Z","status":"unread","starred":false}]}""")
        val entries = mutableListOf<RemoteArticle>()
        api.articles { entries += it }
        assertTrue(entries.single().unread)
        assertEquals(1794441600000L, entries.single().date)
        reply("2.2.11")
        reply("", 204)
        api.setState(listOf("7"), starred = true)
        assertEquals("access-token", server.takeRequest().getHeader("X-Auth-Token"))
        assertEquals("/version", server.takeRequest().path)
        val write = server.takeRequest()
        assertEquals("PUT", write.method)
        val body = JsonParser.parseString(write.body.readUtf8()).asJsonObject
        assertTrue(body["starred"].asBoolean)
        assertFalse(body.has("status"))
    }

    @Test fun legacyMinifluxRetryDoesNotToggleAnAlreadyCorrectBookmark() {
        val api = client(AccountType.Miniflux)
        reply("2.2.0")
        reply("""{"starred":true}""")
        api.setState(listOf("7"), starred = true)
        assertEquals(2, server.requestCount)
    }

    @Test fun tinyTinyRssRenewsExpiredSessionAndUsesExplicitStateAssignment() {
        val api = client(AccountType.TinyTinyRss)
        reply("""{"status":0,"content":{"session_id":"first"}}""")
        reply("""{"status":1,"content":{"error":"NOT_LOGGED_IN"}}""")
        reply("""{"status":0,"content":{"session_id":"second"}}""")
        reply("""{"status":0,"content":[]}""")
        assertTrue(api.groups().isEmpty())
        reply("""{"status":0,"content":{"status":"OK","updated":2}}""")
        api.setState(listOf("1", "2"), unread = false)
        repeat(4) { server.takeRequest() }
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("/api/", request.path)
        val json = JsonParser.parseString(request.body.readUtf8()).asJsonObject
        assertEquals("second", json["sid"].asString)
        assertEquals(2, json["field"].asInt)
        assertEquals(0, json["mode"].asInt)
    }

    @Test fun deniedApiRequestsFailInsteadOfBeingAcknowledged() {
        val api = client(AccountType.Feedly)
        reply("denied", 401)
        assertThrows(java.io.IOException::class.java) { api.setState(listOf("1"), unread = false) }
    }
}
