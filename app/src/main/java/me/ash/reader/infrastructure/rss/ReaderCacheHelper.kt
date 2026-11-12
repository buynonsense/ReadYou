package me.ash.reader.infrastructure.rss

import android.content.Context
import androidx.annotation.CheckResult
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.repository.ArticleSearchDao
import me.ash.reader.infrastructure.coroutine.runCatchingCancellable
import me.ash.reader.infrastructure.di.IODispatcher

class ReaderCacheHelper @Inject constructor(
    @ApplicationContext context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val rssHelper: RssHelper,
    private val articleSearchDao: ArticleSearchDao,
) {
    private val cacheDir = context.cacheDir.resolve("readability")

    private fun accountDir(accountId: Int): File {
        require(accountId > 0) { "Invalid cache account" }
        return cacheDir.resolve(accountId.toString())
    }

    @OptIn(ExperimentalStdlibApi::class)
    private fun cacheFile(accountId: Int, articleId: String): File =
        accountDir(accountId).resolve(
            MessageDigest.getInstance("SHA-256").digest(articleId.toByteArray()).toHexString() + ".html"
        )

    suspend fun peekFullContent(accountId: Int, articleId: String): String? = withContext(ioDispatcher) {
        val file = cacheFile(accountId, articleId)
        if (file.exists()) file.readText().takeIf { it.isNotBlank() } else null
    }

    suspend fun writeFullContent(accountId: Int, articleId: String, content: String): Result<Unit> =
        withContext(ioDispatcher) {
            runCatchingCancellable {
                require(content.isNotBlank()) { "Empty article content" }
                val target = cacheFile(accountId, articleId)
                check(target.parentFile!!.isDirectory || target.parentFile!!.mkdirs())
                val temporary = File.createTempFile("article-", ".tmp", target.parentFile)
                try {
                    temporary.writeText(content)
                    Files.move(
                        temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                } finally {
                    temporary.delete()
                }
                articleSearchDao.updateCachedBody(accountId, articleId, org.jsoup.Jsoup.parse(content).text())
                Unit
            }
        }

    @CheckResult
    suspend fun readFullContent(articleId: String, accountId: Int): Result<String> =
        withContext(ioDispatcher) {
            runCatchingCancellable {
                cacheFile(accountId, articleId).readText().also {
                    check(it.isNotBlank()) { "Empty article cache" }
                    articleSearchDao.updateCachedBody(accountId, articleId, org.jsoup.Jsoup.parse(it).text())
                }
            }
        }

    private suspend fun fetchFullContentInternal(article: Article): Result<String> =
        withContext(ioDispatcher) {
            runCatchingCancellable {
                val content = rssHelper.parseFullContent(article.link, article.title)
                writeFullContent(article.accountId, article.id, content).getOrThrow()
                content
            }
        }

    @CheckResult
    suspend fun readOrFetchFullContent(article: Article): Result<String> =
        readFullContent(article.id, article.accountId).let { cached ->
            if (cached.isSuccess) cached else fetchFullContentInternal(article)
        }

    suspend fun checkOrFetchFullContent(article: Article): Boolean =
        readOrFetchFullContent(article).isSuccess

    suspend fun deleteCacheFor(articleId: String, accountId: Int): Boolean =
        withContext(ioDispatcher) {
            runCatchingCancellable {
                val file = cacheFile(accountId, articleId)
                articleSearchDao.updateCachedBody(accountId, articleId, "")
                !file.exists() || file.delete()
            }.getOrDefault(false)
        }

    suspend fun clearCache(accountId: Int): Boolean = withContext(ioDispatcher) {
        runCatchingCancellable {
            articleSearchDao.clearCachedBodies(accountId)
            accountDir(accountId).deleteRecursively()
        }.getOrDefault(false)
    }
}
