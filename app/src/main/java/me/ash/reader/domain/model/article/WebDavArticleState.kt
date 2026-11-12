package me.ash.reader.domain.model.article

import androidx.room.Entity

/** Reading states survive local cache cleanup and can arrive before the RSS article. */
@Entity(tableName = "webdav_article_state", primaryKeys = ["accountId", "feedUrl", "link"])
data class WebDavArticleState(
    val accountId: Int,
    val feedUrl: String,
    val link: String,
    val isUnread: Boolean,
    val isStarred: Boolean,
    val isReadLater: Boolean,
)
