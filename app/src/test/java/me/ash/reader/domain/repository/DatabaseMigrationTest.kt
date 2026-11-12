package me.ash.reader.domain.repository

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.db.allMigrations
import me.ash.reader.domain.model.article.WebDavArticleState
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class DatabaseMigrationTest {
    @Test fun versionTenUpgradePreservesArticleContentAndPendingStates() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-webdav-test"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("me.ash.reader.infrastructure.db.AndroidDatabase/10.json")!!
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("database") }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (entity in schema.getAsJsonArray("entities")) {
                val entry = entity.asJsonObject
                val table = entry["tableName"].asString
                old.execSQL(entry["createSql"].asString.replace("\${TABLE_NAME}", table))
                for (index in entry.getAsJsonArray("indices") ?: com.google.gson.JsonArray()) {
                    old.execSQL(index.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", table))
                }
            }
            old.execSQL("INSERT INTO `group` (id, name, accountId) VALUES ('group', 'Group', 1)")
            old.execSQL("INSERT INTO feed (id, name, url, groupId, accountId, isNotification, isFullContent, isBrowser) VALUES ('feed', 'Feed', 'https://example.org/rss', 'group', 1, 0, 0, 0)")
            old.execSQL("INSERT INTO article (id, date, title, rawDescription, shortDescription, link, feedId, accountId, isUnread, isStarred, isReadLater) VALUES ('article', 1000, 'Title', 'RSS body', 'Summary', 'https://example.org/a', 'feed', 1, 0, 1, 1)")
            old.execSQL("INSERT INTO pending_article_state (accountId, articleId, field, value, revision, changedAt) VALUES (1, 'article', 'starred', 1, 'revision', 1000)")
            old.version = 10
        }
        val migrated = Room.databaseBuilder(context, AndroidDatabase::class.java, name)
            .addMigrations(*allMigrations).allowMainThreadQueries().build()
        try {
            val article = migrated.articleDao().queryById("article")!!.article
            assertEquals("RSS body", article.rawDescription)
            assertFalse(article.isUnread)
            assertTrue(article.isStarred)
            assertTrue(article.isReadLater)
            assertEquals("revision", migrated.pendingArticleStateDao().pending(1).single().revision)
            assertTrue(migrated.articleDao().queryWebDavStates(1).isEmpty())
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }

    @Test fun versionSevenUpgradePreservesDataAndDeduplicatesArchive() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "migration-test"
        context.deleteDatabase(name)
        val path = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val schema = javaClass.classLoader!!.getResourceAsStream("me.ash.reader.infrastructure.db.AndroidDatabase/7.json")!!
            .bufferedReader().use { JsonParser.parseReader(it).asJsonObject.getAsJsonObject("database") }
        SQLiteDatabase.openOrCreateDatabase(path, null).use { old ->
            for (entity in schema.getAsJsonArray("entities")) {
                val entry = entity.asJsonObject
                val table = entry["tableName"].asString
                old.execSQL(entry["createSql"].asString.replace("\${TABLE_NAME}", table))
                for (index in entry.getAsJsonArray("indices") ?: com.google.gson.JsonArray()) {
                    old.execSQL(index.asJsonObject["createSql"].asString.replace("\${TABLE_NAME}", table))
                }
            }
            old.execSQL("INSERT INTO `group` (id, name, accountId) VALUES ('group', 'Group', 1)")
            old.execSQL("INSERT INTO feed (id, name, url, groupId, accountId, isNotification, isFullContent, isBrowser) VALUES ('feed', 'Feed', 'https://example.org/rss', 'group', 1, 0, 0, 0)")
            old.execSQL("INSERT INTO archived_article (feedId, link) VALUES ('feed', 'https://example.org/a'), ('feed', 'https://example.org/a')")
            old.version = 7
        }
        val migrated = Room.databaseBuilder(context, AndroidDatabase::class.java, name)
            .addMigrations(*allMigrations).allowMainThreadQueries().build()
        try {
            assertEquals("Feed", migrated.feedDao().queryById("feed")!!.name)
            assertEquals(1, migrated.feedDao().queryArchivedArticles("feed").size)
            val state = WebDavArticleState(1, "https://example.org/rss", "https://example.org/a", false, true, true)
            migrated.articleDao().saveWebDavStates(listOf(state))
            assertEquals(state, migrated.articleDao().queryWebDavStates(1).single())
            assertEquals(0L, migrated.feedDao().queryArchivedArticles("feed").single().archivedAt)
            migrated.openHelper.writableDatabase.execSQL("INSERT OR IGNORE INTO archived_article (feedId, link, archivedAt) VALUES ('feed', 'https://example.org/a', 10)")
            assertEquals(1, migrated.feedDao().queryArchivedArticles("feed").size)
        } finally {
            migrated.close()
            context.deleteDatabase(name)
        }
    }
}
