package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.rss.ReaderCacheHelper

@HiltWorker
class ReaderWorker
@AssistedInject
constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val rssService: RssService,
    private val cacheHelper: ReaderCacheHelper,
    private val accountLock: AccountOperationLock,
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result {
        val accountId = inputData.getInt("accountId", -1)
        if (accountId <= 0) return Result.failure()
        return accountLock.withAccount(accountId) { fetchAccount(accountId) }
    }

    private suspend fun fetchAccount(accountId: Int): Result {
        val service = try {
            rssService.forAccount(accountId)
        } catch (_: IllegalArgumentException) {
            return Result.failure()
        }
        val semaphore = Semaphore(2)

        val deferredList =
            withContext(Dispatchers.IO) {
                val articleList = service.queryUnreadFullContentArticles(accountId)
                articleList.map {
                    async { semaphore.withPermit { cacheHelper.checkOrFetchFullContent(it) } }
                }
            }

        return if (deferredList.awaitAll().any { !it }) Result.retry() else Result.success()
    }
}
