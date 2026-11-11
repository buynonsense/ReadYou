package me.ash.reader.domain.data

import android.util.Log
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.preference.ArticleCleanupRetentionPreference
import me.ash.reader.infrastructure.preference.AutoCleanupArticlesPreference
import me.ash.reader.infrastructure.preference.AutoCleanupIncludeUnreadPreference
import me.ash.reader.infrastructure.preference.AutoCleanupKeepStarredPreference
import me.ash.reader.infrastructure.preference.SettingsProvider

/**
 * Cleans up history articles by age, either manually from the
 * article cleanup page or automatically after each sync.
 */
class ArticleCleanupUseCase
@Inject
constructor(
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
    private val accountService: AccountService,
    private val settingsProvider: SettingsProvider,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    data class Stats(
        val total: Int = 0,
        val unread: Int = 0,
        val read: Int = 0,
        val starred: Int = 0,
    )

    fun pullStats(accountId: Int): Flow<Stats> =
        combine(
            articleDao.countAllAsFlow(accountId),
            articleDao.countUnreadAsFlow(accountId),
            articleDao.countReadAsFlow(accountId),
            articleDao.countStarredAsFlow(accountId),
        ) { total, unread, read, starred ->
            Stats(total = total, unread = unread, read = read, starred = starred)
        }

    suspend fun countBefore(
        accountId: Int,
        retention: ArticleCleanupRetentionPreference,
        includeUnread: Boolean,
        keepStarred: Boolean,
    ): Int =
        articleDao.countArticlesBefore(
            accountId = accountId,
            before = cutoff(retention),
            includeUnread = includeUnread,
            keepStarred = keepStarred,
        )

    /**
     * Deletes articles older than [retention] and records their links as
     * archived, so the next sync will not re-add them.
     *
     * @return the number of deleted articles.
     */
    suspend fun cleanup(
        accountId: Int,
        retention: ArticleCleanupRetentionPreference,
        includeUnread: Boolean,
        keepStarred: Boolean,
    ): Int {
        val before = cutoff(retention)
        val identities =
            articleDao.queryArticleIdentitiesBefore(
                accountId = accountId,
                before = before,
                includeUnread = includeUnread,
                keepStarred = keepStarred,
            )
        val deleted =
            articleDao.deleteArticlesBefore(
                accountId = accountId,
                before = before,
                includeUnread = includeUnread,
                keepStarred = keepStarred,
            )
        if (identities.isNotEmpty()) {
            feedDao.insertArchivedArticles(
                identities.map { ArchivedArticle(feedId = it.feedId, link = it.link) }
            )
        }
        return deleted
    }

    /**
     * Runs the configured automatic cleanup for [accountId].
     * A no-op when auto cleanup is disabled.
     */
    suspend fun autoCleanup(accountId: Int) {
        val settings = settingsProvider.settings
        if (settings.autoCleanupArticles != AutoCleanupArticlesPreference.ON) return
        runCatching {
            cleanup(
                accountId = accountId,
                retention = settings.autoCleanupRetention,
                includeUnread = settings.autoCleanupIncludeUnread.value,
                keepStarred = settings.autoCleanupKeepStarred.value,
            )
        }.onSuccess { deleted ->
            if (deleted > 0) {
                Log.i("RLog", "ArticleCleanupUseCase: auto cleanup deleted $deleted articles")
            }
        }.onFailure {
            Log.e("RLog", "ArticleCleanupUseCase: auto cleanup failed", it)
        }
    }

    private fun cutoff(retention: ArticleCleanupRetentionPreference): Date =
        Date(System.currentTimeMillis() - retention.value)
}
