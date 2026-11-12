package me.ash.reader.ui.page.settings.webdav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.service.WebDavSyncService
import me.ash.reader.domain.service.backup.*
import me.ash.reader.infrastructure.coroutine.runCatchingCancellable
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher

@HiltViewModel
class WebDavSyncViewModel @Inject constructor(
    private val service: WebDavSyncService,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {
    enum class Action { TEST, SYNC, BACKUP, RESTORE, RECOVERY, VERSIONS }
    private data class Request(
        val action: Action,
        val mode: RestoreMode = RestoreMode.MERGE,
        val generation: String? = null,
        val recoveryId: String? = null,
        val resolution: ConflictResolution = ConflictResolution.ASK,
    )
    data class WebDavUiState(
        val isLoading: Boolean = false,
        val configurationLoaded: Boolean = false,
        val supportsRestore: Boolean = false,
        val config: WebDavSyncService.WebDavConfig = WebDavSyncService.WebDavConfig(),
        val lastSyncTime: Long = 0L,
        val error: String? = null,
        val canRetry: Boolean = false,
        val progress: BackupProgress? = null,
        val conflict: BackupConflictException? = null,
        val versions: List<BackupVersion> = emptyList(),
        val versionsLoaded: Boolean = false,
        val recoveryCopies: List<BackupRecoveryCopy> = emptyList(),
        val history: List<BackupHistoryEntry> = emptyList(),
        val completedAction: Action? = null,
        val result: WebDavSyncService.WebDavSyncResult? = null,
    )

    private val _uiState = MutableStateFlow(WebDavUiState())
    val uiState = _uiState.asStateFlow()
    private var accountId = 0
    private var operationJob: Job? = null
    private var progressJob: Job? = null
    private var lastRequest: Request? = null

    fun initData(accountId: Int) {
        require(accountId > 0)
        if (this.accountId == accountId) return
        operationJob?.cancel()
        progressJob?.cancel()
        this.accountId = accountId
        lastRequest = null
        _uiState.value = WebDavUiState()
        progressJob = viewModelScope.launch(mainDispatcher) {
            service.progress.collect { progress -> _uiState.update { it.copy(progress = progress[accountId]) } }
        }
        launchOperation(requireConfiguration = false) { target -> refresh(target) }
    }

    private suspend fun refresh(target: Int) {
        val config = service.loadConfig(target)
        val time = service.lastSyncTime(target)
        val supportsRestore = service.supportsRestore(target)
        val history = service.history(target)
        val copies = service.recoveryCopies(target)
        if (target == accountId) _uiState.update {
            it.copy(config = config, lastSyncTime = time, supportsRestore = supportsRestore,
                history = history, recoveryCopies = copies, configurationLoaded = true)
        }
    }

    fun saveConfig(config: WebDavSyncService.WebDavConfig) {
        launchOperation {
            service.saveConfig(it, config)
            refresh(it)
            _uiState.update { state -> state.copy(versions = emptyList(), versionsLoaded = false) }
        }
    }

    fun reconnectPassword(password: String) {
        launchOperation(requireConfiguration = false) {
            service.reconnectPassword(it, password)
            refresh(it)
        }
    }

    fun testConnection() = execute(Request(Action.TEST))
    fun syncNow() = execute(Request(Action.SYNC))
    fun backup() = execute(Request(Action.BACKUP))
    fun loadVersions() = execute(Request(Action.VERSIONS))
    fun restore(mode: RestoreMode, generation: String? = null) = execute(Request(Action.RESTORE, mode, generation))
    fun restoreRecovery(id: String) = execute(Request(Action.RECOVERY, recoveryId = id))
    fun retry() { lastRequest?.let(::execute) }
    fun dismissConflict() { _uiState.update { it.copy(conflict = null) } }
    fun resolveConflict(resolution: ConflictResolution) {
        lastRequest?.copy(resolution = resolution)?.let(::execute)
    }

    private fun execute(request: Request) {
        if (_uiState.value.isLoading) return
        lastRequest = request
        launchOperation { target ->
            val result = when (request.action) {
                Action.TEST -> { service.testConnection(_uiState.value.config).getOrThrow(); null }
                Action.BACKUP -> { service.backup(target).getOrThrow(); null }
                Action.SYNC -> service.syncNow(target, request.resolution).getOrThrow()
                Action.RESTORE -> service.restore(target, request.mode, request.resolution, request.generation).getOrThrow()
                Action.RECOVERY -> service.restoreRecovery(target, requireNotNull(request.recoveryId)).getOrThrow()
                Action.VERSIONS -> {
                    val versions = service.versions(target)
                    if (target == accountId) _uiState.update { it.copy(versions = versions, versionsLoaded = true) }
                    null
                }
            }
            refresh(target)
            if (target == accountId) _uiState.update { it.copy(completedAction = request.action, result = result) }
        }
    }

    private fun launchOperation(requireConfiguration: Boolean = true, block: suspend (Int) -> Unit) {
        if (_uiState.value.isLoading || accountId <= 0 || requireConfiguration && !_uiState.value.configurationLoaded) return
        val target = accountId
        _uiState.update { it.copy(isLoading = true, error = null, canRetry = false, conflict = null, completedAction = null, result = null) }
        operationJob = viewModelScope.launch(mainDispatcher) {
            try {
                val result = withContext(ioDispatcher) { runCatchingCancellable { block(target) } }
                if (target != accountId) return@launch
                result.exceptionOrNull()?.let { error ->
                    _uiState.update { it.copy(error = error.message ?: error.javaClass.simpleName,
                        canRetry = lastRequest != null, conflict = error as? BackupConflictException) }
                    val history = withContext(ioDispatcher) { runCatchingCancellable { service.history(target) }.getOrDefault(emptyList()) }
                    if (target == accountId) _uiState.update { it.copy(history = history) }
                }
            } finally {
                if (target == accountId) _uiState.update { it.copy(isLoading = false) }
            }
        }
    }
}
