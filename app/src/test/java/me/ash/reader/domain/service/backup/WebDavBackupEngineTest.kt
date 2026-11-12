package me.ash.reader.domain.service.backup

import android.app.Application
import androidx.room.Room
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.JsonParser
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleLinkMeta
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.service.WebDavSyncService.WebDavConfig
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.ARTICLE_SEARCH_CALLBACK
import me.ash.reader.infrastructure.net.WebDavClient
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.dataStore
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class WebDavBackupEngineTest {
    private data class Resource(val bytes: ByteArray, val etag: String)
    private val resources = ConcurrentHashMap<String, Resource>()
    private val revision = AtomicInteger()
    private val chunkWrites = AtomicInteger()
    private val manifestWrites = AtomicInteger()
    @Volatile private var raceOnManifest = false
    @Volatile private var onManifestPut: (() -> Unit)? = null
    @Volatile private var omitManifestEtag = false
    @Volatile private var manifestEtagTransform: (String) -> String = { it }
    @Volatile private var onSnapshotPut: (() -> Unit)? = null
    @Volatile private var afterManifestPut: (() -> Unit)? = null
    private val server = MockWebServer()
    private lateinit var db: AndroidDatabase
    private lateinit var cache: ReaderCacheHelper
    private lateinit var engine: WebDavBackupEngine
    private lateinit var config: WebDavConfig
    private val context get() = RuntimeEnvironment.getApplication()
    private val article = Article("article-1", Date(1_000), "Original", rawDescription = "<p>Body</p>", shortDescription = "Body", link = "https://example.org/a", feedId = "feed-1", accountId = 1, updateAt = Date(1_000))

    @Before fun setup() = runBlocking {
        context.noBackupFilesDir.resolve("webdav-v2").deleteRecursively()
        context.dataStore.edit { it.clear() }
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(resources) {
                val path = request.requestUrl!!.encodedPath
                val isManifest = path.endsWith("readyou-sync-v3.json") || path.endsWith("readyou-backup-v3.json")
                when (request.method) {
                    "GET" -> resources[path]?.let {
                        MockResponse().setResponseCode(200).apply {
                            if (!isManifest || !omitManifestEtag) setHeader("ETag", if (isManifest) manifestEtagTransform(it.etag) else it.etag)
                            setBody(Buffer().write(it.bytes))
                        }
                    } ?: MockResponse().setResponseCode(404)
                    "PROPFIND" -> {
                        val children = if (request.getHeader("Depth") == "1") resources.keys.mapNotNull { key ->
                            key.removePrefix(path.trimEnd('/') + "/").takeIf { key.startsWith(path.trimEnd('/') + "/") }
                                ?.substringBefore('/')?.let { path.trimEnd('/') + "/$it/" }
                        }.distinct() else emptyList()
                        if (children.isEmpty()) MockResponse().setResponseCode(404)
                        else MockResponse().setResponseCode(207).setBody(
                            "<d:multistatus xmlns:d=\"DAV:\">" + children.joinToString("") {
                                "<d:response><d:href>$it</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>"
                            } + "</d:multistatus>"
                        )
                    }
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "PUT" -> {
                        if (isManifest) onManifestPut?.also { onManifestPut = null }?.invoke()
                        if (isManifest && raceOnManifest) {
                            raceOnManifest = false
                            resources[path]?.let { resources[path] = it.copy(etag = "\"${revision.incrementAndGet()}\"") }
                        }
                        val existing = resources[path]
                        val match = request.getHeader("If-Match")
                        if (request.getHeader("If-None-Match") == "*" && existing != null || match != null && match != existing?.etag) {
                            MockResponse().setResponseCode(412)
                        } else {
                            val resource = Resource(request.body.readByteArray(), "\"${revision.incrementAndGet()}\"")
                            resources[path] = resource
                            if (path.contains("/chunks/")) chunkWrites.incrementAndGet()
                            if (isManifest) manifestWrites.incrementAndGet()
                            if (path.contains("/snapshots/")) onSnapshotPut?.also { onSnapshotPut = null }?.invoke()
                            if (isManifest) afterManifestPut?.also { afterManifestPut = null }?.invoke()
                            MockResponse().setResponseCode(201).setHeader("ETag", resource.etag)
                        }
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        config = WebDavConfig(serverUrl = server.url("dav").toString(), namespaceId = "paired", includeArticles = true)
        db = Room.inMemoryDatabaseBuilder(context, AndroidDatabase::class.java).addCallback(ARTICLE_SEARCH_CALLBACK).allowMainThreadQueries().build()
        cache = ReaderCacheHelper(context, Dispatchers.Unconfined, mock(), db.articleSearchDao())
        engine = WebDavBackupEngine(context, db, WebDavClient(OkHttpClient(), context), cache)
        for (id in 1..2) db.groupDao().insert(Group(id.getDefaultGroupId(), "Defaults", id))
        db.feedDao().insert(Feed("feed-1", "Feed", url = "https://example.org/rss", groupId = 1.getDefaultGroupId(), accountId = 1))
        db.articleDao().insert(article)
    }
    @After fun close() { db.close(); server.shutdown() }
    private suspend fun backup(id: Int = 1, cfg: WebDavConfig = config) = engine.backup(id, cfg) { null }
    private suspend fun sync(id: Int = 1, resolution: ConflictResolution = ConflictResolution.ASK) = engine.sync(id, config, resolution) { null }
    private suspend fun restore(id: Int, mode: RestoreMode = RestoreMode.MERGE) = engine.restore(id, config, mode, ConflictResolution.ASK, null) { null }
    private suspend fun articleFor(id: Int) = db.articleDao().queryStateMetadata(id).single().let { db.articleDao().queryById(it.id)!!.article }

    private fun remoteRecords(scope: BackupScope): List<BackupRecord> {
        val manifest = resources.entries.single { it.key.endsWith(scope.manifestFile) }.value.bytes
            .decodeToString().let { backupGson.fromJson(it, BackupManifest::class.java) }
        return manifest.chunks.flatMap { chunk ->
            val bytes = resources.entries.single { it.key.endsWith("/chunks/${chunk.hash}.json.gz") }.value.bytes
            GZIPInputStream(bytes.inputStream()).reader().use { reader ->
                JsonParser.parseReader(reader).asJsonArray.map { backupGson.fromJson(it, BackupRecord::class.java) }
            }
        }
    }

    @Test fun dailySyncNeverUploadsArticleContentEvenWhenFullBackupIsEnabled(): Unit = runBlocking {
        cache.writeFullContent(1, article.id, "<p>Private cached full text</p>").getOrThrow()
        engine.sync(1, config.copy(includeFullContent = true), ConflictResolution.ASK) { null }
        val records = remoteRecords(BackupScope.SYNC)
        assertEquals(setOf("group", "feed", "state", "setting"), records.map { it.kind }.toSet())
        val state = records.single { it.kind == "state" }
        assertEquals(setOf("feedKey", "feedUrl", "link", "isUnread", "isStarred", "isReadLater"), state.payload.keySet())
        assertFalse(backupGson.toJson(records).contains("Private cached full text"))
        assertFalse(backupGson.toJson(records).contains("Original"))
        assertFalse(resources.keys.any { it.endsWith(BackupScope.BACKUP.manifestFile) })
    }

    @Test fun defaultManualBackupContainsNoArticleBodiesOrFullText(): Unit = runBlocking {
        assertFalse(WebDavConfig().includeArticles)
        backup(cfg = config.copy(includeArticles = false, includeFullContent = true))
        assertFalse(remoteRecords(BackupScope.BACKUP).any { it.kind == "article" })
        restore(2)
        assertTrue(db.articleDao().queryStateMetadata(2).isEmpty())
        assertEquals(article.link, db.articleDao().queryWebDavStates(2).single().link)
    }

    @Test fun statesArriveBeforeArticlesAndAreAppliedWhenRssIsFetched(): Unit = runBlocking {
        db.articleDao().markAsReadByArticleId(1, article.id, false)
        db.articleDao().markAsStarredByArticleId(1, article.id, true)
        db.articleDao().markAsReadLaterByIdSet(1, setOf(article.id), true)
        sync()
        val result = sync(2)
        assertEquals(0, result.articlesAdded)
        assertTrue(db.articleDao().queryStateMetadata(2).isEmpty())
        val feed = db.feedDao().queryAll(2).single()
        val fetched = db.articleDao().insertListIfNotExist(
            listOf(article.copy(id = "rss-fetched", accountId = 2, feedId = feed.id)), feed, restoreWebDavStates = true,
        ).single()
        assertFalse(fetched.isUnread)
        assertTrue(fetched.isStarred)
        assertTrue(fetched.isReadLater)
        assertEquals("<p>Body</p>", articleFor(2).rawDescription)
        sync(2)
        assertFalse(articleFor(1).isUnread)
    }

    @Test fun firstPairingAppliesRemoteStatesToAlreadyFetchedArticles(): Unit = runBlocking {
        val feed = Feed("device-two-feed", "Feed", url = "https://example.org/rss", groupId = 2.getDefaultGroupId(), accountId = 2)
        db.feedDao().insert(feed)
        db.articleDao().insert(article.copy(id = "device-two-article", feedId = feed.id, accountId = 2, rawDescription = "Device two body"))
        db.articleDao().markAsReadByArticleId(1, article.id, false)
        sync()
        sync(2)
        assertFalse(articleFor(2).isUnread)
        assertEquals("Device two body", articleFor(2).rawDescription)
    }

    @Test fun manualContentBackupAndDailySyncKeepIndependentVersions(): Unit = runBlocking {
        backup()
        val original = resources.entries.single { it.key.endsWith(BackupScope.BACKUP.manifestFile) }.value
        db.articleDao().insert(article.copy(rawDescription = "Changed only on this device"))
        sync()
        assertEquals(original, resources.entries.single { it.key.endsWith(BackupScope.BACKUP.manifestFile) }.value)
        assertEquals(1, engine.versions(1, config).size)
        assertTrue(remoteRecords(BackupScope.BACKUP).any { it.payload.string("rawDescription") == "<p>Body</p>" })
        assertFalse(remoteRecords(BackupScope.SYNC).any { it.kind == "article" })
        assertEquals("Changed only on this device", articleFor(1).rawDescription)
    }

    @Test fun lightweightReplacementPreservesCachedArticlesOfRetainedFeeds(): Unit = runBlocking {
        backup(cfg = config.copy(includeArticles = false))
        cache.writeFullContent(1, article.id, "Local cached full text").getOrThrow()
        db.articleDao().insert(article.copy(title = "Locally refreshed title"))
        db.articleDao().insert(article.copy(id = "extra", link = "https://example.org/extra", isStarred = true))
        restore(1, RestoreMode.REPLACE)
        val originals = db.articleDao().queryArticlesByLinks(listOf(article.link), article.feedId, 1)
        assertEquals("Locally refreshed title", originals.single().title)
        assertEquals(2, db.articleDao().queryStateMetadata(1).size)
        assertFalse(db.articleDao().queryById("extra")!!.article.isStarred)
        assertTrue(cache.readFullContent(article.id, 1).getOrThrow().contains("Local cached full text"))
    }

    @Test fun emptyFullBackupStillReplacesArticleContent(): Unit = runBlocking {
        db.articleDao().deleteByAccountId(1)
        backup()
        db.articleDao().insert(article)
        restore(1, RestoreMode.REPLACE)
        assertTrue(db.articleDao().queryStateMetadata(1).isEmpty())
    }

    @Test fun appSettingsAreRestoredWithoutCredentialsOrDeviceChoices(): Unit = runBlocking {
        val theme = intPreferencesKey("themeIndex")
        val fontSize = intPreferencesKey("readingTextFontSize")
        val password = stringPreferencesKey("webdav.1.password")
        val browser = stringPreferencesKey("openLinkAppSpecificBrowser")
        context.dataStore.edit {
            it[theme] = 7; it[fontSize] = 22; it[password] = "PRIVATE_PASSWORD"; it[browser] = "device.browser"
            it[stringPreferencesKey("tls.example.org")] = "PRIVATE_CERTIFICATE"
        }
        backup(cfg = config.copy(includeArticles = false))
        val json = backupGson.toJson(remoteRecords(BackupScope.BACKUP))
        assertFalse(json.contains("PRIVATE_"))
        assertFalse(json.contains("device.browser"))
        context.dataStore.edit { it.remove(theme); it.remove(fontSize); it[password] = "KEEP_LOCAL" }
        restore(2, RestoreMode.REPLACE)
        val preferences = context.dataStore.data.first()
        assertEquals(7, preferences[theme])
        assertEquals(22, preferences[fontSize])
        assertEquals("KEEP_LOCAL", preferences[password])
        assertEquals("device.browser", preferences[browser])
    }

    @Test fun appSettingsMergePerPreferenceAndCanResetToDefaults(): Unit = runBlocking {
        val theme = intPreferencesKey("themeIndex")
        val fontSize = intPreferencesKey("readingTextFontSize")
        context.dataStore.edit { it[theme] = 5; it[fontSize] = 14 }
        sync()
        sync(2)
        context.dataStore.edit { it[theme] = 4 }
        sync(2)
        // Simulate device 1's independent local preferences.
        context.dataStore.edit { it[theme] = 5; it[fontSize] = 22 }
        sync()
        assertEquals(4, context.dataStore.data.first()[theme])
        assertEquals(22, context.dataStore.data.first()[fontSize])
        context.dataStore.edit { it.remove(theme) }
        sync()
        context.dataStore.edit { it[theme] = 4; it[fontSize] = 14 }
        sync(2)
        assertNull(context.dataStore.data.first()[theme])
        assertEquals(22, context.dataStore.data.first()[fontSize])
    }

    @Test fun oldV2BackupsMigrateToStatesWithoutLosingTheirRestorableContent(): Unit = runBlocking {
        backup()
        val current = remoteRecords(BackupScope.BACKUP)
        val states = current.filter { it.kind == "state" }.associateBy { it.payload.string("link") }
        val legacyRecords = current.filter { it.kind in listOf("group", "feed", "article") }.map { record ->
            if (record.kind != "article") record else {
                val body = record.payload.deepCopy()
                val state = states.getValue(body.string("link"))
                listOf("isUnread", "isStarred", "isReadLater").forEach { body.add(it, state.payload[it]) }
                record.copy(payload = body, hash = recordHash("article", false, body))
            }
        }
        val directory = File.createTempFile("old-backup", "dir", context.cacheDir).apply { delete(); mkdirs() }
        val root = java.net.URI(config.directoryUrl()).rawPath
        BackupWorkspace(directory).use { workspace ->
            legacyRecords.forEach { workspace.put("local", it) }
            val chunks = BackupTransport(WebDavClient(OkHttpClient(), context), config).writeChunks(workspace, "local")
            chunks.forEach { chunk -> resources["$root/chunks/${chunk.hash}.json.gz"] = Resource(directory.resolve("${chunk.hash}.json.gz").readBytes(), "\"legacy-chunk\"") }
            val now = System.currentTimeMillis()
            val manifest = BackupManifest(version = 2, namespaceId = config.namespaceId,
                generation = "$now-${java.util.UUID.randomUUID()}", exportedAt = now,
                recordCount = chunks.sumOf { it.count }, chunks = chunks).signed()
            val legacy = Resource(backupGson.toJson(manifest).toByteArray(), "\"legacy\"")
            resources["$root/readyou-manifest-v2.json"] = legacy
            resources["$root/snapshots/${manifest.generation}.json"] = legacy
        }
        directory.deleteRecursively()
        resources.remove("$root/${BackupScope.BACKUP.manifestFile}")
        val old = resources.getValue("$root/readyou-manifest-v2.json")
        sync(2)
        assertTrue(db.articleDao().queryStateMetadata(2).isEmpty())
        assertEquals(1, db.articleDao().queryWebDavStates(2).size)
        assertFalse(remoteRecords(BackupScope.SYNC).any { it.kind == "article" })
        assertEquals(old, resources.getValue("$root/readyou-manifest-v2.json"))
        restore(2, RestoreMode.REPLACE)
        assertEquals("<p>Body</p>", articleFor(2).rawDescription)
    }

    @Test fun oldV1SyncImportsOnlyStatesAndItsBackupRemainsRestorable(): Unit = runBlocking {
        val legacy = me.ash.reader.domain.service.WebDavSyncService.WebDavSyncData(
            exportedAt = 1_000,
            feeds = listOf(me.ash.reader.domain.service.WebDavSyncService.FeedDto(id = "old-feed", url = "https://example.org/rss", title = "Feed")),
            articles = listOf(me.ash.reader.domain.service.WebDavSyncService.ArticleDto(feedUrl = "https://example.org/rss", link = article.link,
                title = "Legacy title", date = 1_000, isUnread = false, rawDescription = "Legacy body")),
        )
        engine.sync(2, config, ConflictResolution.ASK) { legacy }
        assertTrue(db.articleDao().queryStateMetadata(2).isEmpty())
        assertFalse(db.articleDao().queryWebDavStates(2).single().isUnread)
        engine.restore(2, config, RestoreMode.REPLACE, ConflictResolution.ASK, null) { legacy }
        assertEquals("Legacy body", articleFor(2).rawDescription)
        assertFalse(articleFor(2).isUnread)
    }

    @Test fun appSettingsEditedDuringPublicationAreNotOverwritten(): Unit = runBlocking {
        val fontSize = intPreferencesKey("readingTextFontSize")
        context.dataStore.edit { it[fontSize] = 14 }
        sync()
        onManifestPut = { runBlocking { context.dataStore.edit { it[fontSize] = 22 } } }
        assertTrue(runCatching { sync() }.isFailure)
        assertEquals(22, context.dataStore.data.first()[fontSize])
    }

    @Test fun backupPublishesWhenServerOmitsManifestEtag(): Unit = runBlocking {
        backup()
        omitManifestEtag = true
        db.articleDao().insert(article.copy(title = "Updated"))

        backup()

        assertEquals(2, engine.versions(1, config).size)
        restore(2)
        assertEquals("Updated", articleFor(2).title)
    }

    @Test fun weakAndUnquotedEtagsDoNotBlockBackups(): Unit = runBlocking {
        backup()
        manifestEtagTransform = { "W/$it" }
        backup()
        manifestEtagTransform = { it.trim('"') }
        db.articleDao().insert(article.copy(title = "Unquoted ETag"))
        backup()
        restore(2)
        assertEquals("Unquoted ETag", articleFor(2).title)
        assertEquals(3, engine.versions(1, config).size)
    }

    @Test fun syncWithoutEtagsStillSharesReadingStates(): Unit = runBlocking {
        omitManifestEtag = true
        sync()
        db.articleDao().markAsReadByArticleId(1, article.id, false)
        sync()
        sync(2)
        assertFalse(db.articleDao().queryWebDavStates(2).single().isUnread)
        assertFalse(remoteRecords(BackupScope.SYNC).any { it.kind == "article" })
    }

    @Test fun missingEtagDoesNotOverwriteChangesDetectedBeforePublication(): Unit = runBlocking {
        backup()
        val path = resources.keys.single { it.endsWith(BackupScope.BACKUP.manifestFile) }
        val original = resources.getValue(path)
        db.articleDao().insert(article.copy(title = "Second"))
        backup()
        omitManifestEtag = true
        onSnapshotPut = { resources[path] = original }
        val before = manifestWrites.get()

        val failure = runCatching { backup() }.exceptionOrNull()

        assertTrue(failure.toString(), failure is BackupConcurrentWriteException)
        assertEquals(before, manifestWrites.get())
        assertEquals(original, resources[path])
    }

    @Test fun missingEtagDetectsAConcurrentOverwriteBeforeApplyingLocally(): Unit = runBlocking {
        omitManifestEtag = true
        sync()
        val path = resources.keys.single { it.endsWith(BackupScope.SYNC.manifestFile) }
        val original = resources.getValue(path)
        db.articleDao().markAsReadByArticleId(1, article.id, false)
        afterManifestPut = { resources[path] = original }

        val failure = runCatching { sync() }.exceptionOrNull()

        assertTrue(failure.toString(), failure is BackupConcurrentWriteException)
        assertFalse(articleFor(1).isUnread)
        assertEquals(original, resources[path])
    }

    @Test fun freshDeviceFindsTheOnlyExistingDatasetWithoutPairingId(): Unit = runBlocking {
        backup()
        backup()
        val accountsPath = java.net.URI(config.directoryUrl(includeNamespace = false)).rawPath + "/accounts"
        resources["$accountsPath/unused/unrelated.txt"] = Resource(byteArrayOf(), "\"unused\"")
        val freshConfig = config.copy(namespaceId = "default")
        engine.restore(2, freshConfig, RestoreMode.REPLACE, ConflictResolution.ASK, null) { null }
        assertEquals("Original", articleFor(2).title)
        engine.sync(2, freshConfig, ConflictResolution.ASK) { null }
        assertFalse(resources.keys.any { it.contains("/accounts/default/") })
        assertEquals(2, engine.versions(2, freshConfig).size)
    }

    @Test fun multipleOldDatasetsAreNotSilentlyMixed(): Unit = runBlocking {
        backup()
        val first = resources.entries.single { it.key.endsWith(BackupScope.BACKUP.manifestFile) }.value
        val another = backupGson.fromJson(first.bytes.decodeToString(), BackupManifest::class.java)
            .copy(namespaceId = "another").signed()
        val path = java.net.URI(config.copy(namespaceId = "another").directoryUrl()).rawPath
        resources["$path/${BackupScope.BACKUP.manifestFile}"] = Resource(backupGson.toJson(another).toByteArray(), "\"another\"")
        val before = manifestWrites.get()
        val failure = runCatching {
            engine.sync(2, config.copy(namespaceId = "default"), ConflictResolution.ASK) { null }
        }.exceptionOrNull()
        assertTrue(failure.toString(), failure?.message?.contains("multiple", ignoreCase = true) == true)
        assertEquals(before, manifestWrites.get())
        assertTrue(db.feedDao().queryAll(2).isEmpty())
    }

    @Test fun unusedOldPairingIdFindsTheExistingDataset(): Unit = runBlocking {
        backup()
        val unusedConfig = config.copy(namespaceId = "previously-unused-id")
        engine.restore(2, unusedConfig, RestoreMode.REPLACE, ConflictResolution.ASK, null) { null }
        assertEquals("Original", articleFor(2).title)
        assertFalse(resources.keys.any { it.contains("/accounts/previously-unused-id/") })
    }

    @Test fun twoNewDevicesShareTheDefaultDirectoryWithoutAPairingId(): Unit = runBlocking {
        engine.sync(1, WebDavConfig(serverUrl = config.serverUrl), ConflictResolution.ASK) { null }
        engine.sync(2, WebDavConfig(serverUrl = config.serverUrl), ConflictResolution.ASK) { null }
        assertEquals("https://example.org/rss", db.feedDao().queryAll(2).single().url)
        assertTrue(resources.keys.all { it.contains("/accounts/default/") })
        assertTrue(db.articleDao().queryStateMetadata(2).isEmpty())
    }

    @Test fun cacheCleanupKeepsOtherDevicesArticlesAndReadingStates() = runBlocking {
        backup()
        restore(2)
        sync(2)
        db.articleDao().markAsReadByArticleId(1, article.id, false)
        db.articleDao().archiveAndDelete(1, listOf(ArticleLinkMeta(article.id, article.feedId, article.link)))
        sync()
        sync(2)
        assertTrue(db.articleDao().queryStateMetadata(1).isEmpty())
        assertFalse(articleFor(2).isUnread)
        assertEquals("Original", articleFor(2).title)
        assertFalse(db.articleDao().queryWebDavStates(1).single().isUnread)
    }

    @Test fun conflictingChangesArePreviewedBeforeAnyLocalOrRemoteMutation() = runBlocking {
        backup()
        restore(2)
        sync(2)
        db.articleDao().markAsReadByArticleId(1, article.id, false)
        val other = articleFor(2)
        db.articleDao().markAsStarredByArticleId(2, other.id, true)
        sync(2)
        val writesBefore = manifestWrites.get()
        val failure = runCatching { sync() }.exceptionOrNull()
        assertTrue(failure is BackupConflictException)
        assertEquals(1, (failure as BackupConflictException).total)
        assertEquals(writesBefore, manifestWrites.get())
        assertFalse(articleFor(1).isUnread)
        assertFalse(articleFor(1).isStarred)
        sync(resolution = ConflictResolution.KEEP_REMOTE)
        assertTrue(articleFor(1).isStarred)
    }

    @Test fun concurrentPublisherCannotOverwriteRemoteOrApplyItsSnapshotLocally() = runBlocking {
        backup()
        restore(2)
        sync()
        sync(2)
        db.articleDao().markAsReadByArticleId(2, articleFor(2).id, false)
        sync(2)
        raceOnManifest = true
        val failure = runCatching { sync() }.exceptionOrNull()
        assertTrue(failure.toString(), failure is BackupConcurrentWriteException)
        assertEquals("Original", articleFor(1).title)
    }

    @Test fun corruptChunksPreventBackupOverwriteAndLocalRestore() = runBlocking {
        backup()
        val chunk = resources.keys.first { it.contains("/chunks/") }
        resources[chunk] = Resource("damaged".toByteArray(), "\"bad\"")
        val before = manifestWrites.get()
        assertTrue(runCatching { backup() }.isFailure)
        assertTrue(runCatching { restore(2) }.isFailure)
        assertEquals(before, manifestWrites.get())
        assertTrue(db.articleDao().queryStateMetadata(2).isEmpty())
        assertEquals("Original", articleFor(1).title)
    }

    @Test fun replacementPreservesRecoveryCopyDatesAndOptionalFullContent() = runBlocking {
        val full = config.copy(includeFullContent = true)
        cache.writeFullContent(1, article.id, "<p>Cached full content</p>").getOrThrow()
        backup(cfg = full)
        db.articleDao().insert(article.copy(title = "Changed"))
        db.articleDao().insert(article.copy(id = "extra", link = "https://example.org/extra"))
        engine.restore(1, full, RestoreMode.REPLACE, ConflictResolution.ASK, null) { null }
        val restored = articleFor(1)
        assertEquals("Original", restored.title)
        assertEquals(1_000L, restored.date.time)
        assertEquals(1_000L, restored.updateAt!!.time)
        assertTrue(cache.readFullContent(restored.id, 1).getOrThrow().contains("Cached full content"))
        assertTrue(context.noBackupFilesDir.resolve("webdav-v2/1/recovery").walkTopDown().any { it.name == "manifest.json" })
    }

    @Test fun localRecoveryCanUndoReplacementWithoutNetwork(): Unit = runBlocking {
        backup()
        db.articleDao().insert(article.copy(title = "Before replacement"))
        cache.writeFullContent(1, article.id, "<p>Local full text</p>").getOrThrow()
        restore(1, RestoreMode.REPLACE)
        val copy = engine.recoveryCopies(1).single()
        val requests = server.requestCount
        engine.restoreRecovery(1, config, copy.id)
        assertEquals(requests, server.requestCount)
        assertEquals("Before replacement", articleFor(1).title)
        assertTrue(cache.readFullContent(articleFor(1).id, 1).getOrThrow().contains("Local full text"))
    }

    @Test fun historicalVersionCanBeSelectedAndRemoteChangesHaveRecoveryCopies(): Unit = runBlocking {
        backup()
        val oldest = engine.versions(1, config).single()
        db.articleDao().insert(article.copy(title = "Second version"))
        backup()
        engine.restore(1, config, RestoreMode.REPLACE, ConflictResolution.ASK, oldest.generation) { null }
        assertEquals("Original", articleFor(1).title)
        assertEquals("Second version", engine.recoveryCopies(1).single().let { copy ->
            engine.restoreRecovery(1, config, copy.id)
            articleFor(1).title
        })
    }

    @Test fun repeatedBackupReusesCompressedChunksAndRetainsHistory() = runBlocking {
        db.articleDao().insertList((1..600).map { article.copy(id = "large-$it", link = "https://example.org/$it", rawDescription = "content ".repeat(300)) })
        backup()
        val firstCount = chunkWrites.get()
        assertTrue(firstCount > 1)
        val compressed = resources.filterKeys { it.contains("/chunks/") }.values.sumOf { it.bytes.size }
        assertTrue("Compressed size was $compressed bytes", compressed * 4 < 600 * "content ".repeat(300).length)
        backup()
        assertEquals(firstCount, chunkWrites.get())
        assertEquals(2, engine.versions(1, config).size)
        assertEquals(2, engine.history(1).size)
        restore(2)
        assertEquals(601, db.articleDao().queryStateMetadata(2).size)
    }

    @Test fun localChangesDuringPublicationAreNeverOverwritten(): Unit = runBlocking {
        backup()
        restore(2)
        sync()
        sync(2)
        db.articleDao().markAsReadByArticleId(2, articleFor(2).id, false)
        sync(2)
        onManifestPut = { runBlocking { db.articleDao().markAsStarredByArticleId(1, article.id, true) } }
        val failure = runCatching { sync() }.exceptionOrNull()
        assertTrue(failure.toString(), failure?.message?.contains("Local articles changed") == true)
        assertTrue(articleFor(1).isStarred)
    }

    @Test fun stagingDetectsChangedRecordHashes(): Unit = runBlocking {
        val workspaceDir = File.createTempFile("backup-workspace", "dir", context.cacheDir).apply { delete(); mkdirs() }
        BackupWorkspace(workspaceDir).use { workspace ->
            val record = BackupRecord(key("group", "group"), "group", payload("name" to "Group"), 1)
            workspace.put("local", record)
            assertFalse(workspace.unchanged())
            workspace.put("fresh", record)
            assertTrue(workspace.unchanged())
            workspace.put("fresh", record.copy(hash = "changed"))
            assertFalse(workspace.unchanged())
        }
        workspaceDir.deleteRecursively()
    }
}
