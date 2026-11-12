package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.net.WebDavClient
import me.ash.reader.infrastructure.preference.WebDavConfigStore
import me.ash.reader.infrastructure.coroutine.runCatchingCancellable
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.service.backup.WebDavBackupEngine
import me.ash.reader.domain.service.backup.ConflictResolution
import me.ash.reader.domain.service.backup.RestoreMode
import okhttp3.HttpUrl.Companion.toHttpUrl

/**
 * Lightweight account sync and separate optional content backups with verified chunks,
 * conditional publication, conflict resolution and recovery copies. V1/V2 backups remain readable.
 */
@Singleton
class WebDavSyncService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val accountService: AccountService,
    private val webDavClient: WebDavClient,
    private val configStore: WebDavConfigStore,
    private val accountLock: AccountOperationLock,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val engine: WebDavBackupEngine,
    private val diffMapHolder: DiffMapHolder,
    private val statusStore: me.ash.reader.infrastructure.preference.AccountSyncStatusStore,
) {
    val progress get() = engine.progress
    fun history(accountId: Int) = engine.history(accountId)
    fun recoveryCopies(accountId: Int) = engine.recoveryCopies(accountId)
    suspend fun versions(accountId: Int) = withContext(ioDispatcher) { engine.versions(accountId, loadConfig(accountId)) }
    suspend fun supportsRestore(accountId: Int) = accountService.getAccountById(accountId)?.type == AccountType.Local

    private suspend fun requireLocalAccount(accountId: Int) {
        check(supportsRestore(accountId)) { context.getString(R.string.webdav_remote_account_limit) }
    }

    data class WebDavConfig(
        val serverUrl: String = "",
        val username: String = "",
        val password: String = "",
        val remotePath: String = DEFAULT_REMOTE_PATH,
        val autoSync: Boolean = false,
        val namespaceId: String = DEFAULT_NAMESPACE_ID,
        val importLegacy: Boolean = false,
        val includeArticles: Boolean = false,
        val includeFullContent: Boolean = false,
    ) {
        val configured: Boolean get() = serverUrl.isNotBlank()

        companion object {
            const val DEFAULT_NAMESPACE_ID = "default"
        }

        fun directoryUrl(includeNamespace: Boolean = true): String {
            require(namespaceId.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Invalid remote account ID" }
            val url = serverUrl.trim().toHttpUrl()
            require(url.username.isEmpty() && url.password.isEmpty() && url.query == null && url.fragment == null) { "Use a server URL without embedded credentials, a query or fragment" }
            val segments = remotePath.trim().trim('/').split('/').filter { it.isNotEmpty() }
            require(segments.none { it == "." || it == ".." || '\\' in it }) { "Invalid remote directory" }
            return url.newBuilder().apply {
                segments.forEach(::addPathSegment)
                if (includeNamespace) { addPathSegment("accounts"); addPathSegment(namespaceId) }
            }.build().toString().trimEnd('/')
        }
    }

    data class WebDavSyncResult(
        val groupsAdded: Int = 0,
        val feedsAdded: Int = 0,
        val articlesAdded: Int = 0,
        val articlesUpdated: Int = 0,
    )

    // region DTO

    data class WebDavSyncData(
        val version: Int = 1,
        val exportedAt: Long = 0L,
        val accountName: String? = null,
        val groups: List<GroupDto> = emptyList(),
        val feeds: List<FeedDto> = emptyList(),
        val articles: List<ArticleDto> = emptyList(),
    )

    data class GroupDto(
        val id: String? = null,
        val name: String? = null,
    )

    data class FeedDto(
        val id: String? = null,
        val groupId: String? = null,
        val url: String? = null,
        val title: String? = null,
        val icon: String? = null,
        val isNotification: Boolean = false,
        val isFullContent: Boolean = false,
        val isBrowser: Boolean = false,
    )

    data class ArticleDto(
        val feedUrl: String? = null,
        val link: String? = null,
        val title: String? = null,
        val author: String? = null,
        val date: Long? = null,
        val isUnread: Boolean = true,
        val isStarred: Boolean = false,
        val isReadLater: Boolean = false,
        val shortDescription: String? = null,
        val rawDescription: String? = null,
        val img: String? = null,
    )

    // endregion

    private val gson = Gson()

    // region Config

    suspend fun loadConfig(accountId: Int): WebDavConfig = withContext(ioDispatcher) { configStore.load(accountId) }

    suspend fun saveConfig(accountId: Int, config: WebDavConfig) = withContext(ioDispatcher) { configStore.save(accountId, config) }
    suspend fun reconnectPassword(accountId: Int, password: String) = withContext(ioDispatcher) { configStore.reconnectPassword(accountId, password) }

    suspend fun lastSyncTime(accountId: Int): Long = withContext(ioDispatcher) { configStore.lastSyncTime(accountId) }

    private suspend fun setLastSyncTime(accountId: Int, time: Long) = configStore.setLastSyncTime(accountId, time)

    // endregion

    // region URLs

    fun dirUrl(config: WebDavConfig): String = config.directoryUrl()

    fun backupFileUrl(config: WebDavConfig): String = "${dirUrl(config)}/$FILE_NAME"

    // endregion

    /**
     * Checks whether the configured server is reachable. A 404 on the remote
     * directory is treated as success: the server is reachable and the
     * directory simply does not exist yet (it is created on first upload).
     */
    suspend fun testConnection(config: WebDavConfig): Result<Unit> = withContext(ioDispatcher) {
        runCatchingCancellable {
            require(config.configured) { context.getString(R.string.webdav_server_required_toast) }
            val url = dirUrl(config)
            webDavClient.propfind(url, config.username, config.password).use { response ->
                when {
                    response.isSuccessful || response.code == 207 -> Unit
                    response.code == 404 -> {
                        // Try to create the directory so the first upload can proceed.
                        webDavClient.mkcol(url, config.username, config.password).use {
                            check(it.isSuccessful || it.code == 405 || it.code == 409) { "HTTP ${it.code}" }
                        }
                    }
                    response.code == 401 ->
                        throw IllegalStateException(
                            context.getString(R.string.webdav_unauthorized)
                        )
                    else ->
                        throw IllegalStateException("HTTP ${response.code}")
                }
            }
        }
    }

    /**
     * Downloads the remote snapshot (if any), merges it into the local
     * account, then uploads the merged snapshot.
     */
    suspend fun syncNow(accountId: Int, resolution: ConflictResolution = ConflictResolution.ASK): Result<WebDavSyncResult> = withContext(ioDispatcher) {
        accountLock.withAccount(accountId) {
            runCatchingCancellable {
                requireLocalAccount(accountId)
                val config = loadConfig(accountId)
                require(config.configured) {
                    context.getString(R.string.webdav_server_required_toast)
                }
                diffMapHolder.awaitPersistence()
                val result = engine.sync(accountId, config, resolution) { downloadSnapshot(config) }
                setLastSyncTime(accountId, System.currentTimeMillis())
                result
            }
        }
    }

    /** Publishes a new verified generation while retaining previous generations. */
    suspend fun backup(accountId: Int): Result<Unit> = withContext(ioDispatcher) {
        accountLock.withAccount(accountId) {
            runCatchingCancellable {
                val config = loadConfig(accountId)
                require(config.configured) {
                    context.getString(R.string.webdav_server_required_toast)
                }
                diffMapHolder.awaitPersistence()
                engine.backup(accountId, config) { downloadSnapshot(config) }
                setLastSyncTime(accountId, System.currentTimeMillis())
            }
        }
    }

    suspend fun restoreRecovery(accountId: Int, recoveryId: String): Result<WebDavSyncResult> = withContext(ioDispatcher) {
        accountLock.withAccount(accountId) {
            runCatchingCancellable {
                requireLocalAccount(accountId)
                diffMapHolder.awaitPersistence()
                engine.restoreRecovery(accountId, loadConfig(accountId), recoveryId)
            }
        }
    }

    /** Downloads the remote snapshot and merges it into the local account. */
    suspend fun restore(accountId: Int, mode: RestoreMode = RestoreMode.MERGE, resolution: ConflictResolution = ConflictResolution.ASK, generation: String? = null): Result<WebDavSyncResult> = withContext(ioDispatcher) {
        accountLock.withAccount(accountId) {
            runCatchingCancellable {
                requireLocalAccount(accountId)
                val config = loadConfig(accountId)
                require(config.configured) {
                    context.getString(R.string.webdav_server_required_toast)
                }
                diffMapHolder.awaitPersistence()
                engine.restore(accountId, config, mode, resolution, generation) { downloadSnapshot(config) }.also {
                    setLastSyncTime(accountId, System.currentTimeMillis())
                }
            }
        }
    }

    /**
     * Syncs subscriptions, app preferences and reading states after RSS sync when enabled.
     * Failures are logged and swallowed: WebDAV must never break RSS sync.
     */
    suspend fun autoSync(accountId: Int) {
        if (!supportsRestore(accountId)) return
        val result =
            runCatchingCancellable {
                val config = loadConfig(accountId)
                if (!config.autoSync || !config.configured) return
                syncNow(accountId).getOrThrow()
            }.getOrElse {
                statusStore.webDavError(accountId, it.message ?: "WebDAV sync failed")
                Log.e("RLog", "WebDavSyncService: auto sync failed", it)
                return
            }
        statusStore.webDavError(accountId, null)
        Log.i(
            "RLog",
            "WebDavSyncService: auto sync done " +
                "(feeds +${result.feedsAdded}, articles +${result.articlesAdded})",
        )
    }

    // region Snapshot

    private suspend fun downloadSnapshot(config: WebDavConfig): WebDavSyncData? =
        withContext(ioDispatcher) {
            val response = webDavClient.get(backupFileUrl(config), config.username, config.password)
            response.use {
                when {
                    it.code == 404 -> if (config.importLegacy) downloadLegacySnapshot(config) else null
                    !it.isSuccessful -> throw IllegalStateException("HTTP ${it.code}")
                    else -> {
                        val body = checkNotNull(it.body) { "Empty WebDAV backup" }
                        body.charStream().use(::decodeSnapshot)
                    }
                }
            }
        }

    internal fun decodeSnapshot(reader: java.io.Reader): WebDavSyncData {
        val data = try {
            val json = com.google.gson.JsonParser.parseReader(reader)
            check(json.isJsonObject) { "Invalid WebDAV backup structure" }
            val root = json.asJsonObject
            check(root.has("version") && root.get("version").isJsonPrimitive) { "Missing WebDAV backup version" }
            require(root.get("version").asInt == 1) { "Unsupported WebDAV backup version: ${root.get("version")}" }
            check(listOf("groups", "feeds", "articles").all { root.has(it) && root[it].isJsonArray }) { "Invalid WebDAV backup structure" }
            gson.fromJson(root, WebDavSyncData::class.java)
        } catch (e: com.google.gson.JsonParseException) {
            throw IllegalStateException("WebDAV backup is damaged; the remote file was preserved", e)
        }
        checkNotNull(data) { "WebDAV backup is empty; the remote file was preserved" }
        require(data.version == 1) { "Unsupported WebDAV backup version: ${data.version}" }
        @Suppress("SENSELESS_COMPARISON")
        check(data.groups != null && data.feeds != null && data.articles != null) { "Invalid WebDAV backup structure" }
        return data
    }

    private suspend fun downloadLegacySnapshot(config: WebDavConfig): WebDavSyncData? {
        val url = config.directoryUrl(includeNamespace = false) + "/$FILE_NAME"
        return webDavClient.get(url, config.username, config.password).use { response ->
            if (response.code == 404) null else {
                check(response.isSuccessful) { "HTTP ${response.code}" }
                checkNotNull(response.body).charStream().use(::decodeSnapshot)
            }
        }
    }

    // endregion

    companion object {
        const val DEFAULT_REMOTE_PATH = "ReadYou"
        private const val FILE_NAME = "readyou-sync.json"
    }
}
