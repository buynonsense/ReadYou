package me.ash.reader.infrastructure.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import me.ash.reader.domain.model.account.*
import me.ash.reader.domain.model.account.security.DESUtils
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleSearch
import me.ash.reader.domain.model.article.PendingArticleState
import me.ash.reader.domain.model.article.WebDavArticleState
import me.ash.reader.domain.repository.PendingArticleStateDao
import me.ash.reader.domain.repository.ArticleSearchDao
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.preference.*
import me.ash.reader.ui.ext.toInt
import java.util.*

@Database(
    entities = [Account::class, Feed::class, Article::class, Group::class, ArchivedArticle::class, ArticleSearch::class, PendingArticleState::class, WebDavArticleState::class],
    version = 11,
    autoMigrations = [
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 5, to = 7),
        AutoMigration(from = 6, to = 7),
    ]
)
@TypeConverters(
    AndroidDatabase.DateConverters::class,
    AccountTypeConverters::class,
    SyncIntervalConverters::class,
    SyncOnStartConverters::class,
    SyncOnlyOnWiFiConverters::class,
    SyncOnlyWhenChargingConverters::class,
    KeepArchivedConverters::class,
    SyncBlockListConverters::class,
)
abstract class AndroidDatabase : RoomDatabase() {

    abstract fun accountDao(): AccountDao
    abstract fun feedDao(): FeedDao
    abstract fun articleDao(): ArticleDao
    abstract fun groupDao(): GroupDao
    abstract fun articleSearchDao(): ArticleSearchDao
    abstract fun pendingArticleStateDao(): PendingArticleStateDao

    companion object {

        private var instance: AndroidDatabase? = null

        fun getInstance(context: Context): AndroidDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    AndroidDatabase::class.java,
                    "Reader"
                ).addMigrations(*allMigrations).addCallback(ARTICLE_SEARCH_CALLBACK).build().also {
                    instance = it
                }
            }
        }
    }

    class DateConverters {

        @TypeConverter
        fun toDate(dateLong: Long?): Date? {
            return dateLong?.let { Date(it) }
        }

        @TypeConverter
        fun fromDate(date: Date?): Long? {
            return date?.time
        }
    }
}

val allMigrations = arrayOf(
    MIGRATION_1_2,
    MIGRATION_2_3,
    MIGRATION_3_4,
    MIGRATION_4_5,
    MIGRATION_7_8,
    MIGRATION_8_9,
    MIGRATION_9_10,
    MIGRATION_10_11,
)

object MIGRATION_10_11 : Migration(10, 11) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS webdav_article_state (
            accountId INTEGER NOT NULL, feedUrl TEXT NOT NULL, link TEXT NOT NULL,
            isUnread INTEGER NOT NULL, isStarred INTEGER NOT NULL, isReadLater INTEGER NOT NULL,
            PRIMARY KEY(accountId, feedUrl, link)
        )""")
    }
}

object MIGRATION_9_10 : Migration(9, 10) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS pending_article_state (
            accountId INTEGER NOT NULL, articleId TEXT NOT NULL, field TEXT NOT NULL,
            value INTEGER NOT NULL, revision TEXT NOT NULL, changedAt INTEGER NOT NULL,
            PRIMARY KEY(accountId, articleId, field),
            FOREIGN KEY(articleId) REFERENCES article(id) ON UPDATE NO ACTION ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS index_pending_article_state_articleId ON pending_article_state(articleId)")
    }
}

val ARTICLE_SEARCH_CALLBACK = object : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) { createArticleSearchTriggers(db) }
}

private fun createArticleSearchTriggers(db: SupportSQLiteDatabase) {
    db.execSQL("""CREATE TRIGGER IF NOT EXISTS readyou_search_insert AFTER INSERT ON article BEGIN
        INSERT OR REPLACE INTO article_search(rowid, title, summary, body, cachedBody)
        VALUES (NEW.rowid, NEW.title, NEW.shortDescription, NEW.rawDescription, COALESCE(NEW.fullContent, ''));
        END""")
    db.execSQL("""CREATE TRIGGER IF NOT EXISTS readyou_search_update AFTER UPDATE OF title, shortDescription, rawDescription, fullContent ON article BEGIN
        UPDATE article_search SET title = NEW.title, summary = NEW.shortDescription, body = NEW.rawDescription
        WHERE rowid = OLD.rowid;
        END""")
    db.execSQL("""CREATE TRIGGER IF NOT EXISTS readyou_search_delete AFTER DELETE ON article BEGIN
        DELETE FROM article_search WHERE rowid = OLD.rowid;
        END""")
}

object MIGRATION_8_9 : Migration(8, 9) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("CREATE VIRTUAL TABLE IF NOT EXISTS article_search USING FTS4(title TEXT NOT NULL, summary TEXT NOT NULL, body TEXT NOT NULL, cachedBody TEXT NOT NULL, tokenize=unicode61)")
        database.execSQL("INSERT INTO article_search(rowid, title, summary, body, cachedBody) SELECT rowid, title, shortDescription, rawDescription, COALESCE(fullContent, '') FROM article")
        createArticleSearchTriggers(database)
    }
}

object MIGRATION_7_8 : Migration(7, 8) {
    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL("ALTER TABLE archived_article ADD COLUMN archivedAt INTEGER NOT NULL DEFAULT 0")
        database.execSQL("DELETE FROM archived_article WHERE id NOT IN (SELECT MAX(id) FROM archived_article GROUP BY feedId, link)")
        database.execSQL("CREATE UNIQUE INDEX index_archived_article_feedId_link ON archived_article(feedId, link)")
        database.execSQL("CREATE INDEX index_article_accountId_date ON article(accountId, date)")
        database.execSQL("CREATE INDEX index_article_feedId_link ON article(feedId, link)")
    }
}

@Suppress("ClassName")
object MIGRATION_1_2 : Migration(1, 2) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE article ADD COLUMN img TEXT DEFAULT NULL
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_2_3 : Migration(2, 3) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE article ADD COLUMN updateAt INTEGER DEFAULT ${System.currentTimeMillis()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncInterval INTEGER NOT NULL DEFAULT ${SyncIntervalPreference.default.value}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnStart INTEGER NOT NULL DEFAULT ${SyncOnStartPreference.default.value.toInt()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnlyOnWiFi INTEGER NOT NULL DEFAULT ${SyncOnlyOnWiFiPreference.default.value.toInt()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncOnlyWhenCharging INTEGER NOT NULL DEFAULT ${SyncOnlyWhenChargingPreference.default.value.toInt()}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN keepArchived INTEGER NOT NULL DEFAULT ${KeepArchivedPreference.default.value}
            """.trimIndent()
        )
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN syncBlockList TEXT NOT NULL DEFAULT ''
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_3_4 : Migration(3, 4) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN securityKey TEXT DEFAULT '${DESUtils.empty}'
            """.trimIndent()
        )
    }
}

@Suppress("ClassName")
object MIGRATION_4_5 : Migration(4, 5) {

    override fun migrate(database: SupportSQLiteDatabase) {
        database.execSQL(
            """
            ALTER TABLE account ADD COLUMN lastArticleId TEXT DEFAULT NULL
            """.trimIndent()
        )
    }
}
