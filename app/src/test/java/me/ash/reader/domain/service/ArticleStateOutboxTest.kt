package me.ash.reader.domain.service

import android.app.Application
import androidx.room.Room
import java.io.IOException
import java.util.Date
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.PendingArticleState
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase
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
class ArticleStateOutboxTest {
    private lateinit var db: AndroidDatabase
    private val accounts = mock<AccountService>()
    private val services = mock<RssService>()
    private val provider = mock<AbstractRssRepository>()
    private lateinit var outbox: ArticleStateOutbox

    @Before fun setup() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AndroidDatabase::class.java)
            .allowMainThreadQueries().build()
        for (id in 1..2) {
            db.groupDao().insert(Group("g$id", "Group", id))
            db.feedDao().insert(Feed("f$id", "Feed", url = "https://example.org/$id", groupId = "g$id", accountId = id))
            db.articleDao().insert(Article("a$id", Date(1), "Article", rawDescription = "", shortDescription = "", link = "https://example.org/article", feedId = "f$id", accountId = id))
            whenever(accounts.getAccountById(id)).thenReturn(Account(id, "Account", AccountType.GoogleReader))
            whenever(services.forAccount(id)).thenReturn(provider)
        }
        outbox = ArticleStateOutbox(db, services, accounts, AccountOperationLock(), me.ash.reader.infrastructure.preference.AccountSyncStatusStore(RuntimeEnvironment.getApplication()))
    }
    @After fun close() { db.close() }

    @Test fun failedUploadSurvivesRecreationAndIsRetriedForItsOwningAccount() = runBlocking {
        outbox.record(1, "a1", PendingArticleState.UNREAD, false)
        whenever(provider.syncReadStatus(1, setOf("a1"), false)).thenAnswer { throw IOException("offline") }
        assertFalse(outbox.flush(1))
        assertFalse(db.articleDao().queryById("a1")!!.article.isUnread)
        assertTrue(db.articleDao().queryById("a2")!!.article.isUnread)
        val restarted = ArticleStateOutbox(db, services, accounts, AccountOperationLock(), me.ash.reader.infrastructure.preference.AccountSyncStatusStore(RuntimeEnvironment.getApplication()))
        doReturn(setOf("a1")).whenever(provider).syncReadStatus(1, setOf("a1"), false)
        assertTrue(restarted.flush(1))
        assertTrue(db.pendingArticleStateDao().pending(1).isEmpty())
        verify(services, never()).get()
        verify(services, never()).forAccount(2)
        Unit
    }

    @Test fun acknowledgementOfAnOlderToggleCannotRemoveTheNewerToggle() = runBlocking {
        outbox.record(1, "a1", PendingArticleState.UNREAD, false)
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        whenever(provider.syncReadStatus(1, setOf("a1"), false)).thenAnswer {
            started.complete(Unit)
            runBlocking { release.await() }
            setOf("a1")
        }
        val upload = async(kotlinx.coroutines.Dispatchers.IO) { outbox.flush(1) }
        started.await()
        outbox.record(1, "a1", PendingArticleState.UNREAD, true)
        release.complete(Unit)
        assertFalse(upload.await())
        val pending = db.pendingArticleStateDao().pending(1).single()
        assertTrue(pending.value)
        assertTrue(db.articleDao().queryById("a1")!!.article.isUnread)
    }

    @Test fun refreshReappliesPendingStatesAndRejectsForeignAccountWrites() = runBlocking {
        outbox.record(1, "a1", PendingArticleState.UNREAD, false)
        outbox.record(1, "a2", PendingArticleState.UNREAD, false)
        db.articleDao().markAsReadByArticleId(1, "a1", true)
        outbox.reapplyPending(1)
        assertFalse(db.articleDao().queryById("a1")!!.article.isUnread)
        assertTrue(db.articleDao().queryById("a2")!!.article.isUnread)
        assertTrue(db.pendingArticleStateDao().pending(2).isEmpty())
    }

    @Test fun bulkReadAndOfflineStarsAreDurableAndAccountBound() = runBlocking {
        outbox.markAsRead(1, null, null, null, null, false)
        outbox.record(1, "a1", PendingArticleState.STARRED, true)
        assertEquals(2, db.pendingArticleStateDao().pending(1).size)
        assertTrue(db.articleDao().queryById("a1")!!.article.isStarred)
        assertTrue(db.articleDao().queryById("a2")!!.article.isUnread)
        whenever(provider.syncReadStatus(1, setOf("a1"), false)).thenReturn(setOf("a1"))
        whenever(provider.syncStarredStatus(1, setOf("a1"), true)).thenReturn(setOf("a1"))
        assertTrue(outbox.flush(1))
    }
}
