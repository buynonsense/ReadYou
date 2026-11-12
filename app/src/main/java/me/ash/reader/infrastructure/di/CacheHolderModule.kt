package me.ash.reader.infrastructure.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import me.ash.reader.domain.service.RssService
import me.ash.reader.domain.data.DiffMapHolder
import me.ash.reader.domain.service.AccountService
import me.ash.reader.domain.service.ArticleStateOutbox
import androidx.work.WorkManager
import me.ash.reader.infrastructure.preference.SettingsProvider
import me.ash.reader.infrastructure.rss.ReaderCacheHelper
import me.ash.reader.infrastructure.rss.RssHelper
import me.ash.reader.domain.repository.ArticleSearchDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object CacheHolderModule {
    @Provides
    @Singleton
    fun provideDiffMapHolder(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
        @IODispatcher ioDispatcher: CoroutineDispatcher,
        accountService: AccountService,
        outbox: ArticleStateOutbox,
        workManager: WorkManager,
    ): DiffMapHolder {
        return DiffMapHolder(
            context = context, applicationScope, ioDispatcher, accountService, outbox, workManager
        )
    }

    @Provides
    @Singleton
    fun provideCacheHelper(
        @ApplicationContext context: Context,
        @IODispatcher ioDispatcher: CoroutineDispatcher,
        rssHelper: RssHelper,
        articleSearchDao: ArticleSearchDao,
    ): ReaderCacheHelper = ReaderCacheHelper(
        context = context, ioDispatcher = ioDispatcher,
        rssHelper = rssHelper,
        articleSearchDao = articleSearchDao,
    )
}
