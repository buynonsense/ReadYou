package me.ash.reader.domain.data

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.domain.service.AbstractRssRepository
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.infrastructure.preference.Settings
import me.ash.reader.infrastructure.preference.HideEmptyGroupsPreference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class GroupWithFeedsListUseCaseTest {
    @Test fun switchingBetweenLocalAccountsCannotReusePreviousFeedsOrCounts(): Unit = runBlocking {
        val accounts = mock<AccountService>()
        val accountFlow = MutableStateFlow<Account?>(Account(1, "One", AccountType.Local))
        whenever(accounts.currentAccountFlow).thenReturn(accountFlow)
        val filters = mock<FilterStateUseCase>()
        whenever(filters.filterStateFlow).thenReturn(MutableStateFlow(FilterState(filter = Filter.Unread)))
        val settings = mock<SettingsProvider>()
        whenever(settings.settingsFlow).thenReturn(MutableStateFlow(Settings(hideEmptyGroups = HideEmptyGroupsPreference.OFF)))
        val repository = mock<AbstractRssRepository>()
        val firstCounts = MutableStateFlow(mapOf("f1" to 2))
        for (id in 1..2) {
            val group = Group("g$id", "Group", id)
            val feed = Feed("f$id", "Feed", url = "https://example.org/$id", groupId = group.id, accountId = id)
            whenever(repository.pullFeeds(id)).thenReturn(flowOf(mutableListOf(GroupWithFeed(group, mutableListOf(feed)))))
            whenever(repository.pullImportant(false, true, id)).thenReturn(if (id == 1) firstCounts else flowOf(mapOf("f2" to 7)))
        }
        val services = mock<RssService>()
        whenever(services.get(AccountType.Local.id)).thenReturn(repository)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val useCase = GroupWithFeedsListUseCase(scope, Dispatchers.Unconfined, settings, services, filters, accounts)
            withTimeout(5_000) { useCase.groupWithFeedListFlow.first { it.firstOrNull()?.feeds?.firstOrNull()?.important == 2 } }
            firstCounts.value = mapOf("f1" to 1)
            withTimeout(5_000) { useCase.groupWithFeedListFlow.first { it.firstOrNull()?.feeds?.firstOrNull()?.important == 1 } }
            accountFlow.value = Account(2, "Two", AccountType.Local)
            val current = withTimeout(5_000) { useCase.groupWithFeedListFlow.first { it.firstOrNull()?.group?.accountId == 2 } }
            assertEquals("f2", current.single().feeds.single().id)
            assertEquals(7, current.single().feeds.single().important)
            firstCounts.value = mapOf("f1" to 99)
            assertEquals(7, useCase.groupWithFeedListFlow.value.single().feeds.single().important)
        } finally { scope.cancel() }
    }

    @Test
    fun initializesFlowsBeforeStartingCollectors() {
        val accountService = mock<AccountService>()
        whenever(accountService.currentAccountFlow).thenReturn(
            MutableStateFlow(Account(id = 1, name = "Fixture", type = AccountType.Local))
        )
        val filters = mock<FilterStateUseCase>()
        val settings = mock<SettingsProvider>()
        whenever(settings.settingsFlow).thenReturn(MutableStateFlow(Settings()))
        whenever(filters.filterStateFlow).thenReturn(MutableStateFlow(FilterState(filter = Filter.Unread)))
        val repository = mock<AbstractRssRepository>()
        whenever(repository.pullFeeds(any())).thenReturn(flowOf(mutableListOf()))
        whenever(repository.pullImportant(any(), any(), any())).thenReturn(flowOf(emptyMap()))
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
                scope, Dispatchers.Unconfined, settings, rssService,
                filters, accountService,
            )
            assertTrue("Collectors failed during construction: $failures", failures.isEmpty())
        } finally {
            scope.cancel()
        }
    }
}
