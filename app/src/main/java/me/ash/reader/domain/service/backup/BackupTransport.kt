package me.ash.reader.domain.service.backup

import com.google.gson.JsonObject
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import me.ash.reader.domain.service.WebDavSyncService.WebDavConfig
import me.ash.reader.infrastructure.net.WebDavClient

internal data class DownloadedManifest(val manifest: BackupManifest, val etag: String?)

internal class BackupTransport(private val client: WebDavClient, private val config: WebDavConfig) {
    val root: String = config.directoryUrl()
    private val manifestUrl get() = "$root/readyou-manifest-v2.json"

    fun current(directory: File): DownloadedManifest? = readManifest(manifestUrl, directory)
    fun version(reference: BackupReference, directory: File): DownloadedManifest {
        val downloaded = requireNotNull(readManifest("$root/snapshots/${reference.generation}.json", directory)) { "Historical backup was not found" }
        check(downloaded.manifest.checksum == reference.checksum) { "Historical manifest checksum mismatch" }
        return downloaded
    }
    private fun readManifest(url: String, directory: File): DownloadedManifest? = client.get(url, config.username, config.password).use { response ->
        if (response.code == 404) return@use null
        check(response.isSuccessful) { "WebDAV download failed: HTTP ${response.code}" }
        val file = File.createTempFile("manifest-", ".json", directory)
        try {
            requireNotNull(response.body).byteStream().use { input -> file.outputStream().use { copyLimited(input, it, 4_194_304) } }
            val json = file.reader().use { com.google.gson.JsonParser.parseReader(it) }
            require(json.isJsonObject) { "Invalid WebDAV manifest" }
            val objectValue = json.asJsonObject
            require(objectValue.has("version")) { "Missing WebDAV backup version" }
            require(objectValue["version"].asInt == 2) { "Unsupported WebDAV backup version: ${objectValue["version"]}" }
            val manifest = backupGson.fromJson(json, BackupManifest::class.java)
            manifest.verify(config.namespaceId)
            DownloadedManifest(manifest, response.header("ETag"))
        } finally { file.delete() }
    }

    suspend fun download(manifest: BackupManifest, workspace: BackupWorkspace, localDirectory: File? = null, progress: (Int, Int) -> Unit) {
        manifest.chunks.forEachIndexed { index, chunk ->
            currentCoroutineContext().ensureActive()
            val file = workspace.directory.resolve("${chunk.hash}.json.gz")
            if (localDirectory != null) {
                localDirectory.resolve(file.name).inputStream().use { input -> file.outputStream().use { copyLimited(input, it, 67_108_864) } }
            } else client.get("$root/chunks/${file.name}", config.username, config.password).use { response ->
                check(response.isSuccessful) { "Missing or unreadable backup chunk: HTTP ${response.code}" }
                requireNotNull(response.body).byteStream().use { input -> file.outputStream().use { copyLimited(input, it, 67_108_864) } }
            }
            check(file.length() == chunk.bytes && fileDigest(file) == chunk.hash) { "WebDAV chunk checksum mismatch; no data was restored" }
            var count = 0
            JsonReader(LimitedInputStream(GZIPInputStream(file.inputStream()), 67_108_864).reader()).use { reader ->
                reader.isLenient = false
                reader.beginArray()
                while (reader.hasNext()) {
                    currentCoroutineContext().ensureActive()
                    val record = backupGson.fromJson<BackupRecord>(reader, BackupRecord::class.java)
                    validate(record)
                    workspace.put("remote", record, rejectDuplicate = true)
                    count++
                    check(count <= chunk.count) { "Unexpected records in WebDAV chunk" }
                }
                reader.endArray()
                check(reader.peek() == JsonToken.END_DOCUMENT) { "Trailing data in backup chunk" }
            }
            check(count == chunk.count) { "Missing records in WebDAV chunk" }
            progress(index + 1, manifest.chunks.size)
        }
        check(workspace.count("remote") == manifest.recordCount) { "Incomplete WebDAV backup" }
    }

    fun writeChunks(workspace: BackupWorkspace, table: String): List<BackupChunk> {
        val chunks = mutableListOf<BackupChunk>()
        var writer: JsonWriter? = null
        var file: File? = null
        var count = 0
        var bytes = 0L
        var bucket = ""
        fun finish() {
            val active = writer ?: return
            active.endArray(); active.close(); writer = null
            val temporary = requireNotNull(file)
            val hash = fileDigest(temporary)
            val target = workspace.directory.resolve("$hash.json.gz")
            if (target.exists()) temporary.delete() else check(temporary.renameTo(target))
            chunks += BackupChunk(hash, count, target.length())
            count = 0; bytes = 0
        }
        try {
            for (record in workspace.records(table)) {
                val recordBucket = record.key.substringAfter(':').take(1)
                val json = backupGson.toJson(record)
                check(json.length <= 16_777_216) { "An article is too large to back up" }
                if (count > 0 && (bucket != recordBucket || count >= 256 || bytes + json.length > 1_048_576)) finish()
                if (writer == null) {
                    file = File.createTempFile("chunk-", ".tmp", workspace.directory)
                    writer = JsonWriter(GZIPOutputStream(file!!.outputStream()).writer())
                    writer!!.beginArray()
                    bucket = recordBucket
                }
                backupGson.toJson(record, BackupRecord::class.java, writer)
                bytes += json.length; count++
            }
            finish()
        } finally { writer?.close() }
        return chunks
    }

    suspend fun publish(workspace: BackupWorkspace, previous: DownloadedManifest?, chunks: List<BackupChunk>, progress: (Int, Int) -> Unit): BackupManifest {
        val etag = previous?.etag
        require(previous == null || (etag != null && etag.startsWith('"') && etag.endsWith('"') && !etag.startsWith("W/"))) {
            "This WebDAV server does not provide a strong ETag. Safe concurrent publication is unavailable; the backup was preserved."
        }
        val existing = previous?.manifest?.chunks?.map { it.hash }?.toSet().orEmpty()
        chunks.forEachIndexed { index, chunk ->
            currentCoroutineContext().ensureActive()
            if (chunk.hash !in existing) {
                val file = workspace.directory.resolve("${chunk.hash}.json.gz")
                client.putFile("$root/chunks/${file.name}", config.username, config.password, file, ifAbsent = true).use { response ->
                    if (response.code == 412) {
                        val verify = workspace.directory.resolve("verify-${file.name}")
                        try {
                            client.get("$root/chunks/${file.name}", config.username, config.password).use { downloaded ->
                                check(downloaded.isSuccessful) { "Could not verify an existing backup chunk" }
                                requireNotNull(downloaded.body).byteStream().use { input -> verify.outputStream().use { copyLimited(input, it, 67_108_864) } }
                            }
                            check(fileDigest(verify) == chunk.hash) { "An existing backup chunk is damaged" }
                        } finally { verify.delete() }
                    } else check(response.isSuccessful) { "WebDAV chunk upload failed: HTTP ${response.code}" }
                }
            }
            progress(index + 1, chunks.size)
        }
        val history = previous?.manifest?.let { listOf(BackupReference(it.generation, it.exportedAt, it.checksum)) + it.history }.orEmpty().take(20)
        val now = System.currentTimeMillis()
        val manifest = BackupManifest(namespaceId = config.namespaceId, generation = "$now-${UUID.randomUUID()}", exportedAt = now,
            recordCount = chunks.sumOf { it.count }, chunks = chunks, history = history).signed()
        val json = backupGson.toJson(manifest).toByteArray()
        currentCoroutineContext().ensureActive()
        client.putConditional("$root/snapshots/${manifest.generation}.json", config.username, config.password, json, ifAbsent = true).use {
            check(it.isSuccessful) { "Could not preserve the backup generation: HTTP ${it.code}" }
        }
        currentCoroutineContext().ensureActive()
        // The small pointer is the only mutable remote object. Conditional PUT is an atomic CAS.
        client.putConditional(manifestUrl, config.username, config.password, json, ifMatch = etag, ifAbsent = previous == null).use {
            if (it.code == 412 || it.code == 409) throw BackupConcurrentWriteException()
            check(it.isSuccessful) { "WebDAV manifest publication failed: HTTP ${it.code}" }
        }
        return manifest
    }

    private fun validate(record: BackupRecord) {
        require(record.kind in listOf("group", "feed", "article") && record.key.matches(Regex("${record.kind}:[a-f0-9]{64}"))) { "Invalid backup record identity" }
        require(record.modifiedAt >= 0 && record.hash == recordHash(record.kind, record.deleted, record.payload)) { "Invalid backup record checksum" }
        when (record.kind) {
            "group" -> require(record.payload.has("name")) { "Invalid backup group" }
            "feed" -> require(record.payload.has("url") && record.payload.has("groupKey")) { "Invalid backup feed" }
            "article" -> require(record.payload.has("link") && record.payload.has("feedKey") && record.payload.has("feedUrl")) { "Invalid backup article" }
        }
    }
}

internal fun copyLimited(input: InputStream, output: OutputStream, limit: Long) {
    val buffer = ByteArray(32_768)
    var total = 0L
    while (true) {
        val size = input.read(buffer)
        if (size < 0) break
        total += size
        check(total <= limit) { "WebDAV resource exceeds its size limit" }
        output.write(buffer, 0, size)
    }
}
private class LimitedInputStream(input: InputStream, private val limit: Long) : FilterInputStream(input) {
    private var count = 0L
    override fun read(): Int = super.read().also { if (it != -1) check(++count <= limit) { "Expanded WebDAV chunk is too large" } }
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = `in`.read(buffer, offset, length).also {
        if (it > 0) { count += it; check(count <= limit) { "Expanded WebDAV chunk is too large" } }
    }
}
