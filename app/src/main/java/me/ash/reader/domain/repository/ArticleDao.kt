package me.ash.reader.domain.repository

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.MapColumn
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RewriteQueriesToDropUnusedColumns
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.article.ArticleLinkMeta
import me.ash.reader.domain.model.article.ArticleMeta
import me.ash.reader.domain.model.article.ArticleSnapshot
import me.ash.reader.domain.model.article.ArticleSyncMeta
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.feed.ImportantNum
import java.util.Date

@Dao
interface ArticleDao {
    @Query("SELECT COUNT(*) FROM article WHERE accountId = :accountId AND feedId = :feedId AND (isStarred = 1 OR isReadLater = 1)")
    suspend fun countProtectedByFeed(accountId: Int, feedId: String): Int

    @Query("SELECT COUNT(*) FROM article a JOIN feed f ON f.id = a.feedId WHERE a.accountId = :accountId AND f.groupId = :groupId AND (a.isStarred = 1 OR a.isReadLater = 1)")
    suspend fun countProtectedByGroup(accountId: Int, groupId: String): Int

    @Query("SELECT id, feedId, link, isUnread, isStarred, isReadLater FROM article WHERE accountId = :accountId")
    suspend fun queryStateMetadata(accountId: Int): List<ArticleSyncMeta>

    @Query("SELECT EXISTS(SELECT 1 FROM article WHERE id = :articleId AND accountId = :accountId)")
    suspend fun contains(accountId: Int, articleId: String): Boolean

    @Query("""SELECT id FROM article WHERE accountId = :accountId
        AND (:groupId IS NULL OR feedId IN (SELECT id FROM feed WHERE groupId = :groupId AND accountId = :accountId))
        AND (:feedId IS NULL OR feedId = :feedId)
        AND (:articleId IS NULL OR id = :articleId)
        AND date < :before AND isUnread != :isUnread ORDER BY id
    """)
    suspend fun stateTargets(accountId: Int, groupId: String?, feedId: String?, articleId: String?, before: Date, isUnread: Boolean): List<String>

    @Query(
        """
        UPDATE article SET isStarred = :isStarred 
        WHERE accountId = :accountId
        AND id in (:ids)
        """
    )
    fun markAsStarredByIdSet(
        accountId: Int,
        ids: Set<String>,
        isStarred: Boolean,
    ): Int

    @Query(
        """
        UPDATE article SET isUnread = :isUnread 
        WHERE accountId = :accountId
        AND id in (:ids)
        """
    )
    fun markAsReadByIdSet(
        accountId: Int,
        ids: Set<String>,
        isUnread: Boolean,
    ): Int

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT count(1)
        FROM article
        WHERE feedId = :feedId
        AND isStarred = :isStarred
        AND accountId = :accountId
        """
    )
    fun countByFeedIdWhenIsStarred(
        accountId: Int,
        feedId: String,
        isStarred: Boolean,
    ): Int

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT count(1)
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.isStarred = :isStarred
        AND a.accountId = :accountId
        """
    )
    fun countByGroupIdWhenIsStarred(
        accountId: Int,
        groupId: String,
        isStarred: Boolean,
    ): Int


    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND (:feedId IS NULL OR feedId = :feedId)
        AND (:groupId IS NULL OR feedId IN (SELECT id FROM feed WHERE groupId = :groupId AND accountId = :accountId))
        AND (:isUnread = 0 OR isUnread = 1)
        AND (:isStarred = 0 OR isStarred = 1)
        AND (
            rowid IN (SELECT rowid FROM article_search WHERE article_search MATCH :query)
            OR (:useSubstring = 1 AND rowid IN (
                SELECT rowid FROM article_search
                WHERE instr(title, :literal) > 0 OR instr(summary, :literal) > 0
                    OR instr(body, :literal) > 0 OR instr(cachedBody, :literal) > 0
            ))
        )
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC,
            id ASC
        """
    )
    fun searchArticles(
        accountId: Int,
        query: String,
        literal: String,
        useSubstring: Boolean,
        feedId: String? = null,
        groupId: String? = null,
        isUnread: Boolean = false,
        isStarred: Boolean = false,
        sortAscending: Boolean = false,
    ): PagingSource<Int, ArticleWithFeed>

    @Query(
        """
        DELETE FROM article
        WHERE accountId = :accountId
        AND updateAt < :before
        AND isUnread = 0
        AND isStarred = 0
        """
    )
    suspend fun deleteAllArchivedBeforeThan(
        accountId: Int,
        before: Date,
    )

    @Query(
        """
        select * FROM article
        WHERE accountId = :accountId
        AND updateAt < :before
        AND isUnread = 0
        AND isStarred = 0
        """
    )
    suspend fun queryArchivedArticleBefore(
        accountId: Int,
        before: Date,
    ): List<Article>

    @Transaction
    @Query(
        """
        UPDATE article SET isUnread = :isUnread 
        WHERE accountId = :accountId
        AND date < :before
        AND isUnread != :isUnread
        """
    )
    suspend fun markAllAsRead(
        accountId: Int,
        isUnread: Boolean,
        before: Date,
    )

    @Transaction
    @Query(
        """
        UPDATE article SET isUnread = :isUnread 
        WHERE feedId IN (
            SELECT id FROM feed 
            WHERE groupId = :groupId
        )
        AND accountId = :accountId
        AND isUnread != :isUnread
        AND date < :before
        """
    )
    suspend fun markAllAsReadByGroupId(
        accountId: Int,
        groupId: String,
        isUnread: Boolean,
        before: Date,
    )

    @Transaction
    @Query(
        """
        UPDATE article SET isUnread = :isUnread 
        WHERE feedId = :feedId
        AND accountId = :accountId
        AND isUnread != :isUnread
        AND date < :before
        """
    )
    suspend fun markAllAsReadByFeedId(
        accountId: Int,
        feedId: String,
        isUnread: Boolean,
        before: Date,
    )

    @Transaction
    @Query(
        """
        UPDATE article SET isUnread = :isUnread 
        WHERE id = :articleId
        AND accountId = :accountId
        """
    )
    suspend fun markAsReadByArticleId(
        accountId: Int,
        articleId: String,
        isUnread: Boolean,
    )

    @Query(
        """
        UPDATE article SET isStarred = :isStarred 
        WHERE id = :articleId
        AND accountId = :accountId
        """
    )
    suspend fun markAsStarredByArticleId(
        accountId: Int,
        articleId: String,
        isStarred: Boolean,
    )

    @Query(
        """
        DELETE FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        AND (isStarred = :includeStarred OR :includeStarred = 1)
        """
    )
    suspend fun deleteByFeedId(accountId: Int, feedId: String, includeStarred: Boolean = false)

    @Query(
        """
        DELETE FROM article
        WHERE id IN (
            SELECT a.id FROM article AS a, feed AS b, `group` AS c
            WHERE a.accountId = :accountId
            AND a.feedId = b.id
            AND b.groupId = c.id
            AND c.id = :groupId
            AND (a.isStarred = :includeStarred OR :includeStarred = 1)
        )
        """
    )
    suspend fun deleteByGroupId(accountId: Int, groupId: String, includeStarred: Boolean = false)

    @Query(
        """
        DELETE FROM article
        WHERE accountId = :accountId
        """
    )
    suspend fun deleteByAccountId(accountId: Int)

    @Query(
        """
        SELECT COUNT(*) FROM article
        WHERE accountId = :accountId
        AND date < :before
        AND (isUnread = 0 OR :includeUnread = 1)
        AND (isStarred = 0 OR :keepStarred = 0)
        AND isReadLater = 0
        """
    )
    suspend fun countArticlesBefore(
        accountId: Int,
        before: Date,
        includeUnread: Boolean = false,
        keepStarred: Boolean = true,
    ): Int

    @Query(
        """
        SELECT id, feedId, link FROM article
        WHERE accountId = :accountId
        AND date < :before
        AND (isUnread = 0 OR :includeUnread = 1)
        AND (isStarred = 0 OR :keepStarred = 0)
        AND isReadLater = 0
        """
    )
    suspend fun queryArticleIdentitiesBefore(
        accountId: Int,
        before: Date,
        includeUnread: Boolean = false,
        keepStarred: Boolean = true,
    ): List<ArticleLinkMeta>

    @Query(
        """
        DELETE FROM article
        WHERE accountId = :accountId
        AND date < :before
        AND (isUnread = 0 OR :includeUnread = 1)
        AND (isStarred = 0 OR :keepStarred = 0)
        AND isReadLater = 0
        """
    )
    suspend fun deleteArticlesBefore(
        accountId: Int,
        before: Date,
        includeUnread: Boolean = false,
        keepStarred: Boolean = true,
    ): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertArchivedIdentities(identities: List<ArchivedArticle>)

    @Query("DELETE FROM article WHERE accountId = :accountId AND id IN (:ids)")
    suspend fun deleteByIds(accountId: Int, ids: List<String>): Int

    @Query("""
        SELECT id, feedId, link FROM article
        WHERE accountId = :accountId AND updateAt < :before
        AND isUnread = 0 AND isStarred = 0 AND isReadLater = 0
    """)
    suspend fun queryExpiredIdentities(accountId: Int, before: Date): List<ArticleLinkMeta>

    @Transaction
    suspend fun archiveExpiredArticles(accountId: Int, before: Date): List<ArticleLinkMeta> =
        archiveAndDelete(accountId, queryExpiredIdentities(accountId, before))

    @Transaction
    suspend fun cleanupBefore(
        accountId: Int,
        before: Date,
        includeUnread: Boolean,
        keepStarred: Boolean,
    ): List<ArticleLinkMeta> = archiveAndDelete(
        accountId, queryArticleIdentitiesBefore(accountId, before, includeUnread, keepStarred),
    )

    @Transaction
    suspend fun archiveAndDelete(
        accountId: Int, identities: List<ArticleLinkMeta>,
    ): List<ArticleLinkMeta> {
        val archivedAt = System.currentTimeMillis()
        identities.chunked(500).forEach { batch ->
            insertArchivedIdentities(batch.map {
                ArchivedArticle(feedId = it.feedId, link = it.link, archivedAt = archivedAt)
            })
            check(deleteByIds(accountId, batch.map { it.id }) == batch.size) {
                "Article cleanup changed during the transaction"
            }
        }
        return identities
    }

    @Query(
        """
        SELECT COUNT(*) FROM article
        WHERE accountId = :accountId
        """
    )
    fun countAllAsFlow(accountId: Int): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM article
        WHERE accountId = :accountId
        AND isUnread = 1
        """
    )
    fun countUnreadAsFlow(accountId: Int): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM article
        WHERE accountId = :accountId
        AND isUnread = 0
        """
    )
    fun countReadAsFlow(accountId: Int): Flow<Int>

    @Query(
        """
        SELECT COUNT(*) FROM article
        WHERE accountId = :accountId
        AND isStarred = 1
        """
    )
    fun countStarredAsFlow(accountId: Int): Flow<Int>

    @Query(
        """
        SELECT id, feedId, link FROM article
        WHERE feedId = :feedId
        """
    )
    suspend fun queryArticleIdentitiesByFeedId(feedId: String): List<ArticleLinkMeta>

    @Query(
        """
        SELECT id, feedId, link, isUnread, isStarred, isReadLater FROM article
        WHERE feedId = :feedId
        """
    )
    suspend fun queryArticleSyncMetasByFeedId(feedId: String): List<ArticleSyncMeta>

    @Query(
        """
        SELECT id, date, title, author, rawDescription, shortDescription,
        img, link, feedId, isUnread, isStarred, isReadLater
        FROM article
        WHERE accountId = :accountId
        ORDER BY date DESC
        """
    )
    suspend fun queryArticleSnapshotsByAccountId(accountId: Int): List<ArticleSnapshot>

    @Query(
        """
        UPDATE article SET isReadLater = :isReadLater
        WHERE accountId = :accountId
        AND id in (:ids)
        """
    )
    suspend fun markAsReadLaterByIdSet(
        accountId: Int,
        ids: Set<String>,
        isReadLater: Boolean,
    ): Int


    @Transaction
    @Query(
        """
        SELECT feedId, COUNT(*) AS important
        FROM article
        WHERE isUnread = :isUnread
        AND accountId = :accountId
        GROUP BY feedId
        """
    )
    fun queryImportantCountWhenIsUnread(
        accountId: Int,
        isUnread: Boolean,
    ): Flow<Map<@MapColumn("feedId") String, @MapColumn("important") Int>>

    @Transaction
    @Query(
        """
        SELECT feedId, COUNT(*) AS important
        FROM article
        WHERE isStarred = :isStarred
        AND accountId = :accountId
        GROUP BY feedId
        """
    )
    fun queryImportantCountWhenIsStarred(
        accountId: Int,
        isStarred: Boolean,
    ): Flow<Map<@MapColumn("feedId") String, @MapColumn("important") Int>>

    @Transaction
    @Query(
        """
        SELECT feedId, COUNT(*) AS important
        FROM article
        WHERE accountId = :accountId
        GROUP BY feedId
        """
    )
    fun queryImportantCountWhenIsAll(accountId: Int):
            Flow<Map<@MapColumn("feedId") String, @MapColumn("important") Int>>


    @Transaction
    @Query(
        """
        SELECT * FROM article 
        WHERE accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedWhenIsAll(
        accountId: Int, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE isStarred = :isStarred 
        AND accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedWhenIsStarred(
        accountId: Int, isStarred: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article 
        WHERE isUnread = :isUnread 
        AND accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedWhenIsUnread(
        accountId: Int, isUnread: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT a.id, a.date, a.title, a.author, a.rawDescription, 
        a.shortDescription, a.fullContent, a.img, a.link, a.feedId, 
        a.accountId, a.isUnread, a.isStarred, a.isReadLater, a.updateAt 
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryArticleWithFeedByGroupIdWhenIsAll(
        accountId: Int, groupId: String, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT a.id, a.date, a.title, a.author, a.rawDescription, 
        a.shortDescription, a.fullContent, a.img, a.link, a.feedId, 
        a.accountId, a.isUnread, a.isStarred, a.isReadLater, a.updateAt 
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.isStarred = :isStarred
        AND a.accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryArticleWithFeedByGroupIdWhenIsStarred(
        accountId: Int, groupId: String, isStarred: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT a.id, a.date, a.title, a.author, a.rawDescription, 
        a.shortDescription, a.fullContent, a.img, a.link, a.feedId, 
        a.accountId, a.isUnread, a.isStarred, a.isReadLater, a.updateAt 
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.isUnread = :isUnread
        AND a.accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryArticleWithFeedByGroupIdWhenIsUnread(
        accountId: Int, groupId: String, isUnread: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE feedId = :feedId
        AND accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedByFeedIdWhenIsAll(
        accountId: Int, feedId: String, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * from article 
        WHERE feedId = :feedId 
        AND isStarred = :isStarred
        AND accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedByFeedIdWhenIsStarred(
        accountId: Int, feedId: String, isStarred: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>

    @Transaction
    @Query(
        """
        SELECT * FROM article 
        WHERE feedId = :feedId 
        AND isUnread = :isUnread
        AND accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryArticleWithFeedByFeedIdWhenIsUnread(
        accountId: Int, feedId: String, isUnread: Boolean, sortAscending: Boolean = false
    ): PagingSource<Int, ArticleWithFeed>


    @RewriteQueriesToDropUnusedColumns
    @Query(
        """
        SELECT a.id, a.date, a.title, a.author, a.rawDescription, 
        a.shortDescription, a.fullContent, a.img, a.link, a.feedId, 
        a.accountId, a.isUnread, a.isStarred, a.isReadLater, a.updateAt 
        FROM article AS a LEFT JOIN feed AS b 
        ON a.feedId = b.id
        WHERE a.feedId = :feedId 
        AND a.accountId = :accountId
        ORDER BY date DESC LIMIT 1
        """
    )
    suspend fun queryLatestByFeedId(accountId: Int, feedId: String): Article?


    @Query(
        """
        SELECT * from article 
        WHERE link in (:linkList)
        AND feedId = :feedId
        AND accountId = :accountId
        """
    )
    suspend fun queryArticlesByLinks(
        linkList: List<String>,
        feedId: String,
        accountId: Int,
    ): List<Article>

    @Query(
        """
        SELECT a.*
        FROM article AS a
        LEFT JOIN feed AS f ON a.feedId = f.id
        WHERE f.accountId = :accountId
        AND f.isFullContent = 1
        AND a.isUnread = 1
        """
    )
    suspend fun queryUnreadFullContentArticles(
        accountId: Int,
    ): List<Article>

    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE id = :id
        """
    )
    suspend fun queryById(id: String): ArticleWithFeed?


    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND isUnread = :isUnread
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, isUnread: Boolean, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, before: Date, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND isUnread = :isUnread
        AND date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataAll(
        accountId: Int, isUnread: Boolean, before: Date, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        AND isUnread = :isUnread
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataByFeedId(
        accountId: Int, feedId: String, isUnread: Boolean, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT id, isUnread, isStarred FROM article
        WHERE accountId = :accountId
        AND feedId = :feedId
        AND isUnread = :isUnread
        AND date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN date END ASC,
            CASE WHEN :sortAscending = 0 THEN date END DESC
        """
    )
    fun queryMetadataByFeedId(
        accountId: Int,
        feedId: String,
        isUnread: Boolean,
        before: Date,
        sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT a.id, a.isUnread, a.isStarred 
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.accountId = :accountId
        AND a.isUnread = :isUnread
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryMetadataByGroupIdWhenIsUnread(
        accountId: Int, groupId: String, isUnread: Boolean, sortAscending: Boolean = false
    ): List<ArticleMeta>

    @Transaction
    @Query(
        """
        SELECT a.id, a.isUnread, a.isStarred 
        FROM article AS a
        LEFT JOIN feed AS b ON b.id = a.feedId
        LEFT JOIN `group` AS c ON c.id = b.groupId
        WHERE c.id = :groupId
        AND a.accountId = :accountId
        AND a.isUnread = :isUnread
        AND a.date < :before
        ORDER BY
            CASE WHEN :sortAscending = 1 THEN a.date END ASC,
            CASE WHEN :sortAscending = 0 THEN a.date END DESC
        """
    )
    fun queryMetadataByGroupIdWhenIsUnread(
        accountId: Int,
        groupId: String,
        isUnread: Boolean,
        before: Date,
        sortAscending: Boolean = false
    ): List<ArticleMeta>


    /**
     * query the latest unread articles from account with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND isUnread = 1
        ORDER BY date desc
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticles(accountId: Int, limit: Int = 15): Flow<List<ArticleWithFeed>>


    /**
     * query the latest unread articles from feed with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE feedId = :feedId
        AND isUnread = 1
        ORDER BY date desc
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticlesFromFeed(feedId: String, limit: Int = 15): Flow<List<ArticleWithFeed>>


    /**
     * query the latest unread articles from group with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT a.* FROM article AS a
        LEFT JOIN feed AS f ON a.feedId = f.id
        WHERE f.groupId = :groupId
        AND a.isUnread = 1
        ORDER BY a.date DESC
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticlesFromGroup(groupId: String, limit: Int = 15): Flow<List<ArticleWithFeed>>


    /**
     * query the latest unread articles from account with id, limit count
     */
    @Transaction
    @Query(
        """
        SELECT * FROM article
        WHERE accountId = :accountId
        AND isUnread = 1
        ORDER BY date desc
        LIMIT :limit
        """
    )
    fun queryLatestUnreadArticleFlow(accountId: Int, limit: Int): Flow<List<ArticleWithFeed>>

    @Upsert
    suspend fun insert(vararg article: Article)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertOnConflictIgnore(vararg article: Article)

    @Insert
    suspend fun insertList(articles: List<Article>)

    @Delete
    suspend fun delete(vararg article: Article)

    @Update
    suspend fun update(vararg article: Article)

    @Transaction
    suspend fun insertListIfNotExist(articles: List<Article>, feed: Feed): List<Article> {
        if (articles.isEmpty()) return articles

        val existingArticles = queryArticlesByLinks(
            linkList = articles.map { it.link },
            feedId = feed.id,
            accountId = feed.accountId
        ).associateBy { it.link }

        return articles.filterNot { existingArticles.containsKey(it.link) }.also { insertList(it) }
    }
}
