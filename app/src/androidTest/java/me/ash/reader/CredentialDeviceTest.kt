package me.ash.reader

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.security.KeyStore
import me.ash.reader.domain.model.account.security.CredentialCipher
import me.ash.reader.domain.model.account.security.CredentialAccessException
import me.ash.reader.domain.model.account.security.DESUtils
import org.junit.Assert.*
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/** Run aPersist and bRead in separate instrumentation invocations to exercise process restart. */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class CredentialDeviceTest {
    private val preferences get() = InstrumentationRegistry.getInstrumentation().targetContext
        .getSharedPreferences("credential_device_test", Context.MODE_PRIVATE)

    @Test fun aPersistCredentialForRestart() {
        val first = CredentialCipher.encrypt("test-credential")
        val second = CredentialCipher.encrypt("test-credential")
        assertNotEquals(first, second)
        assertEquals("test-credential", CredentialCipher.decrypt(first))
        assertTrue(preferences.edit().putString("ciphertext", first).commit())
        val migrated = CredentialCipher.migrate(DESUtils.encrypt("legacy-fixture"))
        assertTrue(CredentialCipher.isCurrent(migrated))
        assertEquals("legacy-fixture", CredentialCipher.decrypt(migrated))
    }

    @Test fun bReadCredentialAfterRestart() {
        val ciphertext = requireNotNull(preferences.getString("ciphertext", null))
        assertEquals("test-credential", CredentialCipher.decrypt(ciphertext))
        assertEquals(ciphertext, CredentialCipher.migrate(ciphertext))
    }

    @Test fun cTamperedCredentialIsRejected() {
        val ciphertext = requireNotNull(preferences.getString("ciphertext", null))
        val last = ciphertext.last()
        val tampered = ciphertext.dropLast(1) + if (last == 'A') 'B' else 'A'
        assertTrue(runCatching { CredentialCipher.decrypt(tampered) }.exceptionOrNull() is CredentialAccessException)
        assertEquals(ciphertext, preferences.getString("ciphertext", null))
    }

    @Test fun zMissingKeyRequiresRecoveryWithoutClearingCiphertext() {
        val ciphertext = requireNotNull(preferences.getString("ciphertext", null))
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        store.deleteEntry("ReadYou.credentials.v2")
        assertTrue(runCatching { CredentialCipher.decrypt(ciphertext) }.exceptionOrNull() is CredentialAccessException)
        assertEquals(ciphertext, preferences.getString("ciphertext", null))
        assertFalse(store.containsAlias("ReadYou.credentials.v2"))
        val replacement = CredentialCipher.encrypt("replacement-fixture")
        assertEquals("replacement-fixture", CredentialCipher.decrypt(replacement))
    }
}
