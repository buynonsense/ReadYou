package me.ash.reader.domain.service

import android.content.Context
import androidx.room.withTransaction
import androidx.work.ListenableWorker
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.Date
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import me.ash.reader.R
import me.ash.reader.domain.data.SyncLogger
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.security.RemoteReaderSecurityKey
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.infrastructure.android.NotificationHelper
import me.ash.reader.infrastructure.coroutine.runCatchingCancellable
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.di.DefaultDispatcher
import me.ash.reader.infrastructure.di.IODispatcher
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.infrastructure.rss.provider.remote.RemoteReaderClient
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.ext.spacerDollar
import okhttp3.OkHttpClient
import org.jsoup.Jsoup

class RemoteRssService @Inject constructor(
    @ApplicationContext private val context: Context,
    private val articleDao: ArticleDao,
    private val groupDao: GroupDao,
    private val feedDao: FeedDao,
    workManager: WorkManager,
    rssHelper: RssHelper,
    private val notificationHelper: NotificationHelper,
    @IODispatcher ioDispatcher: CoroutineDispatcher,
    @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
    private val accountService: AccountService,
    private val database: AndroidDatabase,
    private val httpClient: OkHttpClient,
    private val syncLogger: SyncLogger,
    private val statusStore: me.ash.reader.infrastructure.preference.AccountSyncStatusStore,
) : AbstractRssRepository(articleDao, groupDao, feedDao, workManager, rssHelper, notificationHelper, ioDispatcher, defaultDispatcher, accountService) {
    // Subscription management for these providers remains in their own service UI.
    override val importSubscription = false
    override val addSubscription = false
    override val moveSubscription = false
    override val deleteSubscription = false
    override val updateSubscription = false

    private fun client(account: Account) = RemoteReaderClient(account.type, RemoteReaderSecurityKey.from(account.securityKey), httpClient, context)

    override suspend fun validCredentials(account: Account): Boolean = client(account).validate()

    override suspend fun sync(accountId: Int, feedId: String?, groupId: String?): ListenableWorker.Result =
        runCatchingCancellable {
            val account = requireNotNull(accountService.getAccountById(accountId)) { "Account was deleted" }
            val api = client(account)
            api.validate()
            val defaultGroup = accountId.getDefaultGroupId()
            val remoteGroups = api.groups()
            val remoteFeeds = api.feeds()
            val knownGroups = remoteGroups.map { it.id }.toSet()
            database.withTransaction {
                groupDao.insert(Group(defaultGroup, context.getString(R.string.defaults), accountId))
                remoteGroups.forEach { groupDao.insert(Group(accountId spacerDollar it.id, it.name, accountId)) }
                feedDao.insertOrUpdate(remoteFeeds.map {
                    Feed(accountId spacerDollar it.id, it.title, url = it.url,
                        groupId = it.groupId?.takeIf { it in knownGroups }?.let { accountId spacerDollar it } ?: defaultGroup,
                        accountId = accountId, icon = it.icon)
                })
            }
            val feeds = feedDao.queryAll(accountId).associateBy { it.id.removePrefix("$accountId$") }
            val feedsByLocalId = feeds.values.associateBy { it.id }
            val unread = mutableSetOf<String>()
            val starred = mutableSetOf<String>()
            val observed = mutableSetOf<String>()
            val activeFeeds = remoteFeeds.map { accountId spacerDollar it.id }.toSet()
            var pages = 0
            api.articles { page ->
                statusStore.progress(accountId, "rss-pages", ++pages)
                val articles = page.mapNotNull { row ->
                    val feed = feeds[row.feedId] ?: return@mapNotNull null
                    if (feedId != null && feedId != feed.id) return@mapNotNull null
                    if (groupId != null && groupId != feed.groupId) return@mapNotNull null
                    val id = accountId spacerDollar row.id
                    observed += id
                    if (row.unread) unread += id
                    if (row.starred) starred += id
                    Article(id, Date(row.date), Jsoup.parse(row.title).text(), row.author, row.body,
                        Jsoup.parse(row.body).text().take(500), img = row.image, link = row.link,
                        feedId = feed.id, accountId = accountId, isUnread = row.unread, isStarred = row.starred, updateAt = Date())
                }
                articles.groupBy { it.feedId }.forEach { (id, rows) ->
                    val feed = requireNotNull(feedsByLocalId[id])
                    val inserted = articleDao.insertListIfNotExist(rows, feed)
                    if (feed.isNotification && inserted.isNotEmpty()) {
                        notificationHelper.notify(me.ash.reader.domain.model.feed.FeedWithArticle(feed, inserted))
                    }
                }
            }
            // Restrict a scoped refresh to the feeds actually requested.
            val targets = activeFeeds.filter { id ->
                val feed = feedsByLocalId[id]
                (feedId == null || id == feedId) && (groupId == null || feed?.groupId == groupId)
            }.toSet()
            articleDao.queryStateMetadata(accountId).filter { it.feedId in targets && it.id in observed }.chunked(500).forEach { metas ->
                val ids = metas.map { it.id }.toSet()
                articleDao.markAsReadByIdSet(accountId, ids intersect unread, true)
                articleDao.markAsReadByIdSet(accountId, ids - unread, false)
                articleDao.markAsStarredByIdSet(accountId, ids intersect starred, true)
                articleDao.markAsStarredByIdSet(accountId, ids - starred, false)
            }
            // Prune only after every page was read successfully. Keep saved articles even if
            // their subscription was removed on the server.
            database.withTransaction {
                feedDao.queryAll(accountId).filter { it.id !in activeFeeds }.forEach { super.deleteFeed(it, true) }
                groupDao.queryAll(accountId).filter { it.id != defaultGroup && it.id.removePrefix("$accountId$") !in knownGroups }.forEach { group ->
                    feedDao.queryByGroupId(accountId, group.id).forEach { feed -> feedDao.update(feed.copy(groupId = defaultGroup)) }
                    groupDao.delete(group)
                }
            }
            accountService.update(accountId) { copy(updateAt = Date()) }
            ListenableWorker.Result.success()
        }.onFailure { syncLogger.log(it, accountId) }.getOrElse {
            ListenableWorker.Result.failure(androidx.work.workDataOf("error" to (it.message ?: "Reader sync failed")))
        }

    override suspend fun syncReadStatus(accountId: Int, articleIds: Set<String>, isUnread: Boolean): Set<String> {
        val api = client(requireNotNull(accountService.getAccountById(accountId)))
        articleIds.chunked(100).forEach { api.setState(it.map { id -> id.removePrefix("$accountId$") }, unread = isUnread) }
        return articleIds
    }

    override suspend fun syncStarredStatus(accountId: Int, articleIds: Set<String>, isStarred: Boolean): Set<String> {
        val api = client(requireNotNull(accountService.getAccountById(accountId)))
        articleIds.chunked(100).forEach { api.setState(it.map { id -> id.removePrefix("$accountId$") }, starred = isStarred) }
        return articleIds
    }
}
