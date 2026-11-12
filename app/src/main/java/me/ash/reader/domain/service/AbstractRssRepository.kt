package me.ash.reader.domain.service

import android.util.Log
import androidx.paging.PagingSource
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.rometools.rome.feed.synd.SyndFeed
import java.util.Date
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.article.ArchivedArticle
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.article.ArticleLinkMeta
import me.ash.reader.domain.model.article.ArticleWithFeed
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.model.group.GroupWithFeed
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.preference.KeepArchivedPreference
import me.ash.reader.infrastructure.preference.SyncIntervalPreference
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.ui.ext.decodeHTML
import me.ash.reader.ui.ext.spacerDollar

abstract class AbstractRssRepository(
    private val articleDao: ArticleDao,
    private val groupDao: GroupDao,
    private val feedDao: FeedDao,
    private val workManager: WorkManager,
    private val rssHelper: RssHelper,
    private val notificationHelper: NotificationHelper,
    private val dispatcherIO: CoroutineDispatcher,
    private val dispatcherDefault: CoroutineDispatcher,
    private val accountService: AccountService,
) {

    open val importSubscription: Boolean = true
    open val addSubscription: Boolean = true
    open val moveSubscription: Boolean = true
    open val deleteSubscription: Boolean = true
    open val updateSubscription: Boolean = true

    open suspend fun validCredentials(account: Account): Boolean = true

    open suspend fun clearAuthorization() {}

    open suspend fun subscribe(
        feedLink: String,
        searchedFeed: SyndFeed,
        groupId: String,
        isNotification: Boolean,
        isFullContent: Boolean,
        isBrowser: Boolean,
    ) {
        val accountId = requireNotNull(groupDao.queryById(groupId)) { "Group was deleted" }.accountId
        check(addSubscription) { "Manage subscriptions in the service" }
        val feed =
            Feed(
                id = accountId.spacerDollar(UUID.randomUUID().toString()),
                name = searchedFeed.title.decodeHTML()!!,
                url = feedLink,
                groupId = groupId,
                accountId = accountId,
                icon = searchedFeed.icon?.link,
                isBrowser = isBrowser,
                isNotification = isNotification,
                isFullContent = isFullContent,
            )
        val articles =
            searchedFeed.entries.map { rssHelper.buildArticleFromSyndEntry(feed, accountId, it) }
        feedDao.insert(feed)
        articleDao.insertListIfNotExist(articles.map { it.copy(feedId = feed.id) }, feed, restoreWebDavStates = true)
    }

    open suspend fun addGroup(destFeed: Feed?, newGroupName: String, accountId: Int = destFeed?.accountId ?: accountService.getCurrentAccountId()): String {
        check(addSubscription) { "Manage subscriptions in the service" }
        require(destFeed == null || destFeed.accountId == accountId)
        accountId.let { accountId ->
            return accountId.spacerDollar(UUID.randomUUID().toString()).also {
                groupDao.insert(Group(id = it, name = newGroupName, accountId = accountId))
            }
        }
    }

    abstract suspend fun sync(
        accountId: Int,
        feedId: String?,
        groupId: String?,
    ): ListenableWorker.Result

    open suspend fun batchMarkAsRead(accountId: Int, articleIds: Set<String>, isUnread: Boolean) {
        articleIds
            .takeIf { it.isNotEmpty() }
            ?.chunked(500)
            ?.forEachIndexed { index, it ->
                articleDao.markAsReadByIdSet(accountId, it.toSet(), isUnread)
            }
    }

    open suspend fun syncReadStatus(accountId: Int, articleIds: Set<String>, isUnread: Boolean): Set<String> {
        /* no-op */
        return emptySet()
    }

    open suspend fun syncStarredStatus(accountId: Int, articleIds: Set<String>, isStarred: Boolean): Set<String> = emptySet()

    suspend fun clearKeepArchivedArticles(accountId: Int): List<ArticleLinkMeta> {
        val currentAccount = accountService.getAccountById(accountId) ?: return emptyList()
        val keepArchived = currentAccount.keepArchived
        if (keepArchived != KeepArchivedPreference.Always) {
            return articleDao.archiveExpiredArticles(
                    accountId,
                    Date(System.currentTimeMillis() - keepArchived.value),
                )
        }
        return emptyList()
    }

    fun cancelSync() {
        val accountId = accountService.getCurrentAccountId()
        SyncWorker.cancelPeriodicWork(workManager, accountId)
        SyncWorker.cancelOneTimeWork(workManager, accountId)
    }

    open fun doSyncOneTime(
        accountId: Int = accountService.getCurrentAccountId(),
        feedId: String? = null,
        groupId: String? = null,
    ) {
        SyncWorker.enqueueOneTimeWork(
            workManager,
            workDataOf("accountId" to accountId, "feedId" to feedId, "groupId" to groupId),
        )
    }

    fun initSync(account: Account = accountService.getCurrentAccount()) {
        account.let {
            val syncOnStart = it.syncOnStart.value
            if (syncOnStart) {
                doSyncOneTime(it.id!!)
            }
            if (it.syncInterval.value != SyncIntervalPreference.Manually.value) {
                SyncWorker.enqueuePeriodicWork(account = it, workManager = workManager)
            } else {
                SyncWorker.cancelPeriodicWork(workManager, requireNotNull(it.id))
            }
        }
    }

    fun pullGroups(accountId: Int = accountService.getCurrentAccountId()): Flow<MutableList<Group>> =
        groupDao.queryAllGroup(accountId).flowOn(dispatcherIO)

    fun pullFeeds(accountId: Int = accountService.getCurrentAccountId()): Flow<MutableList<GroupWithFeed>> =
        groupDao
            .queryAllGroupWithFeedAsFlow(accountId)
            .flowOn(dispatcherIO)

    fun pullArticles(
        groupId: String?,
        feedId: String?,
        isStarred: Boolean,
        isUnread: Boolean,
        sortAscending: Boolean = false,
        accountId: Int = accountService.getCurrentAccountId(),
    ): PagingSource<Int, ArticleWithFeed> {
        Log.i(
            "RLog",
            "pullArticles: accountId: ${accountId}, groupId: ${groupId}, feedId: ${feedId}, isStarred: ${isStarred}, isUnread: ${isUnread}",
        )
        return when {
            groupId != null ->
                when {
                    isStarred ->
                        articleDao.queryArticleWithFeedByGroupIdWhenIsStarred(
                            accountId,
                            groupId,
                            true,
                        )

                    isUnread ->
                        articleDao.queryArticleWithFeedByGroupIdWhenIsUnread(
                            accountId,
                            groupId,
                            true,
                            sortAscending = sortAscending,
                        )

                    else -> articleDao.queryArticleWithFeedByGroupIdWhenIsAll(accountId, groupId)
                }

            feedId != null ->
                when {
                    isStarred ->
                        articleDao.queryArticleWithFeedByFeedIdWhenIsStarred(
                            accountId,
                            feedId,
                            true,
                        )

                    isUnread ->
                        articleDao.queryArticleWithFeedByFeedIdWhenIsUnread(
                            accountId,
                            feedId,
                            true,
                            sortAscending = sortAscending,
                        )

                    else -> articleDao.queryArticleWithFeedByFeedIdWhenIsAll(accountId, feedId)
                }

            else ->
                when {
                    isStarred -> articleDao.queryArticleWithFeedWhenIsStarred(accountId, true)
                    isUnread ->
                        articleDao.queryArticleWithFeedWhenIsUnread(
                            accountId,
                            true,
                            sortAscending = sortAscending,
                        )

                    else -> articleDao.queryArticleWithFeedWhenIsAll(accountId)
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun pullImportant(isStarred: Boolean, isUnread: Boolean, accountId: Int = accountService.getCurrentAccountId()): Flow<Map<String, Int>> {
        Log.i(
            "RLog",
            "pullImportant: accountId: ${accountId}, isStarred: ${isStarred}, isUnread: ${isUnread}",
        )
        return when {
            isStarred -> articleDao.queryImportantCountWhenIsStarred(accountId, true)
            isUnread -> articleDao.queryImportantCountWhenIsUnread(accountId, true)
            else -> articleDao.queryImportantCountWhenIsAll(accountId)
        }
    }

    suspend fun findFeedById(id: String): Feed? = feedDao.queryById(id)

    suspend fun findGroupById(id: String): Group? = groupDao.queryById(id)

    suspend fun findArticleById(id: String): ArticleWithFeed? = articleDao.queryById(id)

    suspend fun isFeedExist(url: String, accountId: Int = accountService.getCurrentAccountId()): Boolean =
        feedDao.queryByLink(accountId, url).isNotEmpty()

    suspend fun queryAllGroupWithFeeds(accountId: Int = accountService.getCurrentAccountId()): List<GroupWithFeed> =
        groupDao.queryAllGroupWithFeed(accountId)

    open suspend fun renameGroup(group: Group) {
        groupDao.update(group)
    }

    open suspend fun renameFeed(feed: Feed) {
        updateFeed(feed)
    }

    open suspend fun moveFeed(originGroupId: String, feed: Feed) {
        updateFeed(feed)
    }

    open suspend fun changeFeedUrl(feed: Feed) {
        updateFeed(feed)
    }

    internal suspend fun updateFeed(feed: Feed) {
        require(groupDao.queryById(feed.groupId)?.accountId == feed.accountId) { "Group belongs to a different account" }
        feedDao.update(feed)
    }

    open suspend fun deleteGroup(group: Group, onlyDeleteNoStarred: Boolean? = false) {
        val accountId = group.accountId
        if (
            onlyDeleteNoStarred == true &&
                articleDao.countProtectedByGroup(accountId, group.id) > 0
        ) {
            return
        }
        deleteArticles(group = group, includeStarred = true)
        feedDao.deleteByGroupId(accountId, group.id)
        groupDao.delete(group)
    }

    open suspend fun deleteFeed(feed: Feed, onlyDeleteNoStarred: Boolean? = false) {
        if (
            onlyDeleteNoStarred == true &&
                articleDao.countProtectedByFeed(
                    feed.accountId,
                    feed.id,
                ) > 0
        ) {
            return
        }
        deleteArticles(feed = feed, includeStarred = true)
        feedDao.delete(feed)
    }

    suspend fun deleteArticles(
        group: Group? = null,
        feed: Feed? = null,
        includeStarred: Boolean = false,
    ) {
        when {
            group != null ->
                articleDao.deleteByGroupId(
                    group.accountId,
                    group.id,
                    includeStarred,
                )

            feed != null ->
                articleDao.deleteByFeedId(
                    feed.accountId,
                    feed.id,
                    includeStarred,
                )
        }
    }

    suspend fun deleteAccountArticles(accountId: Int) {
        articleDao.deleteByAccountId(accountId)
    }

    suspend fun groupParseFullContent(group: Group, isFullContent: Boolean) {
        feedDao.updateIsFullContentByGroupId(
            group.accountId,
            group.id,
            isFullContent,
        )
    }

    suspend fun groupOpenInBrowser(group: Group, isBrowser: Boolean) {
        feedDao.updateIsBrowserByGroupId(group.accountId, group.id, isBrowser)
    }

    suspend fun groupAllowNotification(group: Group, isNotification: Boolean) {
        feedDao.updateIsNotificationByGroupId(
            group.accountId,
            group.id,
            isNotification,
        )
    }

    suspend fun groupMoveToTargetGroup(group: Group, targetGroup: Group) {
        require(group.accountId == targetGroup.accountId) { "Groups belong to different accounts" }
        feedDao.updateTargetGroupIdByGroupId(
            group.accountId,
            group.id,
            targetGroup.id,
        )
    }

    fun searchArticles(
        content: String,
        groupId: String?,
        feedId: String?,
        isStarred: Boolean,
        isUnread: Boolean,
        sortAscending: Boolean = false,
        accountId: Int = accountService.getCurrentAccountId(),
    ): PagingSource<Int, ArticleWithFeed> {
        val query = me.ash.reader.domain.model.article.ArticleSearchQuery.from(content)
        return articleDao.searchArticles(
            accountId = accountId,
            query = query.match,
            literal = query.literal,
            useSubstring = query.useSubstring,
            feedId = feedId,
            groupId = groupId,
            isUnread = isUnread,
            isStarred = isStarred,
            sortAscending = sortAscending,
        )
    }

    suspend fun queryUnreadFullContentArticles(accountId: Int) =
        articleDao.queryUnreadFullContentArticles(accountId)
}
