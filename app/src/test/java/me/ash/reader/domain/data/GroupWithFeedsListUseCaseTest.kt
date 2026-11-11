package me.ash.reader.domain.data

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.preference.SettingsProvider
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GroupWithFeedsListUseCaseTest {
    @Test
    fun initializesFlowsBeforeStartingCollectors() {
        val accountService = mock<AccountService>()
        whenever(accountService.currentAccountFlow).thenReturn(
            MutableStateFlow(Account(id = 1, name = "Fixture", type = AccountType.Local))
        )
        val filters = mock<FilterStateUseCase>()
        whenever(filters.filterStateFlow).thenReturn(MutableStateFlow(FilterState(filter = Filter.Unread)))
        val repository = mock<AbstractRssRepository>()
        whenever(repository.pullFeeds()).thenReturn(flowOf(mutableListOf()))
        whenever(repository.pullImportant(any(), any())).thenReturn(flowOf(emptyMap()))
        val rssService = mock<RssService>()
        whenever(rssService.get()).thenReturn(repository)
        whenever(rssService.get(any())).thenReturn(repository)
        val diffs = mock<DiffMapHolder>()
        whenever(diffs.diffMapSnapshotFlow).thenReturn(MutableStateFlow(emptyMap()))
        val failures = mutableListOf<Throwable>()
        // Start collectors during construction, deterministically exercising the startup race.
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined +
                CoroutineExceptionHandler { _, error -> failures.add(error) }
        )
        try {
            GroupWithFeedsListUseCase(
                scope, Dispatchers.Unconfined, mock<SettingsProvider>(), rssService,
                filters, diffs, accountService,
            )
            assertTrue("Collectors failed during construction: $failures", failures.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
