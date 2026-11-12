package me.ash.reader.infrastructure.android

import android.Manifest
import android.content.Intent
import android.database.CursorWindow
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.core.app.NotificationManagerCompat
import androidx.core.util.Consumer
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.profileinstaller.ProfileInstallerInitializer
import androidx.work.WorkManager
import coil.ImageLoader
import dagger.hilt.android.AndroidEntryPoint
import java.lang.reflect.Field
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import me.ash.reader.domain.data.FilterStateUseCase
import me.ash.reader.domain.model.general.Filter
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.WidgetUpdateWorker
import me.ash.reader.infrastructure.compose.ProvideCompositionLocals
import me.ash.reader.infrastructure.preference.AccountSettingsProvider
import me.ash.reader.infrastructure.preference.InitialPagePreference
import me.ash.reader.infrastructure.preference.LanguagesPreference
import me.ash.reader.infrastructure.preference.LocalDarkTheme
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.ui.ext.initialPage
import me.ash.reader.ui.ext.isFirstLaunch
import me.ash.reader.ui.ext.languages
import me.ash.reader.ui.page.common.ExtraName
import me.ash.reader.ui.page.home.feeds.subscribe.SubscribeViewModel
import me.ash.reader.ui.page.nav3.AppEntry
import me.ash.reader.ui.page.nav3.key.Route
import me.ash.reader.ui.page.nav3.key.Route.*
import me.ash.reader.ui.theme.AppTheme

/** The Single-Activity Architecture. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    @Inject lateinit var imageLoader: ImageLoader

    @Inject lateinit var settingsProvider: SettingsProvider

    @Inject lateinit var accountService: AccountService

    @Inject lateinit var workManager: WorkManager

    @Inject lateinit var filterUseCase: FilterStateUseCase

    @Inject lateinit var launchTargetResolver: LaunchTargetResolver

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("RLog", "onCreate: ${ProfileInstallerInitializer().create(this)}")

        enableEdgeToEdge()

        // Set the language
        if (Build.VERSION.SDK_INT < 33) {
            LanguagesPreference.fromValue(languages).let { LanguagesPreference.setLocale(it) }
        }

        // Workaround for https://github.com/ReadYouApp/ReadYou/issues/312: increase cursor window
        // size
        try {
            val field: Field = CursorWindow::class.java.getDeclaredField("sCursorWindowSize")
            field.isAccessible = true
            field.set(null, 100 * 1024 * 1024) // 100MB is the new cursor window size
        } catch (e: Exception) {
            Log.e("RLog", "Unable to increase cursor window size: ${e.printStackTrace()}")
        }

        val requestPermissionLauncher =
            this.registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted
                ->
                if (isGranted) {} else { // Permission denied }
                }
            }

        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                !NotificationManagerCompat.from(this).areNotificationsEnabled()
        ) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            AccountSettingsProvider(accountService = accountService) {
                settingsProvider.ProvidesSettings {
                    val subscribeViewModel: SubscribeViewModel = hiltViewModel()

                    ProvideCompositionLocals {
                        AppTheme(useDarkTheme = LocalDarkTheme.current.isDarkTheme()) {
                            val isFirstLaunch = remember { isFirstLaunch }
                            val initialPage = remember { initialPage }
                            val startDestination = remember {
                                if (isFirstLaunch) listOf(Route.Startup)
                                else if (initialPage == InitialPagePreference.FlowPage.value)
                                    listOf(Route.Feeds, Route.Reading(null))
                                else listOf(Route.Feeds)
                            }

                            val backStack = rememberNavBackStack(*startDestination.toTypedArray())

                            NewIntentHandlerEffect(backStack, subscribeViewModel)
                            AppEntry(backStack)
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun NewIntentHandlerEffect(
        backStack: NavBackStack<NavKey>,
        subscribeViewModel: SubscribeViewModel,
    ) {
        val scope = rememberCoroutineScope()
        DisposableEffect(backStack) {
            var navigationJob: Job? = null
            val listener =
                Consumer<Intent> { intent ->
                    intent.consumeLaunchAction()?.let { action ->
                        navigationJob?.cancel()
                        navigationJob = scope.launch {
                            when (action) {
                                is LaunchAction.OpenArticle, is LaunchAction.OpenArticleList -> {
                                    val target = launchTargetResolver.resolve(action) ?: return@launch
                                    accountService.switch(target.account)
                                    filterUseCase.updateFilterState(
                                        feed = target.feed,
                                        group = target.group,
                                        filter = Filter.Unread,
                                        searchContent = null,
                                    )
                                    backStack.clear()
                                    backStack.add(Route.Feeds)
                                    backStack.add(Reading(articleId = target.articleId))
                                }
                                is LaunchAction.Subscribe -> {
                                    backStack.clear()
                                    backStack.add(Route.Feeds)
                                    subscribeViewModel.handleSharedUrlFromIntent(action.url)
                                }
                            }
                        }
                    }
                }
            listener.accept(intent) // consume the launch intent as well
            addOnNewIntentListener(listener)
            onDispose {
                navigationJob?.cancel()
                removeOnNewIntentListener(listener)
            }
        }
    }

    override fun onResume() {
        WidgetUpdateWorker.enqueueOneTimeWork(workManager)
        super.onResume()
    }
}

sealed interface LaunchAction {
    data class Subscribe(val url: String) : LaunchAction

    data class OpenArticle(
        val articleId: String,
        val feedId: String?,
        val groupId: String?,
        val accountId: Int? = null,
    ) : LaunchAction

    data class OpenArticleList(val accountId: Int?, val feedId: String?, val groupId: String?) :
        LaunchAction
}

internal fun Intent.consumeLaunchAction(): LaunchAction? {
    return when (action) {
        Intent.ACTION_VIEW -> {
            dataString?.let {
                data = null
                action = null
                LaunchAction.Subscribe(it)
            }
        }

        Intent.ACTION_SEND -> {
            getStringExtra(Intent.EXTRA_TEXT)
                ?.also { removeExtra(Intent.EXTRA_TEXT) }
                ?.let { LaunchAction.Subscribe(it) }
        }

        else -> {
            val articleId =
                getStringExtra(ExtraName.ARTICLE_ID)?.also { removeExtra(ExtraName.ARTICLE_ID) }
            val feedId = getStringExtra(ExtraName.FEED_ID)?.also { removeExtra(ExtraName.FEED_ID) }
            val groupId =
                getStringExtra(ExtraName.GROUP_ID)?.also { removeExtra(ExtraName.GROUP_ID) }
            val accountId = getIntExtra(ExtraName.ACCOUNT_ID, -1)

            if (accountId != -1) {
                removeExtra(ExtraName.ACCOUNT_ID)
            }

            if (articleId != null) {
                LaunchAction.OpenArticle(articleId, feedId, groupId, accountId.takeIf { it > 0 })
            } else if (feedId != null || groupId != null || accountId != -1) {
                LaunchAction.OpenArticleList(
                    accountId = if (accountId != -1) accountId else null,
                    feedId = feedId,
                    groupId = groupId,
                )
            } else {
                null
            }
        }
    }
}
