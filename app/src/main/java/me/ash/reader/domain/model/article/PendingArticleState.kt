package me.ash.reader.domain.model.article

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

/** A durable, account-bound write awaiting acknowledgement by the RSS service. */
@Entity(
    tableName = "pending_article_state",
    primaryKeys = ["accountId", "articleId", "field"],
    foreignKeys = [ForeignKey(
        entity = Article::class, parentColumns = ["id"], childColumns = ["articleId"],
        onDelete = ForeignKey.CASCADE,
    )],
    indices = [Index("articleId")],
)
data class PendingArticleState(
    val accountId: Int,
    val articleId: String,
    val field: String,
    val value: Boolean,
    val revision: String,
    val changedAt: Long,
) {
    companion object {
        const val UNREAD = "unread"
        const val STARRED = "starred"
    }
}
