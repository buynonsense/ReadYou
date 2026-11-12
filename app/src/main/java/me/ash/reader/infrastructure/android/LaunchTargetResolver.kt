package me.ash.reader.infrastructure.android

import javax.inject.Inject
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.domain.model.group.Group
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.service.AccountService

/** Resolve persisted ownership before switching accounts or applying a notification's filters. */
class LaunchTargetResolver @Inject constructor(
    private val accountService: AccountService,
    private val articleDao: ArticleDao,
    private val feedDao: FeedDao,
    private val groupDao: GroupDao,
) {
    data class Target(
        val account: Account,
        val articleId: String?,
        val feed: Feed?,
        val group: Group?,
    )

    suspend fun resolve(action: LaunchAction): Target? {
        val article = (action as? LaunchAction.OpenArticle)?.let { articleDao.queryById(it.articleId) }
        val accountHint: Int?
        val feedHint: String?
        val groupHint: String?
        when (action) {
            is LaunchAction.OpenArticle -> {
                accountHint = action.accountId
                feedHint = action.feedId
                groupHint = action.groupId
            }
            is LaunchAction.OpenArticleList -> {
                accountHint = action.accountId
                feedHint = action.feedId
                groupHint = action.groupId
            }
            is LaunchAction.Subscribe -> return null
        }
        val feed = article?.feed ?: feedHint?.let { feedDao.queryById(it) }
        val group = (feed?.groupId ?: groupHint)?.let { groupDao.queryById(it) }
        val accountId = article?.article?.accountId ?: feed?.accountId ?: group?.accountId ?: accountHint
            ?: return null
        val account = accountService.getAccountById(accountId) ?: return null
        return Target(
            account = account,
            articleId = article?.article?.id,
            feed = feed?.takeIf { it.accountId == accountId },
            group = group?.takeIf { it.accountId == accountId },
        )
    }
}
