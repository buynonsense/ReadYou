package me.ash.reader.domain.service

import androidx.room.withTransaction
import java.util.UUID
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.PendingArticleState
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.coroutine.runCatchingCancellable
import timber.log.Timber

@Singleton
class ArticleStateOutbox @Inject constructor(
    private val database: AndroidDatabase,
    private val rssService: RssService,
    private val accountService: AccountService,
    private val accountLock: AccountOperationLock,
    private val statusStore: me.ash.reader.infrastructure.preference.AccountSyncStatusStore,
) {
    val changes get() = database.pendingArticleStateDao().observe()

    /** Saving the intent and changing the local article either both commit or both roll back. */
    suspend fun record(accountId: Int, articleId: String, field: String, value: Boolean) {
        require(field == PendingArticleState.UNREAD || field == PendingArticleState.STARRED)
        database.withTransaction {
            if (!database.articleDao().contains(accountId, articleId)) return@withTransaction
            database.pendingArticleStateDao().put(PendingArticleState(
                accountId, articleId, field, value, UUID.randomUUID().toString(), System.currentTimeMillis(),
            ))
            apply(accountId, articleId, field, value)
        }
    }

    suspend fun markAsRead(accountId: Int, groupId: String?, feedId: String?, articleId: String?, before: Date?, isUnread: Boolean) {
        database.withTransaction {
            database.articleDao().stateTargets(accountId, groupId, feedId, articleId, before ?: Date(Long.MAX_VALUE), isUnread)
                .forEach { record(accountId, it, PendingArticleState.UNREAD, isUnread) }
        }
    }

    private suspend fun apply(accountId: Int, articleId: String, field: String, value: Boolean) {
        when (field) {
            PendingArticleState.UNREAD -> database.articleDao().markAsReadByArticleId(accountId, articleId, value)
            PendingArticleState.STARRED -> database.articleDao().markAsStarredByArticleId(accountId, articleId, value)
        }
    }

    /** A refresh must not replace local writes which the server has not acknowledged. */
    suspend fun reapplyPending(accountId: Int) = database.withTransaction {
        database.pendingArticleStateDao().pending(accountId).forEach {
            apply(accountId, it.articleId, it.field, it.value)
        }
    }

    suspend fun flush(accountId: Int): Boolean = accountLock.withAccount(accountId) {
        val account = accountService.getAccountById(accountId) ?: return@withAccount true
        val dao = database.pendingArticleStateDao()
        val pending = dao.pending(accountId)
        if (pending.isEmpty()) return@withAccount true
        val provider = rssService.forAccount(accountId)
        pending.groupBy { it.field to it.value }.forEach { (kind, changes) ->
            val ids = changes.map { it.articleId }.toSet()
            val acknowledged = if (account.type == AccountType.Local) ids else {
                runCatchingCancellable {
                    when (kind.first) {
                        PendingArticleState.UNREAD -> provider.syncReadStatus(accountId, ids, kind.second)
                        PendingArticleState.STARRED -> provider.syncStarredStatus(accountId, ids, kind.second)
                        else -> emptySet()
                    }
                }.onFailure {
                    statusStore.stateError(accountId, it.message)
                    Timber.e(it, "Article state upload failed for account %s", accountId)
                }
                    .getOrDefault(emptySet())
            }
            changes.filter { it.articleId in acknowledged }.forEach {
                // An older request must never acknowledge a newer toggle of the same article.
                dao.acknowledge(accountId, it.articleId, it.field, it.revision)
            }
        }
        dao.pending(accountId).isEmpty().also { if (it) statusStore.stateError(accountId, null) }
    }
}
