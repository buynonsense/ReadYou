package me.ash.reader.ui.page.settings.cleanup

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.data.ArticleCleanupUseCase
import me.ash.reader.domain.service.AccountService
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import me.ash.reader.infrastructure.preference.ArticleCleanupRetentionPreference

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ArticleCleanupViewModel
@Inject
constructor(
    private val articleCleanupUseCase: ArticleCleanupUseCase,
    private val accountService: AccountService,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    @MainDispatcher private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {

    data class ArticleCleanupUiState(
        val cleanupDialogVisible: Boolean = false,
        val previewCount: Int = 0,
        val isCleaning: Boolean = false,
    )

    private val _uiState = MutableStateFlow(ArticleCleanupUiState())
    val uiState: StateFlow<ArticleCleanupUiState> = _uiState.asStateFlow()

    val stats: Flow<ArticleCleanupUseCase.Stats> =
        accountService.currentAccountIdFlow
            .filterNotNull()
            .flatMapLatest { articleCleanupUseCase.pullStats(it) }

    fun showCleanupDialog() {
        _uiState.update { it.copy(cleanupDialogVisible = true) }
    }

    fun hideCleanupDialog() {
        _uiState.update { it.copy(cleanupDialogVisible = false, previewCount = 0) }
    }

    fun preview(
        retention: ArticleCleanupRetentionPreference,
        includeUnread: Boolean,
        keepStarred: Boolean,
    ) {
        applicationScope.launch(ioDispatcher) {
            val count =
                articleCleanupUseCase.countBefore(
                    accountId = accountService.getCurrentAccountId(),
                    retention = retention,
                    includeUnread = includeUnread,
                    keepStarred = keepStarred,
                )
            withContext(mainDispatcher) {
                _uiState.update { it.copy(previewCount = count) }
            }
        }
    }

    fun cleanup(
        retention: ArticleCleanupRetentionPreference,
        includeUnread: Boolean,
        keepStarred: Boolean,
        callback: (Int) -> Unit = {},
    ) {
        if (_uiState.value.isCleaning) return
        _uiState.update { it.copy(isCleaning = true) }
        applicationScope.launch(ioDispatcher) {
            val deleted =
                articleCleanupUseCase.cleanup(
                    accountId = accountService.getCurrentAccountId(),
                    retention = retention,
                    includeUnread = includeUnread,
                    keepStarred = keepStarred,
                )
            withContext(mainDispatcher) {
                _uiState.update {
                    it.copy(isCleaning = false, cleanupDialogVisible = false, previewCount = 0)
                }
                callback(deleted)
            }
        }
    }
}
