package me.ash.reader.domain.model.article

/**
 * Lightweight projection of an article identity, used by cleanup
 * and sync features to avoid loading article bodies.
 */
data class ArticleLinkMeta(
    val id: String,
    val feedId: String,
    val link: String,
)
