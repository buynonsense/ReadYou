package me.ash.reader.domain.service.backup

import android.app.Application
import androidx.room.Room
import java.io.File
import java.util.Date
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
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
    private val server = MockWebServer()
    private lateinit var db: AndroidDatabase
    private lateinit var cache: ReaderCacheHelper
    private lateinit var engine: WebDavBackupEngine
    private lateinit var config: WebDavConfig
    private val context get() = RuntimeEnvironment.getApplication()
    private val article = Article("article-1", Date(1_000), "Original", rawDescription = "<p>Body</p>", shortDescription = "Body", link = "https://example.org/a", feedId = "feed-1", accountId = 1, updateAt = Date(1_000))

    @Before fun setup() = runBlocking {
        context.noBackupFilesDir.resolve("webdav-v2").deleteRecursively()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = synchronized(resources) {
                val path = request.requestUrl!!.encodedPath
                when (request.method) {
                    "GET" -> resources[path]?.let { MockResponse().setResponseCode(200).setHeader("ETag", it.etag).setBody(Buffer().write(it.bytes)) } ?: MockResponse().setResponseCode(404)
                    "MKCOL" -> MockResponse().setResponseCode(201)
                    "PUT" -> {
                        if (path.endsWith("readyou-manifest-v2.json")) onManifestPut?.also { onManifestPut = null }?.invoke()
                        if (path.endsWith("readyou-manifest-v2.json") && raceOnManifest) {
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
                            if (path.endsWith("readyou-manifest-v2.json")) manifestWrites.incrementAndGet()
                            MockResponse().setResponseCode(201).setHeader("ETag", resource.etag)
                        }
                    }
                    else -> MockResponse().setResponseCode(405)
                }
            }
        }
        config = WebDavConfig(serverUrl = server.url("dav").toString(), namespaceId = "paired")
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

    @Test fun cleanupTombstonesPropagateWithoutResurrectingArticles() = runBlocking {
        backup()
        restore(2)
        sync(2)
        db.articleDao().archiveAndDelete(1, listOf(ArticleLinkMeta(article.id, article.feedId, article.link)))
        sync()
        sync(2)
        assertTrue(db.articleDao().queryStateMetadata(1).isEmpty())
        assertTrue(db.articleDao().queryStateMetadata(2).isEmpty())
        val feed = db.feedDao().queryAll(2).single()
        assertEquals(article.link, db.feedDao().queryArchivedArticles(feed.id).single().link)
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
        db.articleDao().insert(articleFor(2).copy(title = "Remote"))
        backup(2)
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
        assertTrue(compressed < 100_000)
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
        sync(2)
        db.articleDao().insert(articleFor(2).copy(title = "Remote"))
        sync(2)
        onManifestPut = { runBlocking { db.articleDao().insert(article.copy(title = "Edited while uploading")) } }
        val failure = runCatching { sync() }.exceptionOrNull()
        assertTrue(failure.toString(), failure?.message?.contains("Local articles changed") == true)
        assertEquals("Edited while uploading", articleFor(1).title)
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
