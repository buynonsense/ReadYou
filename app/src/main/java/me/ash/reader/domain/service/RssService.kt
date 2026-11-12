package me.ash.reader.domain.service

import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.di.ApplicationScope

class RssService
@Inject
constructor(
    @ApplicationScope private val coroutineScope: CoroutineScope,
    private val accountService: AccountService,
    private val localRssService: LocalRssService,
    private val feverRssService: FeverRssService,
    private val googleReaderRssService: GoogleReaderRssService,
    private val remoteRssService: RemoteRssService,
) {

    private val currentServiceFlow =
        accountService.currentAccountFlow
            .mapNotNull { it }
            .map { requireNotNull(it.id) to it.type.id }
            .distinctUntilChanged()
            .map { (_, type) -> get(type) }

    fun get() = get(accountService.getCurrentAccount().type.id)

    suspend fun forAccount(accountId: Int): AbstractRssRepository =
        get(requireNotNull(accountService.getAccountById(accountId)) { "Account was deleted" }.type.id)

    fun flow() = currentServiceFlow

    fun get(accountTypeId: Int) =
        when (accountTypeId) {
            AccountType.Local.id -> localRssService
            AccountType.Fever.id -> feverRssService
            AccountType.GoogleReader.id -> googleReaderRssService
            AccountType.FreshRSS.id -> googleReaderRssService
            AccountType.Inoreader.id, AccountType.Feedly.id, AccountType.Miniflux.id, AccountType.TinyTinyRss.id -> remoteRssService
            else -> localRssService
        }
}
