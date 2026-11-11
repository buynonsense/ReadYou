package me.ash.reader.domain.service

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.domain.model.account.security.DESUtils
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.net.WebDavClient
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.spacerDollar

/**
 * WebDAV based backup / sync for the current account's subscriptions and
 * article read states.
 *
 * The sync payload is a single JSON snapshot stored on the WebDAV server.
 * "Sync" downloads the remote snapshot, merges it into the local database
 * (missing groups/feeds/articles are added, read states of existing
 * articles follow the remote snapshot), then uploads the merged snapshot.
 * The most recent upload therefore wins for conflicting states.
 */
class WebDavSyncService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val accountService: AccountService,
    private val groupDao: GroupDao,
    private val feedDao: FeedDao,
    private val articleDao: ArticleDao,
    private val webDavClient: WebDavClient,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    data class WebDavConfig(
        val serverUrl: String = "",
        val username: String = "",
        val password: String = "",
        val remotePath: String = DEFAULT_REMOTE_PATH,
        val autoSync: Boolean = false,
    ) {
        val configured: Boolean get() = serverUrl.isNotBlank()
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
    private val syncMutex = Mutex()

    // region Config

    suspend fun loadConfig(): WebDavConfig = withContext(ioDispatcher) {
        val preferences = context.dataStore.data.first()
        WebDavConfig(
            serverUrl = preferences[stringKey] ?: "",
            username = preferences[usernameKey] ?: "",
            password = preferences[passwordKey]?.takeIf { it.isNotEmpty() }
                ?.let { runCatching { DESUtils.decrypt(it) }.getOrNull() } ?: "",
            remotePath = preferences[remotePathKey]?.takeIf { it.isNotBlank() }
                ?: DEFAULT_REMOTE_PATH,
            autoSync = preferences[autoSyncKey] ?: false,
        )
    }

    suspend fun saveConfig(config: WebDavConfig) = withContext(ioDispatcher) {
        context.dataStore.edit { preferences ->
            preferences[stringKey] = config.serverUrl.trim()
            preferences[usernameKey] = config.username.trim()
            preferences[passwordKey] = runCatching {
                DESUtils.encrypt(config.password)
            }.getOrDefault("")
            preferences[remotePathKey] = config.remotePath.trim().trim('/')
            preferences[autoSyncKey] = config.autoSync
        }
    }

    suspend fun lastSyncTime(): Long = withContext(ioDispatcher) {
        context.dataStore.data.first()[lastSyncTimeKey] ?: 0L
    }

    private suspend fun setLastSyncTime(time: Long): Unit = withContext(ioDispatcher) {
        context.dataStore.edit { it[lastSyncTimeKey] = time }
    }

    // endregion

    // region URLs

    fun dirUrl(config: WebDavConfig): String {
        val base = config.serverUrl.trim().trimEnd('/')
        val path = config.remotePath.trim('/')
        return if (path.isBlank()) base else "$base/$path"
    }

    fun backupFileUrl(config: WebDavConfig): String = "${dirUrl(config)}/$FILE_NAME"

    // endregion

    /**
     * Checks whether the configured server is reachable. A 404 on the remote
     * directory is treated as success: the server is reachable and the
     * directory simply does not exist yet (it is created on first upload).
     */
    suspend fun testConnection(config: WebDavConfig): Result<Unit> = withContext(ioDispatcher) {
        runCatching {
            require(config.configured) { context.getString(R.string.webdav_server_required_toast) }
            val url = dirUrl(config)
            webDavClient.propfind(url, config.username, config.password).use { response ->
                when {
                    response.isSuccessful || response.code == 207 -> Unit
                    response.code == 404 -> {
                        // Try to create the directory so the first upload can proceed.
                        webDavClient.mkcol(url, config.username, config.password).close()
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
    suspend fun syncNow(accountId: Int): Result<WebDavSyncResult> = withContext(ioDispatcher) {
        syncMutex.withLock {
            runCatching {
                val config = loadConfig()
                require(config.configured) {
                    context.getString(R.string.webdav_server_required_toast)
                }
                val remote = downloadSnapshot(config)
                val result = if (remote != null) {
                    mergeRemote(accountId, remote)
                } else {
                    WebDavSyncResult()
                }
                uploadSnapshot(accountId, config)
                setLastSyncTime(System.currentTimeMillis())
                result
            }
        }
    }

    /** Serializes the local account and uploads it, overwriting the remote backup. */
    suspend fun backup(accountId: Int): Result<Unit> = withContext(ioDispatcher) {
        syncMutex.withLock {
            runCatching {
                val config = loadConfig()
                require(config.configured) {
                    context.getString(R.string.webdav_server_required_toast)
                }
                uploadSnapshot(accountId, config)
                setLastSyncTime(System.currentTimeMillis())
            }
        }
    }

    /** Downloads the remote snapshot and merges it into the local account. */
    suspend fun restore(accountId: Int): Result<WebDavSyncResult> = withContext(ioDispatcher) {
        syncMutex.withLock {
            runCatching {
                val config = loadConfig()
                require(config.configured) {
                    context.getString(R.string.webdav_server_required_toast)
                }
                val remote = downloadSnapshot(config)
                requireNotNull(remote) {
                    context.getString(R.string.webdav_no_backup)
                }
                mergeRemote(accountId, remote).also {
                    setLastSyncTime(System.currentTimeMillis())
                }
            }
        }
    }

    /**
     * Runs a full sync after each RSS sync when automatic sync is enabled.
     * Failures are logged and swallowed: WebDAV must never break RSS sync.
     */
    suspend fun autoSync(accountId: Int) {
        val config = loadConfig()
        if (!config.autoSync || !config.configured) return
        val result =
            runCatching { syncNow(accountId).getOrThrow() }.getOrElse {
                Log.e("RLog", "WebDavSyncService: auto sync failed", it)
                return
            }
        Log.i(
            "RLog",
            "WebDavSyncService: auto sync done " +
                "(feeds +${result.feedsAdded}, articles +${result.articlesAdded})",
        )
    }

    // region Merge

    private suspend fun mergeRemote(
        accountId: Int,
        remote: WebDavSyncData,
    ): WebDavSyncResult {
        var groupsAdded = 0
        var feedsAdded = 0
        var articlesAdded = 0
        var articlesUpdated = 0

        val localGroups = groupDao.queryAll(accountId)
        val groupIdMap = mutableMapOf<String, String>()
        for (dto in remote.groups) {
            val name = dto.name?.trim().takeUnless { it.isNullOrBlank() } ?: continue
            val existing = localGroups.firstOrNull { it.name == name }
            if (existing != null) {
                dto.id?.let { groupIdMap[it] = existing.id }
            } else {
                val newId = accountId spacerDollar UUID.randomUUID().toString()
                groupDao.insert(Group(id = newId, name = name, accountId = accountId))
                dto.id?.let { groupIdMap[it] = newId }
                groupsAdded++
            }
        }

        val localFeeds = feedDao.queryAll(accountId)
        val feedUrlToLocal = localFeeds.associateBy({ it.url }, { it.id }).toMutableMap()
        val feedIdMap = mutableMapOf<String, String>()
        val defaultGroupId = accountId.getDefaultGroupId()
        for (dto in remote.feeds) {
            val url = dto.url?.trim().takeUnless { it.isNullOrBlank() } ?: continue
            val localId = feedUrlToLocal[url]
            if (localId != null) {
                dto.id?.let { feedIdMap[it] = localId }
            } else {
                val groupId = dto.groupId?.let { groupIdMap[it] }
                    ?: defaultGroupId.takeIf { groupIdExists(accountId, it) }
                    ?: localGroups.firstOrNull()?.id
                    ?: defaultGroupId
                val feed =
                    Feed(
                        id = accountId spacerDollar UUID.randomUUID().toString(),
                        name = dto.title?.trim().takeUnless { it.isNullOrBlank() } ?: url,
                        icon = dto.icon,
                        url = url,
                        groupId = groupId,
                        accountId = accountId,
                        isNotification = dto.isNotification,
                        isFullContent = dto.isFullContent,
                        isBrowser = dto.isBrowser,
                    )
                feedDao.insert(feed)
                feedUrlToLocal[url] = feed.id
                dto.id?.let { feedIdMap[it] = feed.id }
                feedsAdded++
            }
        }

        for ((feedUrl, dtos) in remote.articles.groupBy { it.feedUrl?.trim() }) {
            val localFeedId = feedUrlToLocal[feedUrl] ?: continue
            val localMeta = articleDao
                .queryArticleSyncMetasByFeedId(localFeedId)
                .associateBy { it.link }
            val toInsert = mutableListOf<Article>()
            val toMarkRead = mutableListOf<String>()
            val toMarkUnread = mutableListOf<String>()
            val toStar = mutableListOf<String>()
            val toUnstar = mutableListOf<String>()
            val toReadLater = mutableListOf<String>()
            val toUnReadLater = mutableListOf<String>()
            for (dto in dtos) {
                val link = dto.link?.trim().takeUnless { it.isNullOrBlank() } ?: continue
                val existing = localMeta[link]
                if (existing == null) {
                    toInsert +=
                        Article(
                            id = accountId spacerDollar UUID.randomUUID().toString(),
                            date = Date(dto.date ?: System.currentTimeMillis()),
                            title = dto.title.orEmpty(),
                            author = dto.author,
                            rawDescription = dto.rawDescription.orEmpty(),
                            shortDescription = dto.shortDescription.orEmpty(),
                            img = dto.img,
                            link = link,
                            feedId = localFeedId,
                            accountId = accountId,
                            isUnread = dto.isUnread,
                            isStarred = dto.isStarred,
                            isReadLater = dto.isReadLater,
                        )
                } else {
                    if (dto.isUnread != existing.isUnread) {
                        if (dto.isUnread) toMarkUnread += existing.id else toMarkRead += existing.id
                    }
                    if (dto.isStarred != existing.isStarred) {
                        if (dto.isStarred) toStar += existing.id else toUnstar += existing.id
                    }
                    if (dto.isReadLater != existing.isReadLater) {
                        if (dto.isReadLater) toReadLater += existing.id
                        else toUnReadLater += existing.id
                    }
                    articlesUpdated++
                }
            }
            toInsert.chunked(INSERT_CHUNK_SIZE).forEach { articleDao.insertList(it) }
            articlesAdded += toInsert.size
            articleDao.markAsReadByIdSet(accountId, toMarkRead.toSet(), false)
            articleDao.markAsReadByIdSet(accountId, toMarkUnread.toSet(), true)
            articleDao.markAsStarredByIdSet(accountId, toStar.toSet(), true)
            articleDao.markAsStarredByIdSet(accountId, toUnstar.toSet(), false)
            articleDao.markAsReadLaterByIdSet(accountId, toReadLater.toSet(), true)
            articleDao.markAsReadLaterByIdSet(accountId, toUnReadLater.toSet(), false)
        }

        return WebDavSyncResult(
            groupsAdded = groupsAdded,
            feedsAdded = feedsAdded,
            articlesAdded = articlesAdded,
            articlesUpdated = articlesUpdated,
        )
    }

    private suspend fun groupIdExists(accountId: Int, groupId: String): Boolean =
        groupDao.queryById(groupId)?.let { it.accountId == accountId } == true

    // endregion

    // region Snapshot

    private suspend fun downloadSnapshot(config: WebDavConfig): WebDavSyncData? =
        withContext(ioDispatcher) {
            val response = webDavClient.get(backupFileUrl(config), config.username, config.password)
            response.use {
                when {
                    it.code == 404 -> null
                    !it.isSuccessful -> throw IllegalStateException("HTTP ${it.code}")
                    else -> {
                        val bytes = it.body?.bytes() ?: return@use null
                        runCatching { gson.fromJson(String(bytes), WebDavSyncData::class.java) }
                            .getOrNull()
                    }
                }
            }
        }

    private suspend fun uploadSnapshot(accountId: Int, config: WebDavConfig) =
        withContext(ioDispatcher) {
            val snapshot = buildSnapshot(accountId)
            val bytes = gson.toJson(snapshot).toByteArray(Charsets.UTF_8)
            webDavClient
                .put(backupFileUrl(config), config.username, config.password, bytes)
                .use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                }
        }

    private suspend fun buildSnapshot(accountId: Int): WebDavSyncData {
        val account = accountService.getAccountById(accountId)
        val groups = groupDao.queryAll(accountId)
        val feeds = feedDao.queryAll(accountId)
        val feedUrlById = feeds.associate { it.id to it.url }
        val articles = articleDao.queryArticleSnapshotsByAccountId(accountId)
        return WebDavSyncData(
            version = 1,
            exportedAt = System.currentTimeMillis(),
            accountName = account?.name,
            groups =
                groups.map {
                    GroupDto(id = it.id, name = it.name)
                },
            feeds =
                feeds.map {
                    FeedDto(
                        id = it.id,
                        groupId = it.groupId,
                        url = it.url,
                        title = it.name,
                        icon = it.icon,
                        isNotification = it.isNotification,
                        isFullContent = it.isFullContent,
                        isBrowser = it.isBrowser,
                    )
                },
            articles =
                articles.map {
                    ArticleDto(
                        feedUrl = feedUrlById[it.feedId],
                        link = it.link,
                        title = it.title,
                        author = it.author,
                        date = it.date.time,
                        isUnread = it.isUnread,
                        isStarred = it.isStarred,
                        isReadLater = it.isReadLater,
                        shortDescription = it.shortDescription,
                        rawDescription = it.rawDescription,
                        img = it.img,
                    )
                },
        )
    }

    // endregion

    companion object {
        const val DEFAULT_REMOTE_PATH = "ReadYou"
        private const val FILE_NAME = "readyou-sync.json"
        private const val INSERT_CHUNK_SIZE = 500

        private val stringKey =
            DataStoreKey.keys[DataStoreKey.webdavServerUrl]?.key as Preferences.Key<String>
        private val usernameKey =
            DataStoreKey.keys[DataStoreKey.webdavUsername]?.key as Preferences.Key<String>
        private val passwordKey =
            DataStoreKey.keys[DataStoreKey.webdavPassword]?.key as Preferences.Key<String>
        private val remotePathKey =
            DataStoreKey.keys[DataStoreKey.webdavRemotePath]?.key as Preferences.Key<String>
        private val autoSyncKey =
            DataStoreKey.keys[DataStoreKey.webdavAutoSync]?.key as Preferences.Key<Boolean>
        private val lastSyncTimeKey =
            DataStoreKey.keys[DataStoreKey.webdavLastSyncTime]?.key as Preferences.Key<Long>
    }
}
