package me.ash.reader

import android.app.Notification
import android.app.NotificationManager
import android.os.Build
import android.os.Debug
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
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
import me.ash.reader.infrastructure.preference.ReadingRendererPreference
import me.ash.reader.infrastructure.preference.SyncIntervalPreference
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.getDefaultGroupId
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Runs against a test installation; creates its own account and articles. */
@RunWith(AndroidJUnit4::class)
class NotificationMemoryTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext.applicationContext as AndroidApp

    @Test
    fun opensNotificationWithTenThousandUnreadArticles() = runBlocking {
        val previousAccount = withTimeout(15_000) {
            app.accountService.currentAccountFlow.first { it != null }!!
        }
        val account = app.accountService.addAccount(
            Account(
                name = "Notification memory fixture",
                type = AccountType.Local,
                syncInterval = SyncIntervalPreference.Manually,
            )
        )
        val accountId = account.id!!
        val feed = Feed(
            id = "memory-fixture-feed-$accountId",
            name = "Notification memory fixture",
            url = "https://example.invalid/feed",
            groupId = accountId.getDefaultGroupId(),
            accountId = accountId,
            isNotification = true,
        )
        val notifications = app.getSystemService(NotificationManager::class.java)
        val firstLaunchKey = booleanPreferencesKey(PreferencesKey.isFirstLaunch)
        val rendererKey = intPreferencesKey(PreferencesKey.readingRenderer)
        val previousPreferences = app.dataStore.data.first()
        try {
            app.dataStore.edit {
                it[firstLaunchKey] = false
                it[rendererKey] = ReadingRendererPreference.WebView.value
            }
            withTimeout(5_000) {
                app.accountService.currentAccountIdFlow.first { it == accountId }
            }
            app.androidDatabase.feedDao().insert(feed)
            val paragraph = "<p>Offline fixture text for notification memory testing.</p>"
            val padding = paragraph.repeat(300)
            val count = 10_050
            val now = System.currentTimeMillis()
            for (start in 0 until count step 100) {
                app.androidDatabase.articleDao().insertList(
                    (start until minOf(start + 100, count)).map { index ->
                        Article(
                            id = "memory-fixture-$accountId-$index",
                            date = Date(now + index),
                            title = "MEMORY_TITLE_$index",
                            rawDescription = "<p>MEMORY_BODY_$index</p>$padding",
                            shortDescription = "Fixture article $index",
                            link = "https://example.invalid/article/$index",
                            feedId = feed.id,
                            accountId = accountId,
                        )
                    }
                )
            }
            val article = app.androidDatabase.articleDao()
                .queryById("memory-fixture-$accountId-${count - 1}")!!.article
            if (Build.VERSION.SDK_INT >= 33) {
                ParcelFileDescriptor.AutoCloseInputStream(
                    instrumentation.uiAutomation.executeShellCommand(
                        "pm grant ${app.packageName} android.permission.POST_NOTIFICATIONS"
                    )
                ).use { it.readBytes() }
            }
            check(notifications.areNotificationsEnabled()) { "Test requires notification permission" }
            app.notificationHelper.notify(feed, listOf(article))
            val pendingIntent = withTimeout(10_000) {
                while (true) {
                    val notification = notifications.activeNotifications.firstOrNull {
                        it.notification.group == feed.id &&
                            it.notification.extras.getString(Notification.EXTRA_TITLE) == article.title
                    }?.notification
                    notification?.contentIntent?.let { return@withTimeout it }
                    delay(100)
                }
                error("Notification not posted")
            }
            // Sending from instrumentation needs the privilege normally supplied by System UI.
            if (Build.VERSION.SDK_INT >= 29) {
                instrumentation.uiAutomation.adoptShellPermissionIdentity(
                    "android.permission.START_ACTIVITIES_FROM_BACKGROUND"
                )
            }
            try {
                pendingIntent.send()
            } finally {
                if (Build.VERSION.SDK_INT >= 29) {
                    instrumentation.uiAutomation.dropShellPermissionIdentity()
                }
            }
            withTimeout(30_000) {
                while (!readerBody().contains("MEMORY_BODY_${count - 1}")) delay(200)
            }
            val runtime = Runtime.getRuntime()
            val heap = runtime.totalMemory() - runtime.freeMemory()
            println(
                "NOTIFICATION_MEMORY count=$count javaHeap=$heap " +
                    "maxHeap=${runtime.maxMemory()} pssKb=${Debug.getPss()}"
            )
            assertTrue(
                "The article body must render after opening its notification",
                readerBody().contains("MEMORY_BODY_${count - 1}"),
            )
        } finally {
            instrumentation.runOnMainSync {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>().forEach { it.finish() }
            }
            notifications.activeNotifications.filter { it.notification.group == feed.id }
                .forEach { notifications.cancel(it.id) }
            app.accountService.delete(accountId)
            app.accountService.switch(previousAccount)
            app.dataStore.edit {
                previousPreferences[firstLaunchKey]?.let { value -> it[firstLaunchKey] = value }
                    ?: it.remove(firstLaunchKey)
                previousPreferences[rendererKey]?.let { value -> it[rendererKey] = value }
                    ?: it.remove(rendererKey)
            }
        }
    }

    private fun readerBody(): String {
        val latch = CountDownLatch(1)
        var body = ""
        instrumentation.runOnMainSync {
            val activity = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().firstOrNull()
            val webView = activity?.findViewById<View>(android.R.id.content)?.findWebView()
            if (webView == null) latch.countDown()
            else webView.evaluateJavascript("document.body.innerText") {
                body = it.orEmpty()
                latch.countDown()
            }
        }
        check(latch.await(3, TimeUnit.SECONDS)) { "Reader WebView did not respond" }
        return body
    }

    private fun View.findWebView(): WebView? {
        if (this is WebView) return this
        if (this is ViewGroup) {
            for (index in 0 until childCount) {
                getChildAt(index).findWebView()?.let { return it }
            }
        }
        return null
    }
}
