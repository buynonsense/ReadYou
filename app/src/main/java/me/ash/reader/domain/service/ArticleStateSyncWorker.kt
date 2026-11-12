package me.ash.reader.domain.service

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

@HiltWorker
class ArticleStateSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val outbox: ArticleStateOutbox,
) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result {
        val accountId = inputData.getInt("accountId", -1)
        if (accountId <= 0) return Result.failure()
        return if (outbox.flush(accountId)) Result.success() else Result.retry()
    }

    companion object {
        fun enqueue(manager: WorkManager, accountId: Int) {
            manager.enqueueUniqueWork(
                "ReadYou_article_state_$accountId", ExistingWorkPolicy.APPEND_OR_REPLACE,
                OneTimeWorkRequestBuilder<ArticleStateSyncWorker>()
                    .setInputData(workDataOf("accountId" to accountId))
                    .addTag(SyncWorker.accountTag(accountId))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                    .build(),
            )
        }
    }
}
