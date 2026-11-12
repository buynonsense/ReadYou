package me.ash.reader.domain.service

import android.app.Application
import androidx.room.Room
import androidx.work.ListenableWorker
import com.google.gson.Gson
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.account.security.DESUtils
import me.ash.reader.domain.model.account.security.RemoteReaderSecurityKey
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.preference.AccountSyncStatusStore
import me.ash.reader.ui.ext.getDefaultGroupId
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class RemoteRssServiceTest {
    private lateinit var db: AndroidDatabase
    private lateinit var service: RemoteRssService
    private val server = MockWebServer()
    private val accounts = mock<AccountService>()
    @Volatile private var failEntries = false

    @Before fun setup(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when (request.requestUrl!!.encodedPath) {
                "/v1/me" -> MockResponse().setBody("{}")
                "/v1/categories" -> MockResponse().setBody("[]")
                "/v1/feeds" -> MockResponse().setBody("[]")
                "/v1/entries" -> if (failEntries) MockResponse().setResponseCode(500) else MockResponse().setBody("{\"entries\":[]}")
                else -> MockResponse().setResponseCode(404)
            }
        }
        val credentials = RemoteReaderSecurityKey(serverUrl = server.url("/").toString(), accessToken = "fixture-token")
        whenever(accounts.getAccountById(1)).thenReturn(Account(1, "Fixture", AccountType.Miniflux,
            securityKey = DESUtils.encrypt(Gson().toJson(credentials))))
        db = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).allowMainThreadQueries().build()
        db.groupDao().insert(Group("old-group", "Removed group", 1))
        for ((feed, starred, later) in listOf(Triple("plain", false, false), Triple("starred", true, false), Triple("later", false, true))) {
            db.feedDao().insert(Feed(feed, "Removed subscription", url = "https://example.org/$feed", groupId = "old-group", accountId = 1))
            db.articleDao().insert(Article("article-$feed", Date(1), "Saved", rawDescription = "", shortDescription = "", link = "https://example.org/a/$feed",
                feedId = feed, accountId = 1, isStarred = starred, isReadLater = later))
        }
        db.groupDao().insert(Group("other-group", "Other account", 2))
        db.feedDao().insert(Feed("other-feed", "Other", url = "https://example.org/other", groupId = "other-group", accountId = 2))
        service = RemoteRssService(context, db.articleDao(), db.groupDao(), db.feedDao(), mock(), mock(), mock(),
            Dispatchers.Unconfined, Dispatchers.Unconfined, accounts, db, OkHttpClient(), mock(), AccountSyncStatusStore(context))
    }
    @After fun close() { db.close(); server.shutdown() }

    @Test fun removedSubscriptionsArePrunedButSavedArticlesAndOtherAccountsSurvive(): Unit = runBlocking {
        assertEquals(ListenableWorker.Result.success(), service.sync(1, null, null))
        assertNull(db.feedDao().queryById("plain"))
        assertNull(db.groupDao().queryById("old-group"))
        assertEquals(1.getDefaultGroupId(), db.feedDao().queryById("starred")!!.groupId)
        assertTrue(db.articleDao().queryById("article-starred")!!.article.isStarred)
        assertTrue(db.articleDao().queryById("article-later")!!.article.isReadLater)
        assertNotNull(db.feedDao().queryById("other-feed"))
        verify(accounts, never()).getCurrentAccountId()
    }

    @Test fun failedPaginationCannotPruneExistingSubscriptions(): Unit = runBlocking {
        failEntries = true
        val result = service.sync(1, null, null)
        assertEquals(ListenableWorker.Result.failure(result.outputData), result)
        assertNotNull(db.feedDao().queryById("plain"))
        assertNotNull(db.groupDao().queryById("old-group"))
        assertEquals(3, db.articleDao().queryStateMetadata(1).size)
    }
}
