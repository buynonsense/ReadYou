package me.ash.reader.infrastructure.rss

import android.content.Context
import java.io.File
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.service.AccountService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class ReaderCacheAccountTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun fetchingAnArticleUsesItsOwnerEvenAfterAccountSwitch() = runBlocking {
        val context = mock<Context>()
        whenever(context.cacheDir).thenReturn(temporary.root)
        val accounts = mock<AccountService>()
        whenever(accounts.getCurrentAccountId()).thenReturn(2)
        val rss = mock<RssHelper>()
        whenever(rss.parseFullContent("https://example.org/article", "Article"))
            .thenReturn("<p>Offline article</p>")
        val cache = ReaderCacheHelper(context, Dispatchers.Unconfined, rss, mock())
        val article = Article(
            id = "1\$article", date = Date(), title = "Article", rawDescription = "",
            shortDescription = "", link = "https://example.org/article", feedId = "1\$feed",
            accountId = 1,
        )

        assertTrue(cache.checkOrFetchFullContent(article))
        assertTrue(File(temporary.root, "readability/1").listFiles().orEmpty().isNotEmpty())
        assertFalse(File(temporary.root, "readability/2").exists())
    }
}
