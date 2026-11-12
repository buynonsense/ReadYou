package me.ash.reader.ui.page.settings.cleanup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.data.ArticleCleanupUseCase
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.coroutine.runCatchingCancellable
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.preference.ArticleCleanupRetentionPreference
import me.ash.reader.infrastructure.preference.ArticleCleanupSettings
import me.ash.reader.infrastructure.preference.ArticleCleanupSettingsStore

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArticleCleanupViewModel @Inject constructor(
    private val articleCleanupUseCase: ArticleCleanupUseCase,
    private val accountService: AccountService,
    private val settingsStore: ArticleCleanupSettingsStore,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {
    data class ArticleCleanupUiState(
        val cleanupDialogVisible: Boolean = false,
        val previewCount: Int = 0,
        val isCleaning: Boolean = false,
        val error: String? = null,
    )

    private val _uiState = MutableStateFlow(ArticleCleanupUiState())
    val uiState = _uiState.asStateFlow()
    private var previewJob: Job? = null
    private val accountIds = accountService.currentAccountIdFlow.filterNotNull()
    val stats: Flow<ArticleCleanupUseCase.Stats> = accountIds.flatMapLatest { articleCleanupUseCase.pullStats(it) }
    val settings = accountIds.flatMapLatest { settingsStore.observe(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ArticleCleanupSettings())

    init {
        viewModelScope.launch(mainDispatcher) {
            accountIds.collect {
                previewJob?.cancel()
                _uiState.value = ArticleCleanupUiState()
            }
        }
    }

    fun updateSettings(transform: (ArticleCleanupSettings) -> ArticleCleanupSettings) {
        val target = accountService.getCurrentAccountId()
        viewModelScope.launch(mainDispatcher) {
            val result = withContext(ioDispatcher) { runCatchingCancellable { settingsStore.update(target, transform) } }
            if (target == accountService.currentAccountIdFlow.value)
                _uiState.update { it.copy(error = result.exceptionOrNull()?.message) }
        }
    }

    fun showCleanupDialog() { _uiState.update { it.copy(cleanupDialogVisible = true, error = null) } }
    fun hideCleanupDialog() {
        previewJob?.cancel()
        _uiState.update { it.copy(cleanupDialogVisible = false, previewCount = 0) }
    }

    fun preview(retention: ArticleCleanupRetentionPreference, includeUnread: Boolean, keepStarred: Boolean) {
        val target = accountService.getCurrentAccountId()
        previewJob?.cancel()
        previewJob = viewModelScope.launch(mainDispatcher) {
            val result = withContext(ioDispatcher) {
                runCatchingCancellable { articleCleanupUseCase.countBefore(target, retention, includeUnread, keepStarred) }
            }
            if (target != accountService.currentAccountIdFlow.value || !_uiState.value.cleanupDialogVisible) return@launch
            result.fold(
                onSuccess = { count -> _uiState.update { it.copy(previewCount = count, error = null) } },
                onFailure = { error -> _uiState.update { it.copy(error = error.message) } },
            )
        }
    }

    fun cleanup(retention: ArticleCleanupRetentionPreference, includeUnread: Boolean, keepStarred: Boolean, callback: (Int) -> Unit = {}) {
        if (_uiState.value.isCleaning) return
        val target = accountService.getCurrentAccountId()
        _uiState.update { it.copy(isCleaning = true, error = null) }
        viewModelScope.launch(mainDispatcher) {
            try {
                val result = withContext(ioDispatcher) {
                    runCatchingCancellable { articleCleanupUseCase.cleanup(target, retention, includeUnread, keepStarred) }
                }
                if (target != accountService.currentAccountIdFlow.value) return@launch
                result.fold(
                    onSuccess = { deleted ->
                        _uiState.update { it.copy(cleanupDialogVisible = false, previewCount = 0) }
                        callback(deleted)
                    },
                    onFailure = { error -> _uiState.update { it.copy(error = error.message) } },
                )
            } finally {
                if (target == accountService.currentAccountIdFlow.value) _uiState.update { it.copy(isCleaning = false) }
            }
        }
    }
}
