package me.ash.reader.domain.service

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Serializes account mutations, including nested cleanup during a sync. */
@Singleton
class AccountOperationLock @Inject constructor() {
    private val locks = ConcurrentHashMap<Int, Mutex>()

    suspend fun <T> withAccount(accountId: Int, block: suspend () -> T): T {
        require(accountId > 0)
        val held = coroutineContext[HeldAccounts]?.ids.orEmpty()
        if (accountId in held) return block()
        return locks.getOrPut(accountId) { Mutex() }.withLock {
            withContext(HeldAccounts(held + accountId)) { block() }
        }
    }

    private class HeldAccounts(val ids: Set<Int>) : AbstractCoroutineContextElement(Key) {
        companion object Key : CoroutineContext.Key<HeldAccounts>
    }
}
