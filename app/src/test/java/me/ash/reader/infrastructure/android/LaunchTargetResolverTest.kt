package me.ash.reader.infrastructure.android

import android.app.Application
import android.content.Intent
import java.util.Date
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.ui.page.common.ExtraName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class LaunchTargetResolverTest {
    private val accounts = mock<AccountService>()
    private val articles = mock<ArticleDao>()
    private val feeds = mock<FeedDao>()
    private val groups = mock<GroupDao>()
    private val resolver = LaunchTargetResolver(accounts, articles, feeds, groups)

    @Test fun articleOwnershipOverridesStaleAccountAndFilterExtras() = runBlocking {
        val owner = Account(id = 2, name = "Second", type = AccountType.Local)
        val feed = Feed(id = "feed", name = "Feed", url = "https://example.org/rss", groupId = "group", accountId = 2)
        val group = Group("group", "Group", 2)
        val article = Article("article", Date(1), "Article", rawDescription = "", shortDescription = "", link = "https://example.org/article", feedId = feed.id, accountId = 2)
        whenever(articles.queryById(article.id)).thenReturn(ArticleWithFeed(article, feed))
        whenever(groups.queryById(group.id)).thenReturn(group)
        whenever(accounts.getAccountById(2)).thenReturn(owner)

        val target = resolver.resolve(LaunchAction.OpenArticle("article", "wrong-feed", "wrong-group", 1))!!
        assertEquals(owner, target.account)
        assertEquals("article", target.articleId)
        assertEquals(feed, target.feed)
        assertEquals(group, target.group)
        verify(feeds, never()).queryById(any())
        Unit
    }

    @Test fun deletedArticleOpensItsAccountListAndDeletedAccountIsIgnored() = runBlocking {
        whenever(accounts.getAccountById(2)).thenReturn(Account(id = 2, name = "Second", type = AccountType.Local))
        val action = LaunchAction.OpenArticle("deleted", null, null, 2)
        assertNull(resolver.resolve(action)!!.articleId)
        assertNull(resolver.resolve(action.copy(accountId = 3)))
    }

    @Test fun launchExtrasAreConsumedExactlyOnceAndRetainAccountId() {
        val intent = Intent().putExtra(ExtraName.ARTICLE_ID, "article").putExtra(ExtraName.ACCOUNT_ID, 2)
        assertEquals(LaunchAction.OpenArticle("article", null, null, 2), intent.consumeLaunchAction())
        assertNull(intent.consumeLaunchAction())
    }
}
