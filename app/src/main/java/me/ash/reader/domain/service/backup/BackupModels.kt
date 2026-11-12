package me.ash.reader.domain.service.backup

import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.security.MessageDigest

internal val backupGson = Gson()
internal fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
internal fun digest(text: String) = digest(text.toByteArray(Charsets.UTF_8))
internal fun fileDigest(file: File): String {
    val hash = MessageDigest.getInstance("SHA-256")
    file.inputStream().use { input ->
        val buffer = ByteArray(32_768)
        while (true) { val size = input.read(buffer); if (size < 0) break; hash.update(buffer, 0, size) }
    }
    return hash.digest().joinToString("") { "%02x".format(it) }
}
internal fun recordHash(kind: String, deleted: Boolean, payload: JsonObject): String {
    // Optional cached copies do not change an article's semantic conflict revision.
    val semantic = if (kind == "article" && payload.has("cachedBody")) payload.deepCopy().apply { remove("cachedBody") } else payload
    return digest("$kind\u0000$deleted\u0000${backupGson.toJson(semantic)}")
}
internal fun key(kind: String, identity: String) = "$kind:${digest(identity)}"

internal data class BackupRecord(
    val key: String,
    val kind: String,
    val payload: JsonObject,
    val modifiedAt: Long,
    val deleted: Boolean = false,
    val hash: String = recordHash(kind, deleted, payload),
    @Transient val localId: String? = null,
) {
    fun identity(): JsonObject = JsonObject().apply {
        for (name in when (kind) { "article" -> listOf("feedKey", "feedUrl", "link", "title"); "feed" -> listOf("url", "title", "groupKey"); else -> listOf("name", "default") }) {
            payload[name]?.let { add(name, it) }
        }
    }
}
internal data class RecordStamp(val key: String, val kind: String, val hash: String, val modifiedAt: Long, val deleted: Boolean, val identity: JsonObject, val localId: String?)
internal data class BackupMetadata(val records: List<RecordStamp> = emptyList())
internal data class BackupChunk(val hash: String, val count: Int, val bytes: Long)
internal data class BackupReference(val generation: String, val exportedAt: Long, val checksum: String)
internal data class BackupManifest(
    val version: Int = 2,
    val namespaceId: String,
    val generation: String,
    val exportedAt: Long,
    val recordCount: Int,
    val chunks: List<BackupChunk>,
    val history: List<BackupReference> = emptyList(),
    val checksum: String = "",
) {
    fun signed() = copy(checksum = digest(backupGson.toJson(copy(checksum = ""))))
    fun verify(namespace: String) {
        require(version == 2) { "Unsupported WebDAV backup version: $version" }
        require(namespaceId == namespace) { "The backup belongs to a different remote account" }
        require(generation.matches(Regex("[0-9]{13}-[0-9a-f-]{36}"))) { "Invalid backup generation" }
        require(recordCount in 0..1_000_000 && exportedAt > 0) { "Invalid backup size or date" }
        require(chunks.size <= 20_000 && chunks.sumOf { it.count.toLong() } == recordCount.toLong()) { "Invalid backup chunk counts" }
        require(chunks.all { it.hash.matches(Regex("[a-f0-9]{64}")) && it.count in 1..256 && it.bytes in 1..67_108_864 }) { "Invalid backup chunk" }
        require(chunks.map { it.hash }.distinct().size == chunks.size) { "Duplicate backup chunks" }
        require(history.size <= 20 && history.all { it.generation.matches(Regex("[0-9]{13}-[0-9a-f-]{36}")) && it.checksum.matches(Regex("[a-f0-9]{64}")) }) { "Invalid backup history" }
        require(checksum == signed().checksum) { "WebDAV manifest checksum mismatch; the backup was preserved" }
    }
}

enum class ConflictResolution { ASK, KEEP_LOCAL, KEEP_REMOTE, NEWEST }
enum class RestoreMode { MERGE, REPLACE }
data class BackupConflict(val key: String, val kind: String, val title: String, val local: String, val remote: String)
class BackupConflictException(val conflicts: List<BackupConflict>, val total: Int) : IllegalStateException("$total WebDAV conflicts require a choice before syncing")
class BackupConcurrentWriteException : IllegalStateException("The remote backup changed during sync. Download it again and retry; no local data was replaced.")
data class BackupProgress(val stage: String, val completed: Int = 0, val total: Int = 0)
data class BackupHistoryEntry(val time: Long, val action: String, val success: Boolean, val detail: String?, val generation: String? = null)
data class BackupVersion(val generation: String, val time: Long)
data class BackupRecoveryCopy(val id: String, val time: Long)

internal fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString
internal fun JsonObject.long(name: String): Long = string(name)?.toLongOrNull() ?: 0L
internal fun JsonObject.bool(name: String, default: Boolean = false): Boolean = get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: default
internal fun payload(vararg values: Pair<String, Any?>): JsonObject = backupGson.toJsonTree(linkedMapOf(*values)).asJsonObject
