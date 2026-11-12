package me.ash.reader.domain.service

import android.app.Application
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.data.ArticleCleanupUseCase
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class SyncWorkerTest {
    @Test fun retryDoesNotCleanOrUploadAndUsesTheRequestedAccount() = runBlocking {
        val params = mock<WorkerParameters>()
        whenever(params.inputData).thenReturn(workDataOf("accountId" to 42))
        val services = mock<RssService>()
        val provider = mock<AbstractRssRepository>()
        whenever(services.forAccount(42)).thenReturn(provider)
        whenever(provider.sync(42, null, null)).thenReturn(ListenableWorker.Result.retry())
        val cache = mock<ReaderCacheHelper>()
        val cleanup = mock<ArticleCleanupUseCase>()
        val webdav = mock<WebDavSyncService>()
        val manager = mock<WorkManager>()
        val outbox = mock<ArticleStateOutbox>()
        val worker = SyncWorker(RuntimeEnvironment.getApplication(), params, services, cache, cleanup, webdav, manager, AccountOperationLock(), outbox, me.ash.reader.infrastructure.preference.AccountSyncStatusStore(RuntimeEnvironment.getApplication()))

        assertEquals(ListenableWorker.Result.retry(), worker.doWork())
        verify(services).forAccount(42)
        verify(services, never()).get()
        verify(provider, never()).clearKeepArchivedArticles(any())
        verifyNoInteractions(cache, cleanup, webdav, manager)
    }

    @Test fun eachAccountHasIndependentWorkNamesAndTags() {
        assertNotEquals(SyncWorker.periodicWorkName(1), SyncWorker.periodicWorkName(2))
        assertNotEquals(SyncWorker.oneTimeWorkName(1), SyncWorker.oneTimeWorkName(2))
        assertNotEquals(SyncWorker.postSyncWorkName(1), SyncWorker.postSyncWorkName(2))
        assertNotEquals(SyncWorker.accountTag(1), SyncWorker.accountTag(2))
    }
}
