package me.ash.reader.ui.page.settings.webdav

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.service.WebDavSyncService
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher

@HiltViewModel
class WebDavSyncViewModel
@Inject
constructor(
    private val webDavSyncService: WebDavSyncService,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {

    data class WebDavUiState(
        val isLoading: Boolean = false,
        val config: WebDavSyncService.WebDavConfig = WebDavSyncService.WebDavConfig(),
        val lastSyncTime: Long = 0L,
    )

    private val _uiState = MutableStateFlow(WebDavUiState())
    val uiState: StateFlow<WebDavUiState> = _uiState.asStateFlow()

    private var accountId: Int = 0

    fun initData(accountId: Int) {
        if (this.accountId == accountId) return
        this.accountId = accountId
        refresh()
    }

    private fun refresh() {
        applicationScope.launch(ioDispatcher) {
            _uiState.update {
                it.copy(
                    config = webDavSyncService.loadConfig(),
                    lastSyncTime = webDavSyncService.lastSyncTime(),
                )
            }
        }
    }

    fun saveConfig(config: WebDavSyncService.WebDavConfig) {
        applicationScope.launch(ioDispatcher) {
            webDavSyncService.saveConfig(config)
            _uiState.update { it.copy(config = config) }
        }
    }

    fun testConnection(onResult: (Result<Unit>) -> Unit) {
        if (_uiState.value.isLoading) return
        setLoading(true)
        applicationScope.launch(ioDispatcher) {
            val result = webDavSyncService.testConnection(_uiState.value.config)
            withContext(mainDispatcher) {
                setLoading(false)
                onResult(result)
            }
        }
    }

    fun syncNow(onResult: (Result<WebDavSyncService.WebDavSyncResult>) -> Unit) {
        if (_uiState.value.isLoading) return
        setLoading(true)
        applicationScope.launch(ioDispatcher) {
            val result = webDavSyncService.syncNow(accountId)
            withContext(mainDispatcher) {
                setLoading(false)
                if (result.isSuccess) refresh()
                onResult(result)
            }
        }
    }

    fun backup(onResult: (Result<Unit>) -> Unit) {
        if (_uiState.value.isLoading) return
        setLoading(true)
        applicationScope.launch(ioDispatcher) {
            val result = webDavSyncService.backup(accountId)
            withContext(mainDispatcher) {
                setLoading(false)
                if (result.isSuccess) refresh()
                onResult(result)
            }
        }
    }

    fun restore(onResult: (Result<WebDavSyncService.WebDavSyncResult>) -> Unit) {
        if (_uiState.value.isLoading) return
        setLoading(true)
        applicationScope.launch(ioDispatcher) {
            val result = webDavSyncService.restore(accountId)
            withContext(mainDispatcher) {
                setLoading(false)
                if (result.isSuccess) refresh()
                onResult(result)
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        _uiState.update { it.copy(isLoading = isLoading) }
    }
}
