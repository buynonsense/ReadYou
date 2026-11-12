package me.ash.reader.domain.data

import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.ui.ext.getDefaultGroupId

class GroupWithFeedsListUseCase @Inject constructor(
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val settingsProvider: SettingsProvider,
    private val rssService: RssService,
    private val filterStateUseCase: FilterStateUseCase,
    private val accountService: AccountService,
) {
    private val _groupWithFeedsListFlow = MutableStateFlow<List<GroupWithFeed>>(emptyList())
    val groupWithFeedListFlow: StateFlow<List<GroupWithFeed>> = _groupWithFeedsListFlow

    init {
        applicationScope.launch {
            combine(filterStateUseCase.filterStateFlow, accountService.currentAccountFlow.filterNotNull()) { state, account ->
                state.filter to account
            }.collectLatest { (filter, account) ->
                _groupWithFeedsListFlow.value = emptyList()
                val id = requireNotNull(account.id)
                val repository = rssService.get(account.type.id)
                combine(repository.pullFeeds(id), repository.pullImportant(filter.isStarred(), filter.isUnread(), id), settingsProvider.settingsFlow) { groups, counts, settings ->
                    // Counts and state changes come from the same database. Adding optimistic deltas
                    // here would count a committed change twice while Room emits its invalidation.
                    val hideEmpty = settings.hideEmptyGroups.value && !filter.isAll()
                    groups.filter { it.group.accountId == id }.mapNotNull { item ->
                        val feeds = item.feeds.map { feed -> feed.copy(important = counts[feed.id] ?: 0) }
                            .filter { !hideEmpty || it.important > 0 }
                        if (feeds.isEmpty() && (hideEmpty || item.group.id == id.getDefaultGroupId())) null
                        else item.copy(feeds = feeds.toMutableList())
                    }
                }.flowOn(ioDispatcher).collect { _groupWithFeedsListFlow.value = it }
            }
        }
    }
}
