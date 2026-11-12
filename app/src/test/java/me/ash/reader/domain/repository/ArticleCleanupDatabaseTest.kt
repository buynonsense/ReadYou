package me.ash.reader.domain.repository

import android.app.Application
import androidx.room.Room
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class ArticleCleanupDatabaseTest {
    private lateinit var db: AndroidDatabase
    private lateinit var articles: ArticleDao

    @Before fun openDatabase() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AndroidDatabase::class.java)
            .allowMainThreadQueries().build()
        articles = db.articleDao()
        db.groupDao().insert(Group("group", "Group", 1))
        db.feedDao().insert(Feed(id = "feed", name = "Feed", url = "https://example.org/feed", groupId = "group", accountId = 1))
    }

    @After fun closeDatabase() { db.close() }

    private fun article(id: String, accountId: Int = 1) = Article(
        id = id, date = Date(1), title = id, rawDescription = "Body", shortDescription = "Body",
        link = "https://example.org/$id", feedId = "feed", accountId = accountId, isUnread = false,
        updateAt = Date(1),
    )

    @Test fun cleanupPreservesUnreadStarredReadLaterAndOtherAccounts() = runBlocking {
        articles.insertList(listOf(
            article("read"), article("unread").copy(isUnread = true),
            article("starred").copy(isStarred = true), article("later").copy(isReadLater = true),
            article("other", 2),
        ))
        assertEquals(1, articles.countArticlesBefore(1, Date(2), false, true))
        assertEquals(listOf("read"), articles.cleanupBefore(1, Date(2), false, true).map { it.id })
        assertNull(articles.queryById("read"))
        listOf("unread", "starred", "later", "other").forEach { assertNotNull(articles.queryById(it)) }
        db.feedDao().insertArchivedArticles(listOf(ArchivedArticle(feedId = "feed", link = "https://example.org/read")))
        assertEquals(1, db.feedDao().queryArchivedArticles("feed").size)
    }

    @Test fun failedArchiveRollsBackTheEntireCleanup() = runBlocking {
        articles.insert(article("read"))
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_archive BEFORE INSERT ON archived_article BEGIN SELECT RAISE(ABORT, 'test failure'); END"
        )
        val result = runCatching { articles.cleanupBefore(1, Date(2), false, true) }
        assertTrue(result.isFailure)
        assertNotNull(articles.queryById("read"))
        assertTrue(db.feedDao().queryArchivedArticles("feed").isEmpty())
    }

    @Test fun expirationUsesTheOwningAccountAndPreservesReadLater() = runBlocking {
        articles.insertList(listOf(article("old"), article("later").copy(isReadLater = true), article("other", 2)))
        assertEquals(listOf("old"), articles.archiveExpiredArticles(1, Date(2)).map { it.id })
        assertNotNull(articles.queryById("later"))
        assertNotNull(articles.queryById("other"))
    }
}
