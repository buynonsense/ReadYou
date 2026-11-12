package me.ash.reader.domain.data

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.snapshotFlow
import androidx.work.WorkManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.article.PendingArticleState
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.ArticleStateOutbox
import me.ash.reader.domain.service.ArticleStateSyncWorker
import me.ash.reader.infrastructure.coroutine.runCatchingCancellable
import me.ash.reader.infrastructure.di.ApplicationScope
import me.ash.reader.infrastructure.di.IODispatcher
import timber.log.Timber

/** Optimistic UI state; the database outbox owns persistence and remote retries. */
@OptIn(FlowPreview::class)
class DiffMapHolder @Inject constructor(
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val accountService: AccountService,
    private val outbox: ArticleStateOutbox,
    private val workManager: WorkManager,
) {
    val diffMap = mutableStateMapOf<String, Diff>()
    val diffMapSnapshotFlow = snapshotFlow { diffMap.toMap() }.stateIn(
        applicationScope, SharingStarted.Eagerly, emptyMap(),
    )

    private sealed interface Command {
        data class Save(val diff: Diff, val field: String) : Command
        data class Barrier(val complete: CompletableDeferred<Unit>) : Command
        data class Bulk(val accountId: Int, val groupId: String?, val feedId: String?, val articleId: String?, val before: Date?, val isUnread: Boolean) : Command
    }
    private val writes = Channel<Command>(Channel.UNLIMITED)

    init {
        // A single writer preserves the order of rapid read/unread toggles.
        applicationScope.launch(ioDispatcher) {
            accountService.currentAccountFlow.first { it != null }
            importLegacyDiffs()
            for (command in writes) {
                when (command) {
                    is Command.Barrier -> command.complete.complete(Unit)
                    is Command.Bulk -> runCatchingCancellable {
                        outbox.markAsRead(command.accountId, command.groupId, command.feedId, command.articleId, command.before, command.isUnread)
                    }.onFailure { Timber.e(it, "Could not save article states") }
                    is Command.Save -> runCatchingCancellable {
                        val diff = command.diff
                        outbox.record(diff.accountId, diff.articleId, command.field, diff.isUnread)
                        if (command.field == PendingArticleState.UNREAD && diffMap[diff.articleId]?.revision == diff.revision) {
                            diffMap.remove(diff.articleId)
                        }
                    }.onFailure { Timber.e(it, "Could not save article state") }
                }
            }
        }
        applicationScope.launch(ioDispatcher) {
            outbox.changes.debounce(1_000).collect { pending ->
                pending.map { it.accountId }.distinct().forEach { ArticleStateSyncWorker.enqueue(workManager, it) }
            }
        }
    }

    fun checkIfUnread(articleWithFeed: ArticleWithFeed): Boolean =
        diffMap[articleWithFeed.article.id]?.takeIf { it.accountId == articleWithFeed.article.accountId }?.isUnread
            ?: articleWithFeed.article.isUnread

    fun updateDiff(vararg articleWithFeed: ArticleWithFeed, isUnread: Boolean? = null) {
        articleWithFeed.forEach { article ->
            val current = checkIfUnread(article)
            val desired = isUnread ?: !current
            if (desired != current) {
                val diff = Diff(desired, article)
                diffMap[diff.articleId] = diff
                writes.trySend(Command.Save(diff, PendingArticleState.UNREAD)).getOrThrow()
            }
        }
    }

    fun updateStarred(article: ArticleWithFeed, isStarred: Boolean) {
        writes.trySend(Command.Save(Diff(isStarred, article), PendingArticleState.STARRED)).getOrThrow()
    }

    fun updateStarred(accountId: Int, articleId: String, isStarred: Boolean) {
        writes.trySend(Command.Save(Diff(isStarred, articleId, "", accountId), PendingArticleState.STARRED)).getOrThrow()
    }

    fun markAsRead(accountId: Int, groupId: String?, feedId: String?, articleId: String?, before: Date?, isUnread: Boolean) {
        writes.trySend(Command.Bulk(accountId, groupId, feedId, articleId, before, isUnread)).getOrThrow()
    }

    fun commitDiffsToDb() = applicationScope.launch(ioDispatcher) {
        awaitPersistence()
    }

    suspend fun awaitPersistence() {
        val complete = CompletableDeferred<Unit>()
        writes.send(Command.Barrier(complete))
        complete.await()
    }

    private suspend fun importLegacyDiffs() {
        val gson = Gson()
        val type = object : TypeToken<Map<String, Diff>>() {}.type
        accountService.getAllAccounts().forEach { account ->
            val accountId = requireNotNull(account.id)
            val file = context.cacheDir.resolve("diff/$accountId/diff_map.json")
            if (file.exists()) runCatchingCancellable {
                val legacy = requireNotNull(gson.fromJson<Map<String, Diff>>(file.readText(), type))
                legacy.values.forEach { outbox.record(accountId, it.articleId, PendingArticleState.UNREAD, it.isUnread) }
                check(file.delete()) { "Could not remove migrated article state cache" }
            }.onFailure { Timber.e(it, "Could not migrate article state cache for account %s", accountId) }
        }
    }
}

data class Diff(
    val isUnread: Boolean,
    val articleId: String,
    val feedId: String,
    val accountId: Int = 0,
    val revision: String = UUID.randomUUID().toString(),
) {
    constructor(isUnread: Boolean, articleWithFeed: ArticleWithFeed) : this(
        isUnread, articleWithFeed.article.id, articleWithFeed.feed.id, articleWithFeed.article.accountId,
    )
}
