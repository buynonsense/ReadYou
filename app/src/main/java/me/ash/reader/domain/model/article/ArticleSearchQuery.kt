package me.ash.reader.domain.model.article

/** Treat user input as text, never as executable FTS query syntax. */
data class ArticleSearchQuery(val match: String, val literal: String, val useSubstring: Boolean) {
    companion object {
        fun from(text: String): ArticleSearchQuery {
            val literal = text.trim().take(4_096)
            val tokens = Regex("[\\p{L}\\p{N}]+").findAll(literal).take(32).map { it.value.take(128) }.toList()
            // Adjacent terms are ANDed in both FTS4 query dialects. An explicit AND is
            // itself a search term on SQLite builds without enhanced query syntax.
            val expression = tokens.joinToString(" ") { "\"$it*\"" }.ifEmpty { "\"\"" }
            // unicode61 tokenizes CJK runs as words; retain substring search within these runs.
            val cjk = literal.any { it in '\u3040'..'\u30ff' || it in '\u3400'..'\u9fff' }
            return ArticleSearchQuery(expression, literal, cjk)
        }
    }
}
