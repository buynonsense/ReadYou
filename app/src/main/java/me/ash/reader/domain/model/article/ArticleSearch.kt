package me.ash.reader.domain.model.article

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Fts4
import androidx.room.FtsOptions
import androidx.room.PrimaryKey

@Fts4(tokenizer = FtsOptions.TOKENIZER_UNICODE61)
@Entity(tableName = "article_search")
data class ArticleSearch(
    @PrimaryKey @ColumnInfo(name = "rowid") val rowId: Int,
    val title: String,
    val summary: String,
    val body: String,
    val cachedBody: String,
)
