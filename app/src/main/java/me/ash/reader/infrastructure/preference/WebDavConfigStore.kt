package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.datastore.preferences.core.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import me.ash.reader.domain.model.account.security.CredentialCipher
import me.ash.reader.domain.service.WebDavSyncService.WebDavConfig
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore

@Singleton
class CredentialStore @Inject constructor() {
    fun encrypt(value: String) = CredentialCipher.encrypt(value)
    fun decrypt(value: String) = CredentialCipher.decrypt(value)
    fun migrate(value: String) = CredentialCipher.migrate(value)
}

/** The old global configuration is claimed once by the previously selected account. */
@Singleton
class WebDavConfigStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val credentials: CredentialStore,
) {
    suspend fun load(accountId: Int): WebDavConfig {
        require(accountId > 0)
        val preferences = context.dataStore.edit { values ->
            if (values[textKey(accountId, "namespace")] == null) {
                values[textKey(accountId, "namespace")] = WebDavConfig.DEFAULT_NAMESPACE_ID
            }
            val migration = booleanPreferencesKey("webdav.accounts.migrated")
            val previousOwner = values[intPreferencesKey("legacy.settings.owner")] ?: values[intPreferencesKey(DataStoreKey.currentAccountId)]
            if (values[migration] != true && previousOwner != null) {
                val url = values[stringPreferencesKey(DataStoreKey.webdavServerUrl)].orEmpty()
                val encrypted = values[stringPreferencesKey(DataStoreKey.webdavPassword)].orEmpty()
                val migratedPassword = if (encrypted.isEmpty()) "" else try {
                    credentials.migrate(encrypted)
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException || previousOwner == accountId) throw e
                    // A locked legacy account must not block an unrelated account's settings.
                    return@edit
                }
                if (url.isNotBlank()) {
                    values[textKey(previousOwner, "url")] = url
                    values[textKey(previousOwner, "username")] = values[stringPreferencesKey(DataStoreKey.webdavUsername)].orEmpty()
                    if (migratedPassword.isNotEmpty()) values[textKey(previousOwner, "password")] = migratedPassword
                    values[textKey(previousOwner, "path")] = values[stringPreferencesKey(DataStoreKey.webdavRemotePath)] ?: WebDavConfig().remotePath
                    values[flagKey(previousOwner, "auto")] = values[booleanPreferencesKey(DataStoreKey.webdavAutoSync)] ?: false
                    values[flagKey(previousOwner, "legacy")] = true
                    values[timeKey(previousOwner)] = values[longPreferencesKey(DataStoreKey.webdavLastSyncTime)] ?: 0L
                }
                listOf(DataStoreKey.webdavServerUrl, DataStoreKey.webdavUsername, DataStoreKey.webdavPassword, DataStoreKey.webdavRemotePath)
                    .forEach { values.remove(stringPreferencesKey(it)) }
                values.remove(booleanPreferencesKey(DataStoreKey.webdavAutoSync))
                values.remove(longPreferencesKey(DataStoreKey.webdavLastSyncTime))
                values[migration] = true
            }
        }
        return WebDavConfig(
            serverUrl = preferences[textKey(accountId, "url")].orEmpty(),
            username = preferences[textKey(accountId, "username")].orEmpty(),
            password = preferences[textKey(accountId, "password")]?.let(credentials::decrypt).orEmpty(),
            remotePath = preferences[textKey(accountId, "path")] ?: WebDavConfig().remotePath,
            autoSync = preferences[flagKey(accountId, "auto")] ?: false,
            namespaceId = requireNotNull(preferences[textKey(accountId, "namespace")]),
            importLegacy = preferences[flagKey(accountId, "legacy")] ?: false,
            includeArticles = preferences[flagKey(accountId, "articles")] ?: false,
            includeFullContent = preferences[flagKey(accountId, "fullContent")] ?: false,
        )
    }

    suspend fun save(accountId: Int, config: WebDavConfig) {
        require(accountId > 0)
        require(config.namespaceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid remote account ID" }
        require(config.remotePath.trim('/').split('/').none { it == "." || it == ".." }) { "Invalid remote directory" }
        if (config.configured) config.directoryUrl()
        val password = credentials.encrypt(config.password)
        context.dataStore.edit {
            it[textKey(accountId, "url")] = config.serverUrl.trim().trimEnd('/')
            it[textKey(accountId, "username")] = config.username.trim()
            it[textKey(accountId, "password")] = password
            it[textKey(accountId, "path")] = config.remotePath.trim().trim('/')
            it[textKey(accountId, "namespace")] = config.namespaceId
            it[flagKey(accountId, "auto")] = config.autoSync
            it[flagKey(accountId, "legacy")] = config.importLegacy
            it[flagKey(accountId, "articles")] = config.includeArticles
            it[flagKey(accountId, "fullContent")] = config.includeFullContent
        }
    }

    suspend fun reconnectPassword(accountId: Int, password: String) {
        require(accountId > 0)
        val encrypted = credentials.encrypt(password)
        context.dataStore.edit { values ->
            val owner = values[intPreferencesKey("legacy.settings.owner")] ?: values[intPreferencesKey(DataStoreKey.currentAccountId)]
            if (values[booleanPreferencesKey("webdav.accounts.migrated")] != true && owner == accountId) {
                values[stringPreferencesKey(DataStoreKey.webdavPassword)] = encrypted
            } else values[textKey(accountId, "password")] = encrypted
        }
    }

    suspend fun lastSyncTime(accountId: Int): Long = context.dataStore.data.first()[timeKey(accountId)] ?: 0L

    suspend fun setLastSyncTime(accountId: Int, time: Long) {
        context.dataStore.edit { it[timeKey(accountId)] = time }
    }

    private fun textKey(id: Int, name: String) = stringPreferencesKey("webdav.$id.$name")
    private fun flagKey(id: Int, name: String) = booleanPreferencesKey("webdav.$id.$name")
    private fun timeKey(id: Int) = longPreferencesKey("webdav.$id.lastSync")
}
