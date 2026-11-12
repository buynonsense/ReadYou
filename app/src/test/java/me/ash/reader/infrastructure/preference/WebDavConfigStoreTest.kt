package me.ash.reader.infrastructure.preference

import android.app.Application
import androidx.datastore.preferences.core.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.fromDataStoreToJSONString
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.*
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WebDavConfigStoreTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val credentials = mock<CredentialStore>()
    private val store get() = WebDavConfigStore(context, credentials)

    @Before fun prepare() = runBlocking {
        context.dataStore.edit { it.clear() }
        whenever(credentials.encrypt(any())).thenAnswer { "encrypted:" + it.getArgument<String>(0) }
        whenever(credentials.decrypt(any())).thenAnswer { it.getArgument<String>(0).removePrefix("encrypted:") }
        whenever(credentials.migrate(any())).thenReturn("encrypted:old password")
        Unit
    }

    @Test fun newConfigurationsUseTheSameDirectoryWithoutAPairingId() = runBlocking {
        val first = store.load(1)
        store.save(1, first.copy(serverUrl = "https://example.org/dav", username = "one", password = "test", autoSync = true))
        val second = store.load(2)
        assertFalse(second.configured)
        assertFalse(second.autoSync)
        assertEquals(first.namespaceId, second.namespaceId)
        assertEquals(
            first.copy(serverUrl = "https://example.org/dav").directoryUrl(),
            second.copy(serverUrl = "https://example.org/dav").directoryUrl(),
        )
        assertEquals("test", store.load(1).password)
        assertEquals(first.namespaceId, store.load(1).namespaceId)
        assertFalse(context.fromDataStoreToJSONString().contains("encrypted:test"))
    }

    @Test fun upgradePreservesThePreviousRemoteDirectory() = runBlocking {
        context.dataStore.edit {
            it[stringPreferencesKey("webdav.1.namespace")] = "previous-device-id"
            it[stringPreferencesKey("webdav.1.url")] = "https://example.org/dav"
        }
        val config = store.load(1)
        assertEquals("previous-device-id", config.namespaceId)
        store.save(1, config.copy(autoSync = true))
        assertEquals(config.directoryUrl(), store.load(1).directoryUrl())
    }

    @Test fun legacyConfigIsClaimedByItsPreviousOwnerEvenWhenAnotherAccountLoadsFirst() = runBlocking {
        context.dataStore.edit {
            it[intPreferencesKey(DataStoreKey.currentAccountId)] = 1
            it[stringPreferencesKey(DataStoreKey.webdavServerUrl)] = "https://example.org/dav"
            it[stringPreferencesKey(DataStoreKey.webdavPassword)] = "legacy encrypted value"
            it[booleanPreferencesKey(DataStoreKey.webdavAutoSync)] = true
        }
        assertFalse(store.load(2).configured)
        assertTrue(store.load(1).autoSync)
        assertTrue(store.load(1).importLegacy)
        assertEquals("old password", store.load(1).password)
        assertNull(context.dataStore.data.first()[stringPreferencesKey(DataStoreKey.webdavPassword)])
    }

    @Test fun failedCredentialMigrationPreservesTheOriginalConfiguration() = runBlocking {
        context.dataStore.edit {
            it[intPreferencesKey(DataStoreKey.currentAccountId)] = 1
            it[stringPreferencesKey(DataStoreKey.webdavServerUrl)] = "https://example.org/dav"
            it[stringPreferencesKey(DataStoreKey.webdavPassword)] = "legacy encrypted value"
        }
        whenever(credentials.migrate(any())).thenThrow(IllegalStateException("unavailable key"))
        assertTrue(runCatching { store.load(1) }.isFailure)
        assertEquals("legacy encrypted value", context.dataStore.data.first()[stringPreferencesKey(DataStoreKey.webdavPassword)])
    }

    @Test fun articleBackupDefaultsOffEvenWhenOldFullTextBackupWasEnabled() = runBlocking {
        context.dataStore.edit { it[booleanPreferencesKey("webdav.1.fullContent")] = true }
        val original = store.load(1)
        assertFalse(original.includeArticles)
        assertTrue(original.includeFullContent)
        store.save(1, original.copy(includeArticles = true))
        assertTrue(store.load(1).includeArticles)
        assertFalse(store.load(2).includeArticles)
        store.save(1, original.copy(includeArticles = false))
        assertFalse(store.load(1).includeArticles)
    }
}
