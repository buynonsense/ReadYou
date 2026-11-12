package me.ash.reader.ui.page.settings.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.security.SecurityKey
import me.ash.reader.infrastructure.preference.AccountSyncStatusStore
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.OpmlService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.di.MainDispatcher
import javax.inject.Inject

@HiltViewModel
class AccountViewModel @Inject constructor(
    private val accountService: AccountService,
    private val rssService: RssService,
    private val opmlService: OpmlService,
    private val syncStatusStore: AccountSyncStatusStore,
    @IODispatcher
    private val ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher
    private val defaultDispatcher: CoroutineDispatcher,
    @MainDispatcher
    private val mainDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _accountUiState = MutableStateFlow(AccountUiState())
    val accountUiState: StateFlow<AccountUiState> = _accountUiState.asStateFlow()
    val accounts = accountService.getAccounts()
    val syncStatuses = syncStatusStore.statuses

    fun retrySync(accountId: Int) {
        viewModelScope.launch(ioDispatcher) { rssService.forAccount(accountId).doSyncOneTime(accountId) }
    }
    var addAccountJob: Job? = null

    fun initData(accountId: Int) {
        viewModelScope.launch(ioDispatcher) {
            _accountUiState.update { it.copy(selectedAccount = accountService.getAccountFlowById(accountId)) }
        }
    }

    fun update(accountId: Int, block: Account.() -> Account) {
        viewModelScope.launch(ioDispatcher) {
            accountService.update(accountId, block)
            accountService.getAccountById(accountId)?.let { account ->
                rssService.get(account.type.id).clearAuthorization()
            }
        }
    }

    fun reconnect(accountId: Int, credentials: SecurityKey, callback: (Exception?) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            val error = try {
                val account = requireNotNull(accountService.getAccountById(accountId)) { "Account was deleted" }
                val encrypted = credentials.toString()
                val provider = rssService.get(account.type.id)
                check(provider.validCredentials(account.copy(securityKey = encrypted))) { "Unauthorized" }
                accountService.update(accountId) { copy(securityKey = encrypted) }
                provider.clearAuthorization()
                provider.doSyncOneTime(accountId)
                null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                e
            }
            withContext(mainDispatcher) { callback(error) }
        }
    }

    fun exportAsOPML(accountId: Int, callback: (String) -> Unit = {}) {
        viewModelScope.launch(mainDispatcher) {
            val content = withContext(defaultDispatcher) {
                opmlService.saveToString(accountId, _accountUiState.value.exportOPMLMode == ExportOPMLMode.ATTACH_INFO)
            }
            callback(content)
        }
    }

    fun hideDeleteDialog() {
        _accountUiState.update { it.copy(deleteDialogVisible = false) }
    }

    fun showDeleteDialog() {
        _accountUiState.update { it.copy(deleteDialogVisible = true) }
    }

    fun showClearDialog() {
        _accountUiState.update { it.copy(clearDialogVisible = true) }
    }

    fun hideClearDialog() {
        _accountUiState.update { it.copy(clearDialogVisible = false) }
    }

    fun delete(accountId: Int, callback: () -> Unit = {}) {
        viewModelScope.launch(ioDispatcher) {
            accountService.delete(accountId)
            withContext(mainDispatcher) {
                callback()
            }
        }
    }

    fun clear(account: Account, callback: () -> Unit = {}) {
        viewModelScope.launch(ioDispatcher) {
            rssService.get(account.type.id).deleteAccountArticles(account.id!!)
            withContext(mainDispatcher) {
                callback()
            }
        }
    }

    fun addAccount(account: Account, callback: (account: Account?, exception: Exception?) -> Unit) {
        if (_accountUiState.value.isLoading) return
        setLoading(true)
        addAccountJob = viewModelScope.launch(ioDispatcher) {
            try {
                val rssService = rssService.get(account.type.id)
                if (rssService.validCredentials(account)) {
                    val addAccount = accountService.addAccount(account)
                    rssService.doSyncOneTime(requireNotNull(addAccount.id))
                    withContext(mainDispatcher) {
                        callback(addAccount, null)
                    }
                } else {
                    throw Exception("Unauthorized")
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(mainDispatcher) {
                    callback(null, e)
                }
            } finally {
                setLoading(false)
            }
        }
    }

    fun addAccount(account: Account, credentials: SecurityKey, callback: (Account?, Exception?) -> Unit) {
        viewModelScope.launch(ioDispatcher) {
            val encrypted = try { credentials.toString() } catch (e: Exception) {
                if (e is CancellationException) throw e
                withContext(mainDispatcher) { callback(null, e) }
                return@launch
            }
            addAccount(account.copy(securityKey = encrypted), callback)
        }
    }

    fun switchAccount(targetAccount: Account, callback: () -> Unit = {}) {
        viewModelScope.launch(ioDispatcher) {
            accountService.switch(targetAccount)
            withContext(mainDispatcher) {
                callback()
            }
        }
    }

    fun changeExportOPMLMode(mode: ExportOPMLMode) {
        viewModelScope.launch {
            _accountUiState.update {
                it.copy(
                    exportOPMLMode = mode
                )
            }
        }
    }

    private fun setLoading(isLoading: Boolean) {
        _accountUiState.update { it.copy(isLoading = isLoading) }
    }
    
    fun cancelAdd() {
        addAccountJob?.cancel()
        setLoading(false)
    }
}

data class AccountUiState(
    val selectedAccount: Flow<Account?> = emptyFlow(),
    val deleteDialogVisible: Boolean = false,
    val clearDialogVisible: Boolean = false,
    val exportOPMLMode: ExportOPMLMode = ExportOPMLMode.ATTACH_INFO,
    val isLoading: Boolean = false,
)

sealed class ExportOPMLMode {
    object ATTACH_INFO : ExportOPMLMode()
    object NO_ATTACH : ExportOPMLMode()
}
