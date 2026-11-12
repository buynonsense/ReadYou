package me.ash.reader.domain.service

import android.content.Context
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.room.withTransaction
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.account.security.CredentialCipher
import me.ash.reader.domain.model.account.security.DESUtils
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.put
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.ext.spacerDollar

class AccountService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val accountDao: AccountDao,
    private val groupDao: GroupDao,
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
    @ApplicationScope private val coroutineScope: CoroutineScope,
    settingsProvider: SettingsProvider,
    private val database: AndroidDatabase,
    private val workManager: WorkManager,
    private val cacheHelper: ReaderCacheHelper,
    private val accountLock: AccountOperationLock,
) {

    private val accountIdKey = intPreferencesKey(DataStoreKey.currentAccountId)
    private val accountsMutex = Mutex()

    private val _credentialErrors = MutableStateFlow<Map<Int, String>>(emptyMap())
    val credentialErrors = _credentialErrors.asStateFlow()

    suspend fun migrateCredentials() {
        val failures = mutableMapOf<Int, String>()
        accountDao.queryAll().forEach { account ->
            val value = account.securityKey
            if (value.isNullOrEmpty() || value == DESUtils.empty || CredentialCipher.isCurrent(value)) return@forEach
            try {
                val migrated = CredentialCipher.migrate(value)
                update(requireNotNull(account.id)) { if (securityKey == value) copy(securityKey = migrated) else this }
            } catch (e: me.ash.reader.domain.model.account.security.CredentialAccessException) {
                failures[requireNotNull(account.id)] = requireNotNull(e.message)
            }
        }
        _credentialErrors.value = failures
    }

    val currentAccountIdFlow =
        settingsProvider.preferencesFlow
            .map { it[accountIdKey] }
            .stateIn(scope = coroutineScope, started = SharingStarted.Eagerly, initialValue = null)

    val currentAccountFlow =
        currentAccountIdFlow
            .combine(getAccounts()) { id, accounts ->
                id?.let { accounts.firstOrNull { it.id == id } }
            }
            .stateIn(scope = coroutineScope, SharingStarted.Eagerly, initialValue = null)

    fun getAccounts(): Flow<List<Account>> = accountDao.queryAllAsFlow()

    suspend fun getAllAccounts(): List<Account> = accountDao.queryAll()

    fun getAccountFlowById(accountId: Int): Flow<Account?> = accountDao.queryAccount(accountId)

    suspend fun getAccountById(accountId: Int): Account? = accountDao.queryById(accountId)

    fun getCurrentAccount(): Account = runBlocking {
        currentAccountFlow.first { it != null } as Account
    }

    fun getCurrentAccountId(): Int = runBlocking {
        currentAccountIdFlow.first { it != null } as Int
    }

    suspend fun isNoAccount(): Boolean = accountDao.queryAll().isEmpty()

    suspend fun addAccount(account: Account): Account {
        val added = database.withTransaction {
            val id = accountDao.insert(account).toInt()
            account.copy(id = id).also {
            when (it.type) {
                AccountType.Local -> {
                    groupDao.insert(
                        Group(
                            id = it.id!!.getDefaultGroupId(),
                            name = context.getString(R.string.defaults),
                            accountId = it.id!!,
                        )
                    )
                }
            }
            }
        }
        switch(added)
        SyncWorker.enqueuePeriodicWork(added, workManager)
        return added
    }

    private fun getDefaultAccount(): Account =
        Account(type = AccountType.Local, name = context.getString(R.string.read_you))

    private suspend fun addDefaultAccount(): Account = addAccount(getDefaultAccount())

    suspend fun initWithDefaultAccount() {
        val account = addDefaultAccount()
        val group = Group(account.id!!.getDefaultGroupId(), context.getString(R.string.defaults), account.id)
        val initialFeed = getInitialFeed(account, group)
        feedDao.insert(initialFeed)
    }

    private fun getInitialFeed(account: Account, group: Group): Feed =
        Feed(
            id = account.id!!.spacerDollar(UUID.randomUUID().toString()),
            name = "ReadYou Releases",
            icon = "https://github.com/ReadYouApp.png",
            url = "https://github.com/ReadYouApp/ReadYou/releases.atom",
            groupId = group.id,
            accountId = account.id,
        )

    fun getDefaultGroup(): Group =
        getCurrentAccountId().let {
            Group(
                id = it.getDefaultGroupId(),
                name = context.getString(R.string.defaults),
                accountId = it,
            )
        }

    suspend fun update(accountId: Int, block: Account.() -> Account) {
        val change = database.withTransaction {
            val previous = accountDao.queryById(accountId) ?: return@withTransaction null
            val updated = previous.block()
            require(updated.id == accountId) { "Account identity cannot be changed" }
            accountDao.update(updated)
            previous to updated
        } ?: return
        rescheduleIfNeeded(change.first, change.second)
    }

    private fun rescheduleIfNeeded(previous: Account, account: Account) {
        if (previous.syncInterval != account.syncInterval ||
            previous.syncOnlyOnWiFi != account.syncOnlyOnWiFi ||
            previous.syncOnlyWhenCharging != account.syncOnlyWhenCharging
        ) {
            SyncWorker.enqueuePeriodicWork(account, workManager)
        }
    }

    suspend fun delete(accountId: Int) = accountsMutex.withLock {
        if (accountDao.queryAll().size == 1) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                context.showToast(context.getString(R.string.must_have_an_account))
            }
            return@withLock
        }
        SyncWorker.cancelAccount(workManager, accountId)
        accountLock.withAccount(accountId) {
        accountDao.queryById(accountId)?.let {
            database.withTransaction {
                articleDao.deleteByAccountId(accountId)
                articleDao.deleteWebDavStates(accountId)
                feedDao.deleteByAccountId(accountId)
                groupDao.deleteByAccountId(accountId)
                accountDao.delete(it)
            }
            cacheHelper.clearCache(accountId)
            if (getCurrentAccountId() == accountId) {
                accountDao.queryAll().firstOrNull()?.let { remaining -> switch(remaining) }
            }
        }
        }
    }

    suspend fun switch(account: Account) {
        context.dataStore.edit { values ->
            val legacyOwner = intPreferencesKey("legacy.settings.owner")
            if (values[legacyOwner] == null) values[accountIdKey]?.let { values[legacyOwner] = it }
            values[accountIdKey] = requireNotNull(account.id)
            values[intPreferencesKey(DataStoreKey.currentAccountType)] = account.type.id
        }
        currentAccountFlow.first { it?.id == account.id }
    }
}
