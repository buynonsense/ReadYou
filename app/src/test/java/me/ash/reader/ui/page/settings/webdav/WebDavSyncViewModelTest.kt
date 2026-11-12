package me.ash.reader.ui.page.settings.webdav

import androidx.lifecycle.ViewModelStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.service.WebDavSyncService
import me.ash.reader.domain.service.backup.*
import org.junit.Assert.*
import org.junit.Test
import org.mockito.kotlin.*

class WebDavSyncViewModelTest {
    @Test fun conflictsRemainVisibleAndRetryKeepsTheSelectedAccountAndResolution(): Unit = runBlocking {
        val service = mock<WebDavSyncService>()
        whenever(service.progress).thenReturn(MutableStateFlow(emptyMap()))
        whenever(service.loadConfig(7)).thenReturn(WebDavSyncService.WebDavConfig(serverUrl = "https://example.org", namespaceId = "fixture"))
        whenever(service.lastSyncTime(7)).thenReturn(0L)
        whenever(service.supportsRestore(7)).thenReturn(true)
        whenever(service.history(7)).thenReturn(emptyList())
        whenever(service.recoveryCopies(7)).thenReturn(emptyList())
        val conflict = BackupConflictException(listOf(BackupConflict("article:key", "article", "Article", "Read", "Unread")), 1)
        whenever(service.syncNow(7, ConflictResolution.ASK)).thenReturn(Result.failure(conflict))
        whenever(service.syncNow(7, ConflictResolution.KEEP_REMOTE)).thenReturn(Result.success(WebDavSyncService.WebDavSyncResult(articlesUpdated = 1)))
        val model = WebDavSyncViewModel(service, Dispatchers.Unconfined, Dispatchers.Unconfined)
        val store = ViewModelStore().apply { put("test", model) }
        try {
            model.initData(7)
            withTimeout(5_000) { model.uiState.first { !it.isLoading } }
            assertTrue(model.uiState.value.toString(), model.uiState.value.configurationLoaded)
            model.syncNow()
            withTimeout(5_000) { model.uiState.first { !it.isLoading } }
            assertEquals(conflict, model.uiState.value.conflict)
            assertFalse(model.uiState.value.isLoading)
            val pendingConflict = model.uiState.value
            model.refreshLocalData()
            withTimeout(5_000) { model.uiState.first { !it.isLoading } }
            assertEquals(pendingConflict, model.uiState.value)
            model.resolveConflict(ConflictResolution.KEEP_REMOTE)
            withTimeout(5_000) { model.uiState.first { !it.isLoading } }
            assertNull(model.uiState.value.conflict)
            assertEquals(1, model.uiState.value.result!!.articlesUpdated)
            val completedSync = model.uiState.value
            model.refreshLocalData()
            withTimeout(5_000) { model.uiState.first { !it.isLoading } }
            assertEquals(completedSync, model.uiState.value)
            model.retry()
            withTimeout(5_000) { model.uiState.first { !it.isLoading } }
            verify(service, times(2)).syncNow(7, ConflictResolution.KEEP_REMOTE)
            verify(service, never()).syncNow(1, ConflictResolution.KEEP_REMOTE)
        } finally { store.clear() }
    }
}
