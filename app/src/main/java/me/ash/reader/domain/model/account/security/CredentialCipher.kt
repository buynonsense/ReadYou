package me.ash.reader.domain.model.account.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Authenticated, versioned envelopes. Legacy DES is read only during migration. */
internal class VersionedCredentialCipher(
    private val encryptionKey: () -> SecretKey,
    private val decryptionKey: () -> SecretKey = encryptionKey,
) {
    fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey())
        cipher.updateAAD(AAD)
        return PREFIX + Base64.getEncoder().encodeToString(cipher.iv + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8)))
    }

    fun decrypt(envelope: String): String {
        if (!envelope.startsWith(PREFIX)) return DESUtils.decrypt(envelope)
        val bytes = Base64.getDecoder().decode(envelope.removePrefix(PREFIX))
        require(bytes.size >= IV_BYTES + TAG_BYTES) { "Invalid credential envelope" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, decryptionKey(), GCMParameterSpec(128, bytes.copyOfRange(0, IV_BYTES)))
        cipher.updateAAD(AAD)
        return String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8)
    }

    companion object {
        const val PREFIX = "keystore-v2:"
        private const val IV_BYTES = 12
        private const val TAG_BYTES = 16
        private val AAD = "ReadYou credential v2".toByteArray(Charsets.UTF_8)
    }
}

class CredentialAccessException(cause: Exception) : IllegalStateException(
    "Unable to unlock saved credentials. Reconnect this account on this device.", cause,
)

object CredentialCipher {
    private const val ALIAS = "ReadYou.credentials.v2"
    private val cipher = VersionedCredentialCipher(
        encryptionKey = { key(create = true) },
        decryptionKey = { key(create = false) },
    )

    @Synchronized
    private fun key(create: Boolean): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        check(create) { "Credential key is unavailable" }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build())
            generateKey()
        }
    }

    fun isCurrent(value: String) = value.startsWith(VersionedCredentialCipher.PREFIX)

    fun encrypt(value: String): String = protect { cipher.encrypt(value) }
    fun decrypt(value: String): String = protect { cipher.decrypt(value) }
    fun migrate(value: String): String = if (isCurrent(value)) value else encrypt(decrypt(value))

    private inline fun <T> protect(block: () -> T): T = try { block() } catch (e: Exception) {
        throw CredentialAccessException(e)
    }
}
