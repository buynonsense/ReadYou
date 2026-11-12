package me.ash.reader.domain.service.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.Closeable
import java.io.File

/** Bodies are staged on disk. Only one record is decoded at a time. */
internal class BackupWorkspace(val directory: File) : Closeable {
    private val database: SQLiteDatabase
    init {
        check(directory.isDirectory || directory.mkdirs())
        database = SQLiteDatabase.openOrCreateDatabase(directory.resolve("staging.db"), null)
        for (table in listOf("local", "remote", "merged", "fresh")) database.execSQL("""CREATE TABLE $table (
            recordKey TEXT PRIMARY KEY NOT NULL, kind TEXT NOT NULL, payload TEXT NOT NULL,
            hash TEXT NOT NULL, modifiedAt INTEGER NOT NULL, deleted INTEGER NOT NULL, localId TEXT
        )""")
    }
    private fun table(value: String): String = value.also { require(it in listOf("local", "remote", "merged", "fresh")) }
    fun put(table: String, record: BackupRecord, rejectDuplicate: Boolean = false) {
        val values = ContentValues().apply {
            put("recordKey", record.key); put("kind", record.kind); put("payload", backupGson.toJson(record.payload))
            put("hash", record.hash); put("modifiedAt", record.modifiedAt); put("deleted", if (record.deleted) 1 else 0); put("localId", record.localId)
        }
        database.insertWithOnConflict(table(table), null, values, if (rejectDuplicate) SQLiteDatabase.CONFLICT_ABORT else SQLiteDatabase.CONFLICT_REPLACE)
            .also { check(it != -1L) { "Could not stage backup record" } }
    }
    fun get(table: String, key: String): BackupRecord? = database.rawQuery("SELECT * FROM ${table(table)} WHERE recordKey = ?", arrayOf(key))
        .use { if (it.moveToFirst()) read(it) else null }
    fun records(table: String, kind: String? = null): Sequence<BackupRecord> = sequence {
        val filter = if (kind == null) "" else "WHERE kind = ?"
        database.rawQuery("SELECT * FROM ${table(table)} $filter ORDER BY substr(recordKey, instr(recordKey, ':') + 1, 1), recordKey", if (kind == null) null else arrayOf(kind)).use { cursor ->
            while (cursor.moveToNext()) yield(read(cursor))
        }
    }
    fun keys(): Sequence<String> = sequence {
        database.rawQuery("SELECT recordKey FROM local UNION SELECT recordKey FROM remote ORDER BY recordKey", null).use { cursor ->
            while (cursor.moveToNext()) yield(cursor.getString(0))
        }
    }
    fun keys(table: String, kind: String): List<String> = database.rawQuery(
        "SELECT recordKey FROM ${table(table)} WHERE kind = ? ORDER BY recordKey", arrayOf(kind),
    ).use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
    fun count(table: String): Int = database.rawQuery("SELECT COUNT(*) FROM ${table(table)}", null).use { it.moveToFirst(); it.getInt(0) }
    fun copy(from: String, to: String) { database.execSQL("INSERT OR REPLACE INTO ${table(to)} SELECT * FROM ${table(from)}") }
    fun remove(table: String, key: String) { database.delete(table(table), "recordKey = ?", arrayOf(key)) }
    fun unchanged(): Boolean = !differs("local", "fresh")
    fun differs(first: String, second: String): Boolean = database.rawQuery("""SELECT
        EXISTS(SELECT recordKey, hash FROM ${table(first)} EXCEPT SELECT recordKey, hash FROM ${table(second)})
        OR EXISTS(SELECT recordKey, hash FROM ${table(second)} EXCEPT SELECT recordKey, hash FROM ${table(first)})
    """, null).use { it.moveToFirst(); it.getInt(0) != 0 }
    private fun read(cursor: Cursor) = BackupRecord(
        cursor.getString(cursor.getColumnIndexOrThrow("recordKey")), cursor.getString(cursor.getColumnIndexOrThrow("kind")),
        backupGson.fromJson(cursor.getString(cursor.getColumnIndexOrThrow("payload")), com.google.gson.JsonObject::class.java),
        cursor.getLong(cursor.getColumnIndexOrThrow("modifiedAt")), cursor.getInt(cursor.getColumnIndexOrThrow("deleted")) == 1,
        cursor.getString(cursor.getColumnIndexOrThrow("hash")), cursor.getString(cursor.getColumnIndexOrThrow("localId")),
    )
    override fun close() { database.close() }
}
