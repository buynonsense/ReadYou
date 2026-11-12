package me.ash.reader.ui.page.settings.accounts

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

class AccountViewModelTest {
    @Test
    fun updatingCredentialsInvalidatesTheProviderForTheAccountType() = runBlocking {
        val accounts = mock<AccountService>()
        val account = Account(id = 42, name = "News", type = AccountType.FreshRSS)
        whenever(accounts.getAccounts()).thenReturn(flowOf(listOf(account)))
        whenever(accounts.getAccountById(42)).thenReturn(account)
        val services = mock<RssService>()
        val provider = mock<AbstractRssRepository>()
        whenever(services.get(any())).thenReturn(provider)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val model = AccountViewModel(
                accounts, services, mock(), mock(), Dispatchers.Unconfined, Dispatchers.Unconfined,
                Dispatchers.Unconfined,
            )
            model.update(42) { copy(name = "Renamed") }
            verify(services).get(AccountType.FreshRSS.id)
            verify(provider).clearAuthorization()
        } finally {
            scope.cancel()
        }
    }
}
