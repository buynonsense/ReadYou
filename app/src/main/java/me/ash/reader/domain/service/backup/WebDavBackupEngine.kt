package me.ash.reader.domain.service.backup

import android.content.Context
import android.util.AtomicFile
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.Date
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.ash.reader.R
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.WebDavArticleState
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.WebDavSyncService.WebDavConfig
import me.ash.reader.domain.service.WebDavSyncService.WebDavSyncData
import me.ash.reader.domain.service.WebDavSyncService.WebDavSyncResult
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.net.WebDavClient
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.spacerDollar

@Singleton
class WebDavBackupEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    private val database: AndroidDatabase,
    private val client: WebDavClient,
    private val cache: ReaderCacheHelper,
) {
    private val storage = context.noBackupFilesDir.resolve("webdav-v2").apply { mkdirs() }
    private val operationLock = Mutex() // App preferences are shared by all accounts.
    private val _progress = MutableStateFlow<Map<Int, BackupProgress>>(emptyMap())
    val progress = _progress.asStateFlow()
    private fun stage(id: Int, stage: String, count: Int = 0, total: Int = 0) { _progress.update { it + (id to BackupProgress(stage, count, total)) } }

    private fun stateDir(id: Int, config: WebDavConfig) = storage.resolve("$id/${digest("${config.serverUrl}/${config.remotePath}/${config.namespaceId}")}").apply { mkdirs() }
    private fun metadata(id: Int, config: WebDavConfig, scope: BackupScope = BackupScope.BACKUP): BackupMetadata {
        val file = AtomicFile(stateDir(id, config).resolve("metadata-${scope.id}-v3.json"))
        if (!file.baseFile.exists()) return BackupMetadata()
        return file.openRead().reader().use { backupGson.fromJson(it, BackupMetadata::class.java) }
            ?: throw IllegalStateException("Local WebDAV sync metadata is damaged")
    }
    private fun saveMetadata(id: Int, config: WebDavConfig, stamps: List<RecordStamp>, scope: BackupScope = BackupScope.BACKUP) = atomicJson(stateDir(id, config).resolve("metadata-${scope.id}-v3.json"), BackupMetadata(stamps))
    private fun atomicJson(file: File, value: Any) {
        val atomic = AtomicFile(file)
        val output = atomic.startWrite()
        try { output.write(backupGson.toJson(value).toByteArray()); atomic.finishWrite(output) }
        catch (e: Throwable) { atomic.failWrite(output); throw e }
    }

    fun history(id: Int): List<BackupHistoryEntry> {
        val file = storage.resolve("$id/history.json")
        if (!file.exists()) return emptyList()
        return AtomicFile(file).openRead().reader().use { backupGson.fromJson(it, Array<BackupHistoryEntry>::class.java)?.toList() }.orEmpty()
    }
    private fun recordHistory(id: Int, entry: BackupHistoryEntry) {
        storage.resolve(id.toString()).mkdirs()
        atomicJson(storage.resolve("$id/history.json"), (listOf(entry) + history(id)).take(50))
    }

    suspend fun versions(id: Int, config: WebDavConfig): List<BackupVersion> = withWorkspace { workspace ->
        val manifest = BackupTransport(client, config).current(workspace.directory)?.manifest ?: return@withWorkspace emptyList()
        (listOf(BackupVersion(manifest.generation, manifest.exportedAt)) + manifest.history.map { BackupVersion(it.generation, it.exportedAt) })
    }

    fun recoveryCopies(id: Int): List<BackupRecoveryCopy> = storage.resolve("$id/recovery").listFiles().orEmpty()
        .filter { it.isDirectory && it.resolve("manifest.json").isFile }
        .map { directory ->
            val manifest = directory.resolve("manifest.json").reader().use { backupGson.fromJson(it, BackupManifest::class.java) }
            manifest.verify(manifest.namespaceId)
            BackupRecoveryCopy(directory.name, manifest.exportedAt)
        }.sortedByDescending { it.time }

    suspend fun restoreRecovery(id: Int, config: WebDavConfig, recoveryId: String): WebDavSyncResult = operation(id, "recovery") {
        require(recoveryId.matches(Regex("[0-9]{13}-[0-9a-f-]{36}"))) { "Invalid local recovery copy" }
        withWorkspace { workspace ->
            val directory = storage.resolve("$id/recovery/$recoveryId")
            val manifest = directory.resolve("manifest.json").reader().use { backupGson.fromJson(it, BackupManifest::class.java) }
            manifest.verify(manifest.namespaceId)
            val recoveryConfig = config.copy(namespaceId = manifest.namespaceId, serverUrl = config.serverUrl.ifBlank { "https://localhost" }, includeArticles = true, includeFullContent = true)
            BackupTransport(client, recoveryConfig).download(manifest, workspace, directory) { count, total -> stage(id, "download", count, total) }
            prepareRemote(workspace, manifest.version, includeArticles = true)
            val baseline = metadata(id, config).records.associateBy { it.key }
            scan(id, recoveryConfig, workspace, "local", baseline)
            workspace.copy("remote", "merged")
            normalizeParents(workspace, ConflictResolution.KEEP_REMOTE)
            preserveRecovery(id, config, baseline)
            stage(id, "apply")
            apply(id, recoveryConfig, workspace, baseline, replace = true).also { saveRestoredBindings(id, config, workspace, baseline) }
        }
    }

    suspend fun sync(id: Int, config: WebDavConfig, resolution: ConflictResolution, legacy: suspend () -> WebDavSyncData?): WebDavSyncResult =
        operation(id, "sync") {
            withWorkspace { workspace ->
                val syncConfig = config.copy(includeArticles = false, includeFullContent = false)
                val baseline = metadata(id, config, BackupScope.SYNC).records.associateBy { it.key }
                stage(id, "download")
                val transport = BackupTransport(client, config, BackupScope.SYNC)
                val remote = transport.current(workspace.directory)
                if (remote != null) transport.download(remote.manifest, workspace) { count, total -> stage(id, "download", count, total) }
                else legacy()?.let { importLegacy(it, workspace) }
                prepareRemote(workspace, remote?.manifest?.version ?: 2, includeArticles = false)
                stage(id, "scan")
                scan(id, syncConfig, workspace, "local", baseline)
                merge(workspace, baseline, resolution)
                normalizeParents(workspace, resolution)
                if (workspace.differs("local", "merged")) preserveRecovery(id, config, baseline)
                stage(id, "upload")
                val chunks = transport.writeChunks(workspace, "merged")
                val manifest = transport.publish(workspace, remote, chunks) { count, total -> stage(id, "upload", count, total) }
                // A concurrent local edit leaves the published snapshot valid, but is never overwritten.
                stage(id, "apply")
                val result = apply(id, syncConfig, workspace, baseline, replace = false)
                saveMetadata(id, config, stamps(workspace, "merged"), BackupScope.SYNC)
                generations[id] = manifest.generation
                result
            }
        }

    suspend fun backup(id: Int, config: WebDavConfig, legacy: suspend () -> WebDavSyncData?) {
        operation(id, "backup") {
            withWorkspace { workspace ->
                val transport = BackupTransport(client, config)
                val remote = transport.current(workspace.directory)
                if (remote != null) transport.download(remote.manifest, workspace) { count, total -> stage(id, "verify", count, total) }
                else legacy() // Validate old backups before replacing their logical namespace.
                val baseline = metadata(id, config).records.associateBy { it.key }
                stage(id, "scan")
                scan(id, config, workspace, "local", baseline)
                workspace.copy("local", "merged")
                val chunks = transport.writeChunks(workspace, "merged")
                val manifest = transport.publish(workspace, remote, chunks, includeArticles = config.includeArticles) { count, total -> stage(id, "upload", count, total) }
                saveMetadata(id, config, stamps(workspace, "local"))
                generations[id] = manifest.generation
            }
        }
    }

    suspend fun restore(id: Int, config: WebDavConfig, mode: RestoreMode, resolution: ConflictResolution, generation: String?, legacy: suspend () -> WebDavSyncData?): WebDavSyncResult =
        operation(id, if (mode == RestoreMode.REPLACE) "replace" else "restore") {
            withWorkspace { workspace ->
                val baseline = metadata(id, config).records.associateBy { it.key }
                val transport = BackupTransport(client, config)
                val current = transport.current(workspace.directory)
                val selected = if (generation != null && current != null && generation != current.manifest.generation) {
                    val ref = requireNotNull(current.manifest.history.firstOrNull { it.generation == generation }) { "Backup version is no longer in the current history" }
                    transport.version(ref, workspace.directory)
                } else current
                if (selected != null) transport.download(selected.manifest, workspace) { count, total -> stage(id, "download", count, total) }
                else importLegacy(requireNotNull(legacy()) { context.getString(R.string.webdav_no_backup) }, workspace)
                prepareRemote(workspace, selected?.manifest?.version ?: 2, includeArticles = true)
                val restoreConfig = config.copy(includeArticles = selected?.manifest?.includesArticles ?: true)
                scan(id, restoreConfig, workspace, "local", baseline)
                if (mode == RestoreMode.REPLACE) workspace.copy("remote", "merged") else merge(workspace, baseline, resolution)
                normalizeParents(workspace, resolution)
                preserveRecovery(id, config, baseline)
                stage(id, "apply")
                val result = apply(id, restoreConfig, workspace, baseline, mode == RestoreMode.REPLACE)
                saveRestoredBindings(id, config, workspace, baseline)
                selected?.manifest?.generation?.let { generations[id] = it }
                result
            }
        }

    private fun saveRestoredBindings(id: Int, config: WebDavConfig, workspace: BackupWorkspace, baseline: Map<String, RecordStamp>) {
        // Preserve the common hashes so restoring an older version remains a local edit.
        val bindings = stamps(workspace, "merged").associateBy { it.key }
        saveMetadata(id, config, baseline.values.map { stamp -> stamp.copy(localId = bindings[stamp.key]?.localId ?: stamp.localId) } +
            bindings.values.filter { it.key !in baseline }.map { it.copy(hash = "", modifiedAt = 0) })
    }

    private val generations = java.util.concurrent.ConcurrentHashMap<Int, String>()
    private suspend fun <T> operation(id: Int, action: String, block: suspend () -> T): T = operationLock.withLock {
        generations.remove(id)
        try {
            stage(id, "start")
            block().also { recordHistory(id, BackupHistoryEntry(System.currentTimeMillis(), action, true, null, generations[id])) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            recordHistory(id, BackupHistoryEntry(System.currentTimeMillis(), action, false, e.message?.take(1_024)))
            throw e
        } finally { _progress.update { it - id } }
    }
    private suspend fun <T> withWorkspace(block: suspend (BackupWorkspace) -> T): T {
        val directory = context.cacheDir.resolve("webdav-work/${UUID.randomUUID()}")
        return try { BackupWorkspace(directory).use { block(it) } } finally { directory.deleteRecursively() }
    }

    private suspend fun scan(id: Int, config: WebDavConfig, workspace: BackupWorkspace, table: String, baseline: Map<String, RecordStamp>) {
        val bindings = baseline.values.filter { it.localId != null }.associateBy { it.localId }
        val seen = mutableSetOf<String>()
        val now = System.currentTimeMillis()
        fun add(record: BackupRecord) {
            val common = baseline[record.key]
            val adjusted = if (common?.hash == record.hash) record.copy(modifiedAt = common.modifiedAt) else record
            workspace.put(table, adjusted)
            seen += record.key
        }
        database.withTransaction {
            val groupKeys = mutableMapOf<String, String>()
            database.groupDao().queryAll(id).forEach { group ->
                val isDefault = group.id == id.getDefaultGroupId()
                val groupKey = bindings[group.id]?.takeIf { it.kind == "group" }?.key ?: key("group", if (isDefault) "default" else group.name)
                check(groupKey !in seen) { "Duplicate group names need to be renamed before WebDAV sync" }
                groupKeys[group.id] = groupKey
                add(BackupRecord(groupKey, "group", payload("name" to if (isDefault) "Default" else group.name, "default" to isDefault), now, localId = group.id))
            }
            val feeds = database.feedDao().queryAll(id)
            val feedsById = feeds.associateBy { it.id }
            feeds.forEach { feed ->
                val feedKey = key("feed", feed.url)
                check(feedKey !in seen) { "Duplicate subscriptions with the same URL need to be merged before WebDAV sync" }
                add(BackupRecord(feedKey, "feed", payload("url" to feed.url, "title" to feed.name, "icon" to feed.icon,
                    "groupKey" to (groupKeys[feed.groupId] ?: key("group", "default")), "isNotification" to feed.isNotification,
                    "isFullContent" to feed.isFullContent, "isBrowser" to feed.isBrowser), now, localId = feed.id))
            }
            val feedUrls = feeds.map { it.url }.toSet()
            database.articleDao().queryWebDavStates(id).filter { it.feedUrl in feedUrls }.forEach { state ->
                add(stateRecord(state, now))
            }
            // Daily sync never reads article bodies, even when manual content backup is enabled.
            val columnsToRead = if (config.includeArticles) "*" else "id, feedId, link, isUnread, isStarred, isReadLater"
            database.openHelper.readableDatabase.query("SELECT $columnsToRead FROM article WHERE accountId = ? ORDER BY id", arrayOf(id)).use { cursor ->
                val columns = cursor.columnNames.withIndex().associate { it.value to it.index }
                fun text(name: String): String? = columns[name]?.let { if (cursor.isNull(it)) null else cursor.getString(it) }
                while (cursor.moveToNext()) {
                    currentCoroutineContext().ensureActive()
                    val articleId = requireNotNull(text("id"))
                    val feed = feedsById[text("feedId")] ?: continue
                    val link = text("link").orEmpty()
                    val state = WebDavArticleState(id, feed.url, link, text("isUnread") == "1", text("isStarred") == "1", text("isReadLater") == "1")
                    add(stateRecord(state, now).copy(localId = articleId))
                    if (!config.includeArticles) continue
                    val body = payload("feedKey" to key("feed", feed.url), "feedUrl" to feed.url, "link" to link,
                        "title" to text("title"), "author" to text("author"), "date" to text("date")?.toLongOrNull(),
                        "updateAt" to text("updateAt")?.toLongOrNull(), "rawDescription" to text("rawDescription"),
                        "shortDescription" to text("shortDescription"), "img" to text("img"))
                    if (config.includeFullContent) cache.peekFullContent(id, articleId)?.let { body.addProperty("cachedBody", it) }
                    add(BackupRecord(key("article", "${feed.url}\u0000$link"), "article", body, now, localId = articleId))
                }
            }
            if (config.includeArticles) database.openHelper.readableDatabase.query("SELECT a.feedId, a.link, a.archivedAt FROM archived_article a JOIN feed f ON f.id = a.feedId WHERE f.accountId = ?", arrayOf(id)).use { cursor ->
                while (cursor.moveToNext()) {
                    val feed = feedsById[cursor.getString(0)] ?: continue
                    val link = cursor.getString(1)
                    val articleKey = key("article", "${feed.url}\u0000$link")
                    if (articleKey in seen) continue
                    add(BackupRecord(articleKey, "article", payload("feedKey" to key("feed", feed.url), "feedUrl" to feed.url, "link" to link, "title" to link), cursor.getLong(2), deleted = true))
                }
            }
        }
        WebDavSettings.scan(context, now).forEach(::add)
        baseline.values.filter { it.key !in seen && it.kind != "setting" && (it.kind != "article" || config.includeArticles) }.forEach { stamp ->
            // Cache eviction is local. A missing article is not a deletion of its reading state.
            val keepState = stamp.kind == "state" && !stamp.deleted && workspace.get(table, stamp.identity.string("feedKey").orEmpty())?.deleted == false
            add(BackupRecord(stamp.key, stamp.kind, stamp.identity, if (stamp.deleted || keepState) stamp.modifiedAt else now, deleted = !keepState, localId = if (keepState) null else stamp.localId))
        }
    }

    private fun stateRecord(state: WebDavArticleState, time: Long) = BackupRecord(
        key("state", "${state.feedUrl}\u0000${state.link}"), "state",
        payload("feedKey" to key("feed", state.feedUrl), "feedUrl" to state.feedUrl, "link" to state.link,
            "isUnread" to state.isUnread, "isStarred" to state.isStarred, "isReadLater" to state.isReadLater), time,
    )

    /** V1/V2 article records become lightweight states; content is retained only for explicit restores. */
    private fun prepareRemote(workspace: BackupWorkspace, version: Int, includeArticles: Boolean) {
        for (recordKey in workspace.keys("remote", "article")) {
            val article = requireNotNull(workspace.get("remote", recordKey))
            if (version < 3) {
                val body = article.payload
                val state = stateRecord(WebDavArticleState(0, body.string("feedUrl").orEmpty(), body.string("link").orEmpty(),
                    body.bool("isUnread", true), body.bool("isStarred"), body.bool("isReadLater")), article.modifiedAt)
                // Old cleanup tombstones describe cached content, not cross-device state deletions.
                if (!article.deleted) workspace.put("remote", state)
                val content = body.deepCopy().apply { remove("isUnread"); remove("isStarred"); remove("isReadLater") }
                workspace.put("remote", article.copy(payload = content, hash = recordHash("article", article.deleted, content)))
            }
            if (!includeArticles) workspace.remove("remote", article.key)
        }
    }

    private fun merge(workspace: BackupWorkspace, baseline: Map<String, RecordStamp>, resolution: ConflictResolution) {
        val conflicts = mutableListOf<BackupConflict>()
        var total = 0
        for (recordKey in workspace.keys()) {
            val local = workspace.get("local", recordKey)
            val remote = workspace.get("remote", recordKey)
            val common = baseline[recordKey]
            val chosen = when {
                local == null -> remote
                remote == null -> local
                local.hash == remote.hash -> if (remote.payload.has("cachedBody") && !local.payload.has("cachedBody")) remote.copy(localId = local.localId) else local
                // A fresh installation's unset preference does not conflict with an existing choice.
                common == null && local.kind == "setting" && !local.payload.has("value") -> remote
                common == null && remote.kind == "setting" && !remote.payload.has("value") -> local
                common == null && local.kind == "state" && isInitialState(local) -> remote.copy(localId = local.localId)
                common == null && remote.kind == "state" && isInitialState(remote) -> local
                local.hash == common?.hash -> remote.copy(localId = local.localId)
                remote.hash == common?.hash -> local
                else -> {
                    total++
                    if (conflicts.size < 100) conflicts += conflict(local, remote)
                    when (resolution) {
                        ConflictResolution.KEEP_REMOTE -> remote.copy(localId = local.localId)
                        ConflictResolution.NEWEST -> if (remote.modifiedAt > local.modifiedAt) remote.copy(localId = local.localId) else local
                        else -> local
                    }
                }
            }
            chosen?.let { workspace.put("merged", it) }
        }
        if (total > 0 && resolution == ConflictResolution.ASK) throw BackupConflictException(conflicts, total)
    }

    private fun isInitialState(record: BackupRecord) = !record.deleted && record.payload.bool("isUnread", true) &&
        !record.payload.bool("isStarred") && !record.payload.bool("isReadLater")
    private fun conflict(local: BackupRecord, remote: BackupRecord): BackupConflict {
        fun summary(record: BackupRecord) = if (record.deleted) context.getString(R.string.webdav_record_deleted) else when (record.kind) {
            "state" -> context.getString(R.string.webdav_article_state_summary,
                context.getString(if (record.payload.bool("isUnread")) R.string.enabled else R.string.disabled),
                context.getString(if (record.payload.bool("isStarred")) R.string.enabled else R.string.disabled),
                context.getString(if (record.payload.bool("isReadLater")) R.string.enabled else R.string.disabled))
            "feed" -> "${record.payload.string("title")} · ${record.payload.string("url")}"
            "setting" -> record.payload["value"]?.toString() ?: context.getString(R.string.defaults)
            else -> record.payload.string("title") ?: record.payload.string("name").orEmpty()
        }
        return BackupConflict(local.key, local.kind, local.payload.string("title") ?: local.payload.string("name") ?: local.payload.string("link") ?: local.key, summary(local), summary(remote))
    }

    private fun normalizeParents(workspace: BackupWorkspace, resolution: ConflictResolution) {
        val defaultKey = key("group", "default")
        if (workspace.get("merged", defaultKey)?.deleted != false) workspace.put("merged", BackupRecord(defaultKey, "group", payload("name" to "Default", "default" to true), 0))
        for (feed in workspace.records("merged", "feed").toList()) {
            if (feed.deleted) continue
            val parent = workspace.get("merged", feed.payload.string("groupKey").orEmpty())
            if (parent == null || parent.deleted) {
                val updated = feed.payload.deepCopy().apply { addProperty("groupKey", defaultKey) }
                workspace.put("merged", feed.copy(payload = updated, hash = recordHash("feed", false, updated)))
            }
        }
        for (recordKey in workspace.keys("merged", "state") + workspace.keys("merged", "article")) {
            val article = requireNotNull(workspace.get("merged", recordKey))
            if (article.deleted) continue
            val parent = workspace.get("merged", article.payload.string("feedKey").orEmpty())
            if (parent == null || parent.deleted) {
                val local = workspace.get("local", article.key)
                val remoteParent = workspace.get("remote", article.payload.string("feedKey").orEmpty())
                if (local != null && !local.deleted && resolution == ConflictResolution.ASK) {
                    throw BackupConflictException(listOf(BackupConflict(article.key, "article", article.payload.string("title").orEmpty(), context.getString(R.string.webdav_record_retained), context.getString(R.string.webdav_feed_deleted))), 1)
                }
                val keepLocal = resolution == ConflictResolution.KEEP_LOCAL || resolution == ConflictResolution.NEWEST && article.modifiedAt > (remoteParent?.modifiedAt ?: Long.MAX_VALUE)
                val localParent = workspace.get("local", article.payload.string("feedKey").orEmpty())
                if (keepLocal && localParent != null && !localParent.deleted) workspace.put("merged", localParent)
                else workspace.put("merged", BackupRecord(article.key, article.kind, article.identity(), maxOf(article.modifiedAt, parent?.modifiedAt ?: 0), deleted = true, localId = article.localId))
            }
        }
    }

    private fun stamps(workspace: BackupWorkspace, table: String): List<RecordStamp> = workspace.records(table).map {
        RecordStamp(it.key, it.kind, it.hash, it.modifiedAt, it.deleted, it.identity(), it.localId)
    }.toList()

    private suspend fun preserveRecovery(id: Int, config: WebDavConfig, baseline: Map<String, RecordStamp>) {
        val directory = storage.resolve("$id/recovery/${System.currentTimeMillis()}-${UUID.randomUUID()}")
        BackupWorkspace(directory).use { recovery ->
            scan(id, config.copy(includeArticles = true, includeFullContent = true), recovery, "local", baseline)
            val chunks = BackupTransport(client, config).writeChunks(recovery, "local")
            val now = System.currentTimeMillis()
            val manifest = BackupManifest(namespaceId = config.namespaceId, generation = "$now-${UUID.randomUUID()}", exportedAt = now,
                recordCount = chunks.sumOf { it.count }, chunks = chunks, includesArticles = true).signed()
            atomicJson(directory.resolve("manifest.json"), manifest)
        }
        directory.resolve("staging.db").delete()
        directory.parentFile?.listFiles()?.filter { it.isDirectory }?.sortedByDescending { it.name }?.drop(3)?.forEach { it.deleteRecursively() }
    }

    private suspend fun apply(id: Int, config: WebDavConfig, workspace: BackupWorkspace, baseline: Map<String, RecordStamp>, replace: Boolean): WebDavSyncResult {
        var groupsAdded = 0; var feedsAdded = 0; var articlesAdded = 0; var articlesUpdated = 0
        val writtenCache = mutableListOf<Pair<String, File>>()
        val removedCache = mutableListOf<String>()
        val replaceArticles = replace && config.includeArticles
        database.withTransaction {
            scan(id, config, workspace, "fresh", baseline)
            check(workspace.unchanged()) { "Local articles changed during WebDAV sync. Retry to include those changes; no local data was replaced." }
            if (replaceArticles) database.articleDao().deleteByAccountId(id)
            if (replace) {
                database.articleDao().deleteWebDavStates(id)
                database.articleDao().resetReadingStates(id)
                database.pendingArticleStateDao().clearAccount(id)
            }
            val groups = mutableMapOf<String, String>()
            for (record in workspace.records("merged", "group")) {
                if (record.deleted) continue
                val local = workspace.get("local", record.key)?.takeUnless { it.deleted }
                val target = if (record.payload.bool("default")) id.getDefaultGroupId() else local?.localId ?: (id spacerDollar UUID.randomUUID().toString())
                if (local == null) groupsAdded++
                database.groupDao().insert(Group(target, if (record.payload.bool("default")) context.getString(R.string.defaults) else record.payload.string("name").orEmpty(), id))
                groups[record.key] = target
                workspace.put("merged", record.copy(localId = target))
            }
            val feeds = mutableMapOf<String, String>()
            for (record in workspace.records("merged", "feed")) {
                if (record.deleted) continue
                val local = workspace.get("local", record.key)?.takeUnless { it.deleted }
                val target = local?.localId ?: (id spacerDollar "webdav-${record.key.substringAfter(':')}")
                val body = record.payload
                database.feedDao().insert(Feed(target, body.string("title") ?: body.string("url").orEmpty(), icon = body.string("icon"), url = body.string("url").orEmpty(),
                    groupId = groups[body.string("groupKey")] ?: id.getDefaultGroupId(), accountId = id,
                    isNotification = body.bool("isNotification"), isFullContent = body.bool("isFullContent"), isBrowser = body.bool("isBrowser")))
                if (local == null) feedsAdded++
                feeds[record.key] = target
                workspace.put("merged", record.copy(localId = target))
            }
            // Content exists only in explicit backups. Daily sync never inserts placeholder articles.
            for (record in workspace.records("merged", "article")) {
                currentCoroutineContext().ensureActive()
                val body = record.payload
                val local = workspace.get("local", record.key)?.takeUnless { it.deleted }
                val feed = feeds[body.string("feedKey")] ?: continue
                if (record.deleted) {
                    if (!replaceArticles) local?.localId?.let { database.articleDao().deleteByIds(id, listOf(it)); removedCache += it }
                    database.feedDao().insertArchivedArticles(listOf(ArchivedArticle(feedId = feed, link = body.string("link").orEmpty(), archivedAt = record.modifiedAt)))
                } else {
                    val target = local?.localId ?: (id spacerDollar "webdav-${record.key.substringAfter(':')}")
                    database.articleDao().insert(Article(target, Date(body.long("date")), body.string("title").orEmpty(), body.string("author"),
                        body.string("rawDescription").orEmpty(), body.string("shortDescription").orEmpty(), img = body.string("img"), link = body.string("link").orEmpty(),
                        feedId = feed, accountId = id,
                        updateAt = body.string("updateAt")?.toLongOrNull()?.let(::Date) ?: Date(body.long("date"))))
                    if (local == null || replaceArticles) articlesAdded++
                    body.string("cachedBody")?.takeIf { it.isNotBlank() }?.let { content ->
                        val staged = workspace.directory.resolve("cache-${digest(target)}.html")
                        staged.writeText(content)
                        writtenCache += target to staged
                    }
                    workspace.put("merged", record.copy(localId = target))
                }
            }
            for (record in workspace.records("merged", "state")) {
                if (record.deleted) continue
                val body = record.payload
                val feed = feeds[body.string("feedKey")] ?: continue
                val state = WebDavArticleState(id, body.string("feedUrl").orEmpty(), body.string("link").orEmpty(),
                    body.bool("isUnread", true), body.bool("isStarred"), body.bool("isReadLater"))
                database.articleDao().saveWebDavStates(listOf(state))
                val local = workspace.get("local", record.key)?.takeUnless { it.deleted }
                val targets = database.articleDao().webDavStateTargets(id, feed, state.link)
                if (local != null && local.hash != record.hash) targets.forEach { database.pendingArticleStateDao().clearArticle(id, it) }
                database.articleDao().applyWebDavState(id, feed, state.link, state.isUnread, state.isStarred, state.isReadLater)
                if (local != null && local.hash != record.hash) articlesUpdated += targets.size
                workspace.put("merged", record.copy(localId = targets.firstOrNull()))
            }
            // A lightweight replacement keeps the cached articles of retained subscriptions.
            val retainedFeeds = feeds.values.toSet()
            val deletedFeeds = workspace.records("merged", "feed").filter { it.deleted }.mapNotNull { workspace.get("local", it.key)?.localId }.toSet()
            database.feedDao().queryAll(id).filter { feed ->
                if (replace) feed.id !in retainedFeeds
                else feed.id in deletedFeeds
            }.forEach { feed ->
                removedCache += database.articleDao().queryArticleIdentitiesByFeedId(feed.id).map { it.id }
                database.feedDao().delete(feed)
            }
            val retainedGroups = groups.values.toSet()
            val deletedGroups = workspace.records("merged", "group").filter { it.deleted }.mapNotNull { workspace.get("local", it.key)?.localId }.toSet()
            database.groupDao().queryAll(id).filter { group ->
                if (replace) group.id !in retainedGroups
                else group.id in deletedGroups
            }.forEach { database.groupDao().delete(it) }
            database.articleDao().pruneWebDavStates(id)
            WebDavSettings.apply(context, workspace)
        }
        if (replaceArticles) cache.clearCache(id)
        removedCache.forEach { cache.deleteCacheFor(it, id) }
        writtenCache.forEach { (articleId, content) -> cache.writeFullContent(id, articleId, content.readText()).getOrThrow() }
        return WebDavSyncResult(groupsAdded, feedsAdded, articlesAdded, articlesUpdated)
    }

    private fun importLegacy(data: WebDavSyncData, workspace: BackupWorkspace) {
        val timestamp = data.exportedAt.coerceAtLeast(0)
        val groups = data.groups.associate { dto -> dto.id to key("group", dto.name.orEmpty()) }
        data.groups.forEach { dto -> workspace.put("remote", BackupRecord(groups[dto.id]!!, "group", payload("name" to dto.name.orEmpty(), "default" to false), timestamp)) }
        data.feeds.forEach { dto ->
            val url = requireNotNull(dto.url) { "Invalid legacy feed URL" }
            workspace.put("remote", BackupRecord(key("feed", url), "feed", payload("url" to url, "title" to dto.title, "icon" to dto.icon,
                "groupKey" to (groups[dto.groupId] ?: key("group", "default")), "isNotification" to dto.isNotification, "isFullContent" to dto.isFullContent, "isBrowser" to dto.isBrowser), timestamp))
        }
        data.articles.forEach { dto ->
            val url = requireNotNull(dto.feedUrl) { "Invalid legacy article feed" }
            val link = requireNotNull(dto.link) { "Invalid legacy article link" }
            workspace.put("remote", BackupRecord(key("article", "$url\u0000$link"), "article", payload("feedKey" to key("feed", url), "feedUrl" to url, "link" to link,
                "title" to dto.title, "author" to dto.author, "date" to dto.date, "updateAt" to dto.date, "rawDescription" to dto.rawDescription,
                "shortDescription" to dto.shortDescription, "img" to dto.img, "isUnread" to dto.isUnread, "isStarred" to dto.isStarred, "isReadLater" to dto.isReadLater), timestamp))
        }
    }
}
