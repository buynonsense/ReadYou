package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Query

@Dao
interface ArticleSearchDao {
    @Query("UPDATE article_search SET cachedBody = :text WHERE cachedBody != :text AND rowid IN (SELECT rowid FROM article WHERE id = :articleId AND accountId = :accountId)")
    suspend fun updateCachedBody(accountId: Int, articleId: String, text: String)

    @Query("UPDATE article_search SET cachedBody = '' WHERE cachedBody != '' AND rowid IN (SELECT rowid FROM article WHERE accountId = :accountId)")
    suspend fun clearCachedBodies(accountId: Int)
}
