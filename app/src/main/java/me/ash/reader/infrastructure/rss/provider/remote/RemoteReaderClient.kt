package me.ash.reader.infrastructure.rss.provider.remote

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.IOException
import java.time.Instant
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.account.security.RemoteReaderSecurityKey
import me.ash.reader.infrastructure.di.withServerTrust
import okhttp3.Credentials
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

internal fun JsonObject.text(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
internal fun JsonObject.number(name: String): Long = text(name)?.toLongOrNull() ?: 0L
internal fun JsonObject.flag(name: String): Boolean = text(name)?.let { it == "true" || it == "1" } ?: false
internal fun JsonObject.array(name: String): JsonArray = getAsJsonArray(name) ?: JsonArray()
internal fun JsonObject.obj(name: String): JsonObject = getAsJsonObject(name) ?: JsonObject()

data class RemoteGroup(val id: String, val name: String)
data class RemoteFeed(val id: String, val groupId: String?, val title: String, val url: String, val icon: String? = null)
data class RemoteArticle(
    val id: String, val feedId: String, val title: String, val body: String, val link: String,
    val date: Long, val author: String?, val unread: Boolean, val starred: Boolean, val image: String? = null,
)

/** Provider-specific wire formats stay here; account and database policy stay in the repository. */
class RemoteReaderClient(
    private val type: AccountType,
    private val credentials: RemoteReaderSecurityKey,
    httpClient: OkHttpClient,
    context: Context,
) {
    private val gson = Gson()
    private val base = credentials.serverUrl.orEmpty().trim().trimEnd('/').ifEmpty {
        when (type) {
            AccountType.Feedly -> "https://cloud.feedly.com"
            AccountType.Inoreader -> "https://www.inoreader.com"
            else -> throw IllegalArgumentException("Server URL is required")
        }
    }
    private val client = httpClient.withServerTrust(context, base, credentials.clientCertificateAlias)
    private var userId: String? = null
    private var session: String? = null
    private var explicitMinifluxStars: Boolean? = null

    private fun request(path: String, query: Map<String, String> = emptyMap(), method: String = "GET", body: RequestBody? = null): String {
        val url = "$base/${path.trimStart('/')}".toHttpUrl().newBuilder().apply {
            query.forEach { (key, value) -> addQueryParameter(key, value) }
        }.build()
        val request = Request.Builder().url(url).apply {
            when (type) {
                AccountType.Feedly, AccountType.Inoreader -> {
                    require(!credentials.accessToken.isNullOrBlank()) { "An API access token is required" }
                    header("Authorization", "Bearer ${credentials.accessToken}")
                    if (type == AccountType.Inoreader) {
                        credentials.clientId?.takeIf { it.isNotBlank() }?.let { header("AppId", it) }
                        credentials.clientSecret?.takeIf { it.isNotBlank() }?.let { header("AppKey", it) }
                    }
                }
                AccountType.Miniflux -> if (!credentials.accessToken.isNullOrBlank()) {
                    header("X-Auth-Token", credentials.accessToken!!)
                } else header("Authorization", Credentials.basic(credentials.username.orEmpty(), credentials.password.orEmpty()))
            }
            method(method, body)
        }.build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("${type.id}: HTTP ${response.code} (${url.host})")
            response.body?.string().orEmpty()
        }
    }

    private fun json(path: String, query: Map<String, String> = emptyMap()): JsonElement = JsonParser.parseString(request(path, query))
    private fun post(path: String, data: Any, method: String = "POST") =
        request(path, method = method, body = gson.toJson(data).toRequestBody("application/json; charset=utf-8".toMediaType()))

    private fun tt(operation: String, arguments: Map<String, Any> = emptyMap(), retryLogin: Boolean = true): JsonElement {
        if (operation != "login" && session == null) login()
        val payload = arguments.toMutableMap().apply {
            put("op", operation)
            session?.let { put("sid", it) }
        }
        val apiPath = if (base.endsWith("/api")) "" else "api/"
        val response = JsonParser.parseString(post(apiPath, payload)).asJsonObject
        val content = response["content"] ?: throw IOException("Missing Tiny Tiny RSS response")
        if (response.number("status") != 0L) {
            val error = if (content.isJsonObject) content.asJsonObject.text("error") else null
            if (error == "NOT_LOGGED_IN" && retryLogin && operation != "login") {
                session = null
                login()
                return tt(operation, arguments, retryLogin = false)
            }
            throw IOException("Tiny Tiny RSS: ${error ?: "API error"}")
        }
        return content
    }
    private fun login() {
        session = tt("login", mapOf("user" to credentials.username.orEmpty(), "password" to credentials.password.orEmpty()), retryLogin = false)
            .asJsonObject.text("session_id") ?: throw IOException("Tiny Tiny RSS login failed")
    }

    fun validate(): Boolean {
        when (type) {
            AccountType.Feedly -> userId = json("v3/profile").asJsonObject.text("id") ?: throw IOException("Missing Feedly profile")
            AccountType.Inoreader -> json("reader/api/0/user-info", mapOf("output" to "json")).asJsonObject
            AccountType.Miniflux -> json("v1/me").asJsonObject
            AccountType.TinyTinyRss -> login()
            else -> throw IllegalArgumentException("Unsupported reader service")
        }
        return true
    }

    fun groups(): List<RemoteGroup> = when (type) {
        AccountType.Feedly -> json("v3/collections").asJsonArray.map { it.asJsonObject.let { row -> RemoteGroup(row.text("id")!!, row.text("label").orEmpty()) } }
        AccountType.Inoreader -> json("reader/api/0/tag/list", mapOf("output" to "json")).asJsonObject.array("tags")
            .mapNotNull { it.asJsonObject.text("id") }.filter { it.contains("/label/") }
            .map { RemoteGroup(it, it.substringAfter("/label/")) }
        AccountType.Miniflux -> json("v1/categories").asJsonArray.map { it.asJsonObject.let { row -> RemoteGroup(row.text("id")!!, row.text("title").orEmpty()) } }
        AccountType.TinyTinyRss -> tt("getCategories", mapOf("include_empty" to true)).asJsonArray
            .map { it.asJsonObject }.filter { it.number("id") > 0 }
            .map { RemoteGroup(it.text("id")!!, it.text("title").orEmpty()) }
        else -> emptyList()
    }

    fun feeds(): List<RemoteFeed> = when (type) {
        // The subscription endpoint also contains uncategorized feeds.
        AccountType.Feedly -> json("v3/subscriptions").asJsonArray.map { element ->
            val row = element.asJsonObject
            val id = requireNotNull(row.text("id"))
            RemoteFeed(id, row.array("categories").firstOrNull()?.asJsonObject?.text("id"), row.text("title") ?: id,
                id.removePrefix("feed/"), row.text("visualUrl")?.takeUnless { it == "none" })
        }
        AccountType.Inoreader -> json("reader/api/0/subscription/list", mapOf("output" to "json")).asJsonObject.array("subscriptions").map { element ->
            val row = element.asJsonObject
            val id = requireNotNull(row.text("id"))
            RemoteFeed(id, row.array("categories").firstOrNull()?.asJsonObject?.text("id"), row.text("title") ?: id,
                row.text("url") ?: id.removePrefix("feed/"), row.text("iconUrl"))
        }
        AccountType.Miniflux -> json("v1/feeds").asJsonArray.map { element ->
            val row = element.asJsonObject
            RemoteFeed(row.text("id")!!, row.obj("category").text("id"), row.text("title").orEmpty(), row.text("feed_url")!!)
        }
        AccountType.TinyTinyRss -> tt("getFeeds", mapOf("cat_id" to "-3", "unread_only" to false)).asJsonArray
            .map { it.asJsonObject }.filter { !it.flag("is_cat") && it.number("id") > 0 }
            .map { RemoteFeed(it.text("id")!!, it.text("cat_id"), it.text("title").orEmpty(), it.text("feed_url") ?: it.text("url").orEmpty()) }
        else -> emptyList()
    }

    /** All pages are consumed before status reconciliation; repeated cursors fail instead of looping. */
    suspend fun articles(onPage: suspend (List<RemoteArticle>) -> Unit) {
        when (type) {
            AccountType.Feedly -> {
                val profile = userId ?: json("v3/profile").asJsonObject.text("id").also { userId = it }
                for (stream in listOf("user/$profile/category/global.all", "user/$profile/tag/global.saved")) {
                    var continuation: String? = null
                    val seen = mutableSetOf<String>()
                    do {
                        val query = mutableMapOf("streamId" to stream, "count" to "200", "unreadOnly" to "false")
                        continuation?.let { query["continuation"] = it }
                        val page = json("v3/streams/contents", query).asJsonObject
                        onPage(page.array("items").mapNotNull { feedlyArticle(it.asJsonObject) })
                        continuation = page.text("continuation")?.takeIf { it.isNotEmpty() }
                        check(continuation == null || seen.add(continuation!!)) { "Repeated Feedly page cursor" }
                    } while (continuation != null)
                }
            }
            AccountType.Inoreader -> {
                for (stream in listOf("user/-/state/com.google/reading-list", "user/-/state/com.google/starred")) {
                    var continuation: String? = null
                    val seen = mutableSetOf<String>()
                    do {
                        val query = mutableMapOf("s" to stream, "n" to "200", "output" to "json")
                        continuation?.let { query["c"] = it }
                        val page = json("reader/api/0/stream/contents", query).asJsonObject
                        onPage(page.array("items").mapNotNull { inoreaderArticle(it.asJsonObject) })
                        continuation = page.text("continuation")?.takeIf { it.isNotEmpty() }
                        check(continuation == null || seen.add(continuation!!)) { "Repeated Inoreader page cursor" }
                    } while (continuation != null)
                }
            }
            AccountType.Miniflux -> {
                var offset = 0
                do {
                    val page = json("v1/entries", mapOf("limit" to "200", "offset" to offset.toString(), "order" to "id", "direction" to "asc")).asJsonObject
                    val rows = page.array("entries")
                    onPage(rows.map { minifluxArticle(it.asJsonObject) })
                    offset += rows.size()
                } while (rows.size() == 200)
            }
            AccountType.TinyTinyRss -> {
                // The oldest supported API caps a page at 60; continue until an empty page.
                for (feed in listOf(-4, -1)) {
                    var offset = 0
                    var previous: String? = null
                    do {
                        val rows = tt("getHeadlines", mapOf("feed_id" to feed.toString(), "limit" to "200", "skip" to offset.toString(), "show_content" to true, "view_mode" to "all_articles", "order_by" to "date_reverse")).asJsonArray
                        if (rows.size() > 0) {
                            val signature = rows.joinToString(",") { it.asJsonObject.text("id").orEmpty() }
                            check(signature != previous) { "Tiny Tiny RSS pagination did not advance" }
                            previous = signature
                        }
                        onPage(rows.map { ttArticle(it.asJsonObject) })
                        offset += rows.size()
                    } while (rows.size() > 0)
                }
            }
        }
    }

    private fun feedlyArticle(row: JsonObject): RemoteArticle? {
        val link = row.array("canonical").firstOrNull()?.asJsonObject?.text("href")
            ?: row.array("alternate").firstOrNull()?.asJsonObject?.text("href") ?: row.text("originId") ?: return null
        val tags = row.array("tags").mapNotNull { it.asJsonObject.text("id") }
        return RemoteArticle(row.text("id")!!, row.obj("origin").text("streamId") ?: return null,
            row.text("title").orEmpty(), row.obj("content").text("content") ?: row.obj("summary").text("content").orEmpty(),
            link, row.number("published").takeIf { it > 0 } ?: row.number("crawled"), row.text("author"),
            row.flag("unread"), tags.any { it.endsWith("/tag/global.saved") }, row.obj("visual").text("url")?.takeUnless { it == "none" })
    }
    private fun inoreaderArticle(row: JsonObject): RemoteArticle? {
        val tags = row.array("categories").map { it.asString }
        val link = row.array("canonical").firstOrNull()?.asJsonObject?.text("href")
            ?: row.array("alternate").firstOrNull()?.asJsonObject?.text("href") ?: return null
        return RemoteArticle(row.text("id")!!, row.obj("origin").text("streamId") ?: return null, row.text("title").orEmpty(),
            row.obj("content").text("content") ?: row.obj("summary").text("content").orEmpty(), link,
            row.number("published") * 1_000, row.text("author"), !tags.any { it.endsWith("/state/com.google/read") }, tags.any { it.endsWith("/state/com.google/starred") })
    }
    private fun minifluxArticle(row: JsonObject) = RemoteArticle(row.text("id")!!, row.text("feed_id")!!, row.text("title").orEmpty(),
        row.text("content").orEmpty(), row.text("url").orEmpty(), row.text("published_at")?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0,
        row.text("author"), row.text("status") == "unread", row.flag("starred"))
    private fun ttArticle(row: JsonObject) = RemoteArticle(row.text("id")!!, row.text("feed_id")!!, row.text("title").orEmpty(),
        row.text("content").orEmpty(), row.text("link").orEmpty(), row.number("updated") * 1_000,
        row.text("author"), row.flag("unread"), row.flag("marked"))

    fun setState(ids: List<String>, unread: Boolean? = null, starred: Boolean? = null) {
        require((unread == null) != (starred == null))
        if (ids.isEmpty()) return
        when (type) {
            AccountType.Feedly -> post("v3/markers", mapOf("type" to "entries", "entryIds" to ids, "action" to when {
                unread == true -> "keepUnread"
                unread == false -> "markAsRead"
                starred == true -> "markAsSaved"
                else -> "markAsUnsaved"
            }))
            AccountType.Inoreader -> {
                val tag = "user/-/state/com.google/${if (unread != null) "read" else "starred"}"
                val adding = if (unread != null) !unread else starred!!
                val form = FormBody.Builder().add(if (adding) "a" else "r", tag)
                ids.forEach { form.add("i", it) }
                val response = request("reader/api/0/edit-tag", method = "POST", body = form.build())
                check(response.trim() == "OK") { "Inoreader rejected article state changes" }
            }
            AccountType.Miniflux -> {
                // Explicit state assignment is idempotent, unlike the legacy bookmark toggle endpoint.
                if (starred != null) {
                    val explicit = explicitMinifluxStars ?: runCatching {
                        val version = request("version").trim().removePrefix("v").split('.', '-').take(3).map { it.toIntOrNull() ?: 0 }
                        version.size == 3 && (version[0] > 2 || version[0] == 2 && (version[1] > 2 || version[1] == 2 && version[2] >= 11))
                    }.getOrDefault(false).also { explicitMinifluxStars = it }
                    if (!explicit) {
                        ids.forEach { id ->
                            if (json("v1/entries/$id").asJsonObject.flag("starred") != starred) {
                                request("v1/entries/$id/bookmark", method = "PUT", body = byteArrayOf().toRequestBody(null))
                                check(json("v1/entries/$id").asJsonObject.flag("starred") == starred) { "Miniflux bookmark state changed concurrently; retry" }
                            }
                        }
                        return
                    }
                }
                val payload = mutableMapOf<String, Any>("entry_ids" to ids.map { it.toLong() })
                unread?.let { payload["status"] = if (it) "unread" else "read" }
                starred?.let { payload["starred"] = it }
                post("v1/entries", payload, "PUT")
            }
            AccountType.TinyTinyRss -> tt("updateArticle", mapOf("article_ids" to ids.joinToString(","), "mode" to if (unread ?: starred!!) 1 else 0, "field" to if (unread != null) 2 else 0))
        }
    }
}
