package me.ash.reader

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.lang.ref.WeakReference
import java.util.Date
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.data.ArticlePagingListUseCase
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleFlowItem
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.infrastructure.android.AndroidApp
import me.ash.reader.infrastructure.preference.SyncIntervalPreference
import me.ash.reader.ui.ext.getDefaultGroupId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ArticlePagingListMemoryTest {
    @Test
    fun releasesArticlesFromPreviousFilters() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation()
            .targetContext.applicationContext as AndroidApp
        val previousAccount = withTimeout(15_000) {
            app.accountService.currentAccountFlow.first { it != null }!!
        }
        val account = app.accountService.addAccount(
            Account(
                name = "Paging memory fixture",
                type = AccountType.Local,
                syncInterval = SyncIntervalPreference.Manually,
            )
        )
        val accountId = account.id!!
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val db = app.androidDatabase
        val feed = Feed(
            id = "paging-memory-feed-$accountId",
            name = "Paging memory fixture",
            url = "https://example.invalid/paging",
            accountId = accountId,
            groupId = accountId.getDefaultGroupId(),
        )
        try {
            withTimeout(5_000) { app.accountService.currentAccountIdFlow.first { it == accountId } }
            db.feedDao().insert(feed)
            db.articleDao().insertList((0 until 200).map { index ->
                Article(
                    id = "paging-memory-$accountId-$index",
                    date = Date(1_700_000_000_000L + index),
                    title = "Paging fixture $index",
                    rawDescription = "<p>Article $index</p>" + "Memory fixture text.".repeat(2_000),
                    shortDescription = "Paging fixture",
                    link = "https://example.invalid/paging/$index",
                    feedId = feed.id,
                    accountId = accountId,
                )
            })
            val filters = FilterStateUseCase(app.settingsProvider, db.feedDao(), db.groupDao(), scope)
            filters.updateFilterState(filter = Filter.Unread)
            val useCase = ArticlePagingListUseCase(
                app.rssService, app.androidStringsHelper, scope, Dispatchers.IO,
                app.settingsProvider, filters, app.accountService,
            )
            awaitNewArticles(useCase, null)
            val initialJobs = scope.coroutineContext[kotlinx.coroutines.Job]!!.children.count()
            val oldArticle = firstArticle(useCase)
            val filterChanges = InstrumentationRegistry.getArguments()
                .getString("filterChanges")?.toInt() ?: 10
            repeat(filterChanges) { index ->
                val previous = firstArticle(useCase)
                filters.updateFilterState(feed = if (index % 2 == 0) feed else null)
                awaitNewArticles(useCase, previous)
            }
            for (attempt in 0 until 10) {
                Runtime.getRuntime().gc()
                if (oldArticle.get() == null) break
                delay(100)
            }
            val currentJobs = scope.coroutineContext[kotlinx.coroutines.Job]!!.children.count()
            val runtime = Runtime.getRuntime()
            println(
                "PAGING_MEMORY filterChanges=$filterChanges initialJobs=$initialJobs " +
                    "currentJobs=$currentJobs javaHeap=${runtime.totalMemory() - runtime.freeMemory()} " +
                    "oldArticleRetained=${oldArticle.get() != null}"
            )
            assertTrue(
                "Switching filters must release the previous pages and article bodies",
                oldArticle.get() == null,
            )
            assertEquals("Switching filters must not accumulate cache jobs", initialJobs, currentJobs)
        } finally {
            scope.cancel()
            app.accountService.delete(accountId)
            app.accountService.switch(previousAccount)
        }
    }

    private fun firstArticle(useCase: ArticlePagingListUseCase): WeakReference<Article> =
        WeakReference(
            useCase.itemSnapshotList.items.filterIsInstance<ArticleFlowItem.Article>()
                .first().articleWithFeed.article
        )

    private suspend fun awaitNewArticles(
        useCase: ArticlePagingListUseCase,
        previous: WeakReference<Article>?,
    ) {
        withTimeout(10_000) {
            while (!hasNewArticles(useCase, previous)) delay(20)
        }
    }

    private fun hasNewArticles(
        useCase: ArticlePagingListUseCase,
        previous: WeakReference<Article>?,
    ): Boolean {
        val articles = useCase.itemSnapshotList.items.filterIsInstance<ArticleFlowItem.Article>()
        return articles.size >= 150 && articles.first().articleWithFeed.article !== previous?.get()
    }
}
