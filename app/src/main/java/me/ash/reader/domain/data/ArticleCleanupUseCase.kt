package me.ash.reader.domain.data

import timber.log.Timber
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.coroutine.runCatchingCancellable
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.preference.ArticleCleanupRetentionPreference
import me.ash.reader.infrastructure.preference.AutoCleanupArticlesPreference
import me.ash.reader.infrastructure.preference.AutoCleanupIncludeUnreadPreference
import me.ash.reader.infrastructure.preference.AutoCleanupKeepStarredPreference
import me.ash.reader.infrastructure.preference.ArticleCleanupSettingsStore
import me.ash.reader.domain.service.AccountOperationLock

/**
 * Cleans up history articles by age, either manually from the
 * article cleanup page or automatically after each sync.
 */
class ArticleCleanupUseCase
@Inject
constructor(
    private val articleDao: ArticleDao,
    private val cacheHelper: ReaderCacheHelper,
    private val settingsStore: ArticleCleanupSettingsStore,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val accountLock: AccountOperationLock,
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
        return withContext(ioDispatcher) {
            accountLock.withAccount(accountId) {
            val deleted = articleDao.cleanupBefore(
                accountId, cutoff(retention), includeUnread, keepStarred,
            )
            deleted.forEach { cacheHelper.deleteCacheFor(it.id, accountId) }
            deleted.size
            }
        }
    }

    /**
     * Runs the configured automatic cleanup for [accountId].
     * A no-op when auto cleanup is disabled.
     */
    suspend fun autoCleanup(accountId: Int) {
        val settings = settingsStore.load(accountId)
        if (!settings.enabled) return
        runCatchingCancellable {
            cleanup(
                accountId = accountId,
                retention = settings.retention,
                includeUnread = settings.includeUnread,
                keepStarred = settings.keepStarred,
            )
        }.onSuccess { deleted ->
            if (deleted > 0) {
                Timber.i("Auto cleanup deleted %d articles for account %d", deleted, accountId)
            }
        }.onFailure {
            Timber.e(it, "Auto cleanup failed for account %d", accountId)
        }
    }

    private fun cutoff(retention: ArticleCleanupRetentionPreference): Date =
        Date(System.currentTimeMillis() - retention.value)
}
