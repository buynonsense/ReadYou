package me.ash.reader

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.Date
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.article.Article
import me.ash.reader.domain.model.feed.Feed
import me.ash.reader.infrastructure.android.AndroidApp
import me.ash.reader.infrastructure.android.MainActivity
import me.ash.reader.ui.ext.getDefaultGroupId
import me.ash.reader.ui.page.common.ExtraName
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AccountNavigationDeviceTest {
    @Test fun notificationAndWidgetIntentsSwitchToTheirPersistedAccount(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as AndroidApp
        val accounts = app.accountService
        val original = withTimeout(15_000) { accounts.currentAccountFlow.first { it != null } }!!
        val owner = accounts.addAccount(Account(name = "Navigation fixture", type = AccountType.Local))
        val other = accounts.addAccount(Account(name = "Other fixture", type = AccountType.Local))
        val id = requireNotNull(owner.id)
        val feed = Feed("$id-navigation-feed", "Navigation feed", url = "https://example.org/rss", groupId = id.getDefaultGroupId(), accountId = id)
        val article = Article("$id-navigation-article", Date(), "Navigation fixture", rawDescription = "Body", shortDescription = "Body", link = "https://example.org/a", feedId = feed.id, accountId = id)
        app.androidDatabase.feedDao().insert(feed)
        app.androidDatabase.articleDao().insert(article)
        try {
            val intent = Intent(context, MainActivity::class.java).apply {
                putExtra(ExtraName.ARTICLE_ID, article.id)
                // The database owner takes precedence over a stale sender hint.
                putExtra(ExtraName.ACCOUNT_ID, other.id)
            }
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                withTimeout(15_000) { accounts.currentAccountFlow.first { it?.id == id } }
                assertEquals(id, accounts.getCurrentAccountId())
                scenario.onActivity { activity -> assertFalse(activity.intent.hasExtra(ExtraName.ARTICLE_ID)) }
                scenario.recreate()
                delay(500)
                assertEquals(id, accounts.getCurrentAccountId())
            }
            accounts.switch(other)
            val widget = Intent(context, MainActivity::class.java).putExtra(ExtraName.FEED_ID, feed.id)
            ActivityScenario.launch<MainActivity>(widget).use {
                withTimeout(15_000) { accounts.currentAccountFlow.first { it?.id == id } }
                assertEquals(id, accounts.getCurrentAccountId())
            }
        } finally {
            accounts.switch(original)
            accounts.delete(requireNotNull(other.id))
            accounts.delete(id)
        }
    }
}
