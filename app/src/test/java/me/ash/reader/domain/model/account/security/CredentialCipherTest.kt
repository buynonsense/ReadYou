package me.ash.reader.domain.model.account.security

import android.app.Application
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class CredentialCipherTest {
    private val cipher = VersionedCredentialCipher({ SecretKeySpec(ByteArray(32) { 1 }, "AES") })

    @Test fun roundTripUsesRandomNoncesAndAuthenticatedEncryption() {
        val first = cipher.encrypt("test password")
        val second = cipher.encrypt("test password")
        assertNotEquals(first, second)
        assertFalse(first.contains("test password"))
        assertEquals("test password", cipher.decrypt(first))
        val bytes = java.util.Base64.getDecoder().decode(first.removePrefix(VersionedCredentialCipher.PREFIX))
        bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte()
        val tampered = VersionedCredentialCipher.PREFIX + java.util.Base64.getEncoder().encodeToString(bytes)
        assertTrue(runCatching { cipher.decrypt(tampered) }.isFailure)
    }

    @Test fun oldCredentialsAreReadableAndWrongDeviceCannotDecryptNewOnes() {
        val legacy = DESUtils.encrypt("legacy credentials")
        assertEquals("legacy credentials", cipher.decrypt(legacy))
        val otherDevice = VersionedCredentialCipher({ SecretKeySpec(ByteArray(32) { 2 }, "AES") })
        assertTrue(runCatching { otherDevice.decrypt(cipher.encrypt("test password")) }.isFailure)
    }
}
