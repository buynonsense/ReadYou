package me.ash.reader.domain.service

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AccountOperationLockTest {
    @Test(timeout = 10_000) fun sameAccountSerializesWhileOtherAccountsContinueAndNestedCleanupCanEnter() = runBlocking {
        val lock = AccountOperationLock()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var secondEntered = false
        val first = launch {
            lock.withAccount(1) {
                lock.withAccount(1) { started.complete(Unit) }
                release.await()
            }
        }
        started.await()
        val second = launch { lock.withAccount(1) { secondEntered = true } }
        val other = async { lock.withAccount(2) { "independent" } }
        assertEquals("independent", other.await())
        assertFalse(secondEntered)
        release.complete(Unit)
        first.join(); second.join()
        assertTrue(secondEntered)
    }
}
