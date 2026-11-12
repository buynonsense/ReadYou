package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.datastore.preferences.core.*
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.*
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore

data class ArticleCleanupSettings(
    val enabled: Boolean = false,
    val retention: ArticleCleanupRetentionPreference = ArticleCleanupRetentionPreference.default,
    val includeUnread: Boolean = false,
    val keepStarred: Boolean = true,
)

@Singleton
class ArticleCleanupSettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private suspend fun migrate() {
        context.dataStore.edit { values ->
            val migrated = booleanPreferencesKey("cleanup.accounts.migrated")
            val owner = values[intPreferencesKey("legacy.settings.owner")] ?: values[intPreferencesKey(DataStoreKey.currentAccountId)] ?: return@edit
            if (values[migrated] == true) return@edit
            values[flag(owner, "enabled")] = values[booleanPreferencesKey(DataStoreKey.autoCleanupArticles)] ?: false
            values[retentionKey(owner)] = values[longPreferencesKey(DataStoreKey.autoCleanupRetention)] ?: ArticleCleanupRetentionPreference.default.value
            values[flag(owner, "unread")] = values[booleanPreferencesKey(DataStoreKey.autoCleanupIncludeUnread)] ?: false
            values[flag(owner, "starred")] = values[booleanPreferencesKey(DataStoreKey.autoCleanupKeepStarred)] ?: true
            values[migrated] = true
        }
    }

    fun observe(accountId: Int): Flow<ArticleCleanupSettings> = flow {
        require(accountId > 0)
        migrate()
        emitAll(context.dataStore.data.map { decode(accountId, it) }.distinctUntilChanged())
    }

    suspend fun load(accountId: Int): ArticleCleanupSettings = observe(accountId).first()

    suspend fun update(accountId: Int, transform: (ArticleCleanupSettings) -> ArticleCleanupSettings) {
        require(accountId > 0)
        migrate()
        context.dataStore.edit { values ->
            val settings = transform(decode(accountId, values))
            values[flag(accountId, "enabled")] = settings.enabled
            values[retentionKey(accountId)] = settings.retention.value
            values[flag(accountId, "unread")] = settings.includeUnread
            values[flag(accountId, "starred")] = settings.keepStarred
        }
    }

    private fun decode(id: Int, values: Preferences) = ArticleCleanupSettings(
        enabled = values[flag(id, "enabled")] ?: false,
        retention = ArticleCleanupRetentionPreference.values.find { it.value == values[retentionKey(id)] } ?: ArticleCleanupRetentionPreference.default,
        includeUnread = values[flag(id, "unread")] ?: false,
        keepStarred = values[flag(id, "starred")] ?: true,
    )

    private fun flag(id: Int, name: String) = booleanPreferencesKey("cleanup.$id.$name")
    private fun retentionKey(id: Int) = longPreferencesKey("cleanup.$id.retention")
}
