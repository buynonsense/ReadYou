package me.ash.reader.domain.repository

import android.app.Application
import androidx.paging.PagingSource
import androidx.room.Room
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.ARTICLE_SEARCH_CALLBACK
import me.ash.reader.domain.model.article.ArticleSearchQuery
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import kotlinx.coroutines.Dispatchers
import org.mockito.kotlin.mock
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
class ArticleSearchDatabaseTest {
    private lateinit var db: AndroidDatabase
    private val feed = Feed("feed", "Feed", url = "https://example.org/rss", groupId = "group", accountId = 1)
    private val group = Group("group", "Group", 1)
    private val article = Article("article", Date(1), "Title", rawDescription = "<p>unique_body_term</p>", shortDescription = "Summary", link = "https://example.org/article", feedId = feed.id, accountId = 1)

    @Before fun openDatabase() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), AndroidDatabase::class.java)
            .addCallback(ARTICLE_SEARCH_CALLBACK).allowMainThreadQueries().build()
        db.groupDao().insert(group)
        db.feedDao().insert(feed)
        db.articleDao().insert(article)
    }
    @After fun closeDatabase() { db.close() }

    private suspend fun search(text: String, accountId: Int = 1, feedId: String? = null, groupId: String? = null, unread: Boolean = false, starred: Boolean = false, ascending: Boolean = false): List<String> {
        val query = ArticleSearchQuery.from(text)
        val result = db.articleDao().searchArticles(accountId, query.match, query.literal, query.useSubstring, feedId, groupId, unread, starred, ascending)
            .load(PagingSource.LoadParams.Refresh(null, 50, false))
        val page = result as PagingSource.LoadResult.Page<Int, ArticleWithFeed>
        return page.data.map { it.article.id }
    }

    @Test fun searchFindsRssBodyBeyondTheSummary() = runBlocking {
        assertEquals(listOf("article"), search("unique_body_term"))
    }

    @Test fun cachedBodySurvivesMetadataRefreshAndIsRemovedWhenCacheClears() = runBlocking {
        val cache = ReaderCacheHelper(RuntimeEnvironment.getApplication(), Dispatchers.Unconfined, mock(), db.articleSearchDao())
        cache.writeFullContent(1, "article", "<p>offlineexclusiveword</p>").getOrThrow()
        assertEquals(listOf("article"), search("offlineexclusive"))
        db.articleDao().insert(article.copy(title = "Updated title"))
        assertEquals(listOf("article"), search("offlineexclusiveword"))
        assertTrue(cache.deleteCacheFor("article", 1))
        assertTrue(search("offlineexclusiveword").isEmpty())
    }

    @Test fun cjkSubstringsAndLiteralPunctuationDoNotBecomeQueryOperators() = runBlocking {
        db.articleDao().insert(article.copy(rawDescription = "<p>人工智能改变世界</p>"))
        assertEquals(listOf("article"), search("智能"))
        assertTrue(search("missing OR 智能").isEmpty())
        assertTrue(search("% \" ").isEmpty())
    }

    @Test fun searchRespectsAccountFeedGroupFlagsAndAscendingOrder() = runBlocking {
        db.groupDao().insert(Group("otherGroup", "Other", 2))
        db.feedDao().insert(feed.copy(id = "otherFeed", groupId = "otherGroup", accountId = 2))
        db.articleDao().insert(article.copy(id = "foreign", accountId = 2, feedId = "otherFeed"))
        db.articleDao().insert(article.copy(id = "newer", date = Date(2), isUnread = false, isStarred = true))
        assertEquals(listOf("article", "newer"), search("unique_body_term", ascending = true))
        assertEquals(listOf("newer", "article"), search("unique_body_term"))
        assertEquals(listOf("article"), search("unique_body_term", unread = true))
        assertEquals(listOf("newer"), search("unique_body_term", starred = true))
        assertTrue(search("unique_body_term", feedId = "otherFeed").isEmpty())
        assertTrue(search("unique_body_term", groupId = "otherGroup").isEmpty())
        assertEquals(listOf("foreign"), search("unique_body_term", accountId = 2))
    }

    @Test fun refreshingFeedMetadataPreservesItsArticles() = runBlocking {
        db.feedDao().insert(feed.copy(name = "Updated feed"))
        assertNotNull(db.articleDao().queryById("article"))
    }

    @Test fun refreshingGroupMetadataPreservesItsFeedsAndArticles() = runBlocking {
        db.groupDao().insert(group.copy(name = "Updated group"))
        assertNotNull(db.feedDao().queryById("feed"))
        assertNotNull(db.articleDao().queryById("article"))
    }
}
