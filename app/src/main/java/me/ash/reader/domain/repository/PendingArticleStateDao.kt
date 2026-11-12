package me.ash.reader.domain.repository

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import me.ash.reader.domain.model.article.PendingArticleState

@Dao
interface PendingArticleStateDao {
    @Query("DELETE FROM pending_article_state WHERE accountId = :accountId AND articleId = :articleId")
    suspend fun clearArticle(accountId: Int, articleId: String)
    @Upsert
    suspend fun put(change: PendingArticleState)

    @Query("SELECT * FROM pending_article_state WHERE accountId = :accountId ORDER BY changedAt, articleId")
    suspend fun pending(accountId: Int): List<PendingArticleState>

    @Query("SELECT * FROM pending_article_state ORDER BY accountId, articleId, field")
    fun observe(): Flow<List<PendingArticleState>>

    @Query("DELETE FROM pending_article_state WHERE accountId = :accountId AND articleId = :articleId AND field = :field AND revision = :revision")
    suspend fun acknowledge(accountId: Int, articleId: String, field: String, revision: String): Int
}
