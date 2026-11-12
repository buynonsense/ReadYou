package me.ash.reader.infrastructure.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import me.ash.reader.domain.repository.AccountDao
import me.ash.reader.domain.repository.ArticleDao
import me.ash.reader.domain.repository.FeedDao
import me.ash.reader.domain.repository.GroupDao
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.AccountOperationLock
import me.ash.reader.domain.service.RssService
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.infrastructure.db.AndroidDatabase
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import androidx.work.WorkManager
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AccountServiceModule {
    @Provides
    @Singleton
    fun provideAccountService(
        @ApplicationContext context: Context,
        accountDao: AccountDao,
        groupDao: GroupDao,
        feedDao: FeedDao,
        articleDao: ArticleDao,
        @ApplicationScope coroutineScope: CoroutineScope,
        settingsProvider: SettingsProvider,
        database: AndroidDatabase,
        workManager: WorkManager,
        cacheHelper: ReaderCacheHelper,
        accountLock: AccountOperationLock,
    ): AccountService {
        return AccountService(
            context = context,
            accountDao = accountDao,
            groupDao = groupDao,
            feedDao = feedDao,
            articleDao = articleDao,
            coroutineScope = coroutineScope,
            settingsProvider = settingsProvider,
            database = database,
            workManager = workManager,
            cacheHelper = cacheHelper,
            accountLock = accountLock,
        )
    }
}
