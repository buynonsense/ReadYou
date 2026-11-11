package me.ash.reader.domain.model.article

import java.util.Date

/**
 * Projection of an article with its read states, used by the WebDAV
 * sync merge without loading article bodies.
 */
data class ArticleSyncMeta(
    val id: String,
    val feedId: String,
    val link: String,
    val isUnread: Boolean,
    val isStarred: Boolean,
    val isReadLater: Boolean,
)

/**
 * Projection of a full article without the deprecated duplicate
 * [Article.fullContent] body, used to build WebDAV backup snapshots.
 */
data class ArticleSnapshot(
    val id: String,
    val date: Date,
    val title: String,
    val author: String?,
    val rawDescription: String,
    val shortDescription: String,
    val img: String?,
    val link: String,
    val feedId: String,
    val isUnread: Boolean,
    val isStarred: Boolean,
    val isReadLater: Boolean,
)
