package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.*
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import me.ash.reader.domain.data.ArticleCleanupUseCase
import me.ash.reader.domain.model.account.Account
import me.ash.reader.infrastructure.rss.ReaderCacheHelper

@HiltWorker
class SyncWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rssService: RssService,
    private val readerCacheHelper: ReaderCacheHelper,
    private val articleCleanupUseCase: ArticleCleanupUseCase,
    private val webDavSyncService: WebDavSyncService,
    private val workManager: WorkManager,
    private val accountLock: AccountOperationLock,
    private val articleStateOutbox: ArticleStateOutbox,
    private val statusStore: me.ash.reader.infrastructure.preference.AccountSyncStatusStore,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val data = inputData
        val accountId = data.getInt("accountId", -1)
        if (accountId <= 0) return Result.failure()
        statusStore.begin(accountId)
        return try {
            accountLock.withAccount(accountId) { syncAccount(accountId) }.also { result ->
                if (result == Result.success(result.outputData)) {
                    statusStore.succeeded(accountId)
                } else {
                    statusStore.failed(
                        accountId,
                        statusStore.current(accountId)?.error ?: result.outputData.getString("error"),
                    )
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            statusStore.progress(accountId, "interrupted")
            statusStore.failed(accountId, null)
            throw e
        } catch (e: Exception) {
            statusStore.failed(accountId, e.message)
            Result.retry()
        }
    }

    private suspend fun syncAccount(accountId: Int): Result {
        val data = inputData
        val feedId = data.getString("feedId")
        val groupId = data.getString("groupId")

        val service = try {
            rssService.forAccount(accountId)
        } catch (_: IllegalArgumentException) {
            return Result.failure()
        }
        articleStateOutbox.flush(accountId)
        val result = try {
            service.sync(accountId = accountId, feedId = feedId, groupId = groupId)
        } finally {
            articleStateOutbox.reapplyPending(accountId)
        }
        if (result != Result.success(result.outputData)) return result

        statusStore.progress(accountId, "cleanup")
        service.clearKeepArchivedArticles(accountId).forEach {
            readerCacheHelper.deleteCacheFor(articleId = it.id, accountId = accountId)
        }
        articleCleanupUseCase.autoCleanup(accountId)
        statusStore.progress(accountId, "webdav")
        webDavSyncService.autoSync(accountId)
        workManager
            .beginUniqueWork(
                        uniqueWorkName = postSyncWorkName(accountId),
                        existingWorkPolicy = ExistingWorkPolicy.APPEND_OR_REPLACE,
                        OneTimeWorkRequestBuilder<ReaderWorker>()
                            .setInputData(workDataOf("accountId" to accountId))
                            .addTag(accountTag(accountId))
                            .addTag(READER_TAG)
                            .addTag(ONETIME_WORK_TAG)
                            .setBackoffCriteria(
                                backoffPolicy = BackoffPolicy.EXPONENTIAL,
                                backoffDelay = 30,
                                timeUnit = TimeUnit.SECONDS,
                            )
                            .build(),
                    )
                    .then(OneTimeWorkRequestBuilder<WidgetUpdateWorker>().build())
                    .enqueue()
        return result
    }

    companion object {
        fun periodicWorkName(accountId: Int) = "ReadYou_sync_$accountId"
        fun oneTimeWorkName(accountId: Int) = "ReadYou_once_$accountId"
        fun postSyncWorkName(accountId: Int) = "ReadYou_post_$accountId"
        fun accountTag(accountId: Int) = "ReadYou_account_$accountId"

        const val SYNC_TAG = "SYNC_TAG"
        const val READER_TAG = "READER_TAG"
        const val ONETIME_WORK_TAG = "ONETIME_WORK_TAG"
        const val PERIODIC_WORK_TAG = "PERIODIC_WORK_TAG"

        fun cancelOneTimeWork(workManager: WorkManager, accountId: Int) {
            workManager.cancelUniqueWork(oneTimeWorkName(accountId))
        }

        fun cancelPeriodicWork(workManager: WorkManager, accountId: Int) {
            workManager.cancelUniqueWork(periodicWorkName(accountId))
        }

        fun cancelAccount(workManager: WorkManager, accountId: Int) {
            workManager.cancelAllWorkByTag(accountTag(accountId))
        }

        fun migrateLegacyWork(workManager: WorkManager) {
            listOf("ReadYou", "FETCH_FULL_CONTENT_PERIODIC", "POST_SYNC_WORK", "SYNC_ONETIME")
                .forEach { workManager.cancelUniqueWork(it) }
        }

        fun enqueueOneTimeWork(workManager: WorkManager, inputData: Data = workDataOf()) {
            val accountId = inputData.getInt("accountId", -1)
            require(accountId > 0) { "Sync requires an account" }
            workManager
                .beginUniqueWork(
                    oneTimeWorkName(accountId),
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<SyncWorker>()
                        .addTag(accountTag(accountId))
                        .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                        .addTag(SYNC_TAG)
                        .addTag(ONETIME_WORK_TAG)
                        .setInputData(inputData)
                        .build(),
                )
                .enqueue()
        }

        fun enqueuePeriodicWork(account: Account, workManager: WorkManager) {
            val syncInterval = account.syncInterval
            val syncOnlyWhenCharging = account.syncOnlyWhenCharging
            val syncOnlyOnWiFi = account.syncOnlyOnWiFi
            val accountId = requireNotNull(account.id)
            if (syncInterval.value <= 0) {
                cancelPeriodicWork(workManager, accountId)
                return
            }

            workManager.enqueueUniquePeriodicWork(
                periodicWorkName(accountId),
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SyncWorker>(syncInterval.value, TimeUnit.MINUTES)
                    .setConstraints(
                        Constraints.Builder()
                            .setRequiresCharging(syncOnlyWhenCharging.value)
                            .setRequiredNetworkType(
                                if (syncOnlyOnWiFi.value) NetworkType.UNMETERED
                                else NetworkType.CONNECTED
                            )
                            .build()
                    )
                    .setBackoffCriteria(
                        backoffPolicy = BackoffPolicy.EXPONENTIAL,
                        backoffDelay = 30,
                        timeUnit = TimeUnit.SECONDS,
                    )
                    .setInputData(workDataOf("accountId" to account.id))
                    .addTag(SYNC_TAG)
                    .addTag(accountTag(accountId))
                    .addTag(PERIODIC_WORK_TAG)
                    .setInitialDelay(syncInterval.value, TimeUnit.MINUTES)
                    .build(),
            )
        }
    }
}
