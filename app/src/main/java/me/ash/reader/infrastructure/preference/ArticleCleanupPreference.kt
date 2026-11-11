package me.ash.reader.infrastructure.preference

import android.content.Context
import androidx.compose.runtime.compositionLocalOf
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.ash.reader.R
import me.ash.reader.ui.ext.DataStoreKey
import me.ash.reader.ui.ext.DataStoreKey.Companion.autoCleanupArticles
import me.ash.reader.ui.ext.DataStoreKey.Companion.autoCleanupIncludeUnread
import me.ash.reader.ui.ext.DataStoreKey.Companion.autoCleanupKeepStarred
import me.ash.reader.ui.ext.DataStoreKey.Companion.autoCleanupRetention
import me.ash.reader.ui.ext.dataStore
import me.ash.reader.ui.ext.put

val LocalAutoCleanupArticles =
    compositionLocalOf<AutoCleanupArticlesPreference> { AutoCleanupArticlesPreference.default }
val LocalAutoCleanupRetention =
    compositionLocalOf<ArticleCleanupRetentionPreference> { ArticleCleanupRetentionPreference.default }
val LocalAutoCleanupIncludeUnread =
    compositionLocalOf<AutoCleanupIncludeUnreadPreference> { AutoCleanupIncludeUnreadPreference.default }
val LocalAutoCleanupKeepStarred =
    compositionLocalOf<AutoCleanupKeepStarredPreference> { AutoCleanupKeepStarredPreference.default }

sealed class AutoCleanupArticlesPreference(val value: Boolean) : Preference() {
    data object ON : AutoCleanupArticlesPreference(true)
    data object OFF : AutoCleanupArticlesPreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(autoCleanupArticles, value)
        }
    }

    fun toggle(context: Context, scope: CoroutineScope) = scope.launch {
        context.dataStore.put(autoCleanupArticles, !value)
    }

    companion object {
        val default = OFF
        val values = listOf(ON, OFF)

        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[autoCleanupArticles]?.key as Preferences.Key<Boolean>]) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}

operator fun AutoCleanupArticlesPreference.not(): AutoCleanupArticlesPreference =
    when (this) {
        AutoCleanupArticlesPreference.ON -> AutoCleanupArticlesPreference.OFF
        AutoCleanupArticlesPreference.OFF -> AutoCleanupArticlesPreference.ON
    }

sealed class ArticleCleanupRetentionPreference(val value: Long) : Preference() {

    object For1Month : ArticleCleanupRetentionPreference(2592000000L)
    object For3Months : ArticleCleanupRetentionPreference(7776000000L)
    object For6Months : ArticleCleanupRetentionPreference(15552000000L)
    object For1Year : ArticleCleanupRetentionPreference(31536000000L)
    object For2Years : ArticleCleanupRetentionPreference(63072000000L)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(autoCleanupRetention, value)
        }
    }

    fun toDesc(context: Context): String =
        when (this) {
            For1Month -> context.getString(R.string.retention_1_month)
            For3Months -> context.getString(R.string.retention_3_months)
            For6Months -> context.getString(R.string.retention_6_months)
            For1Year -> context.getString(R.string.retention_1_year)
            For2Years -> context.getString(R.string.retention_2_years)
        }

    companion object {
        val default = For6Months
        val values = listOf(For1Month, For3Months, For6Months, For1Year, For2Years)

        fun fromPreferences(preferences: Preferences): ArticleCleanupRetentionPreference {
            val value =
                preferences[DataStoreKey.keys[autoCleanupRetention]?.key as Preferences.Key<Long>]
            return values.find { it.value == value } ?: default
        }
    }
}

sealed class AutoCleanupIncludeUnreadPreference(val value: Boolean) : Preference() {
    data object ON : AutoCleanupIncludeUnreadPreference(true)
    data object OFF : AutoCleanupIncludeUnreadPreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(autoCleanupIncludeUnread, value)
        }
    }

    fun toggle(context: Context, scope: CoroutineScope) = scope.launch {
        context.dataStore.put(autoCleanupIncludeUnread, !value)
    }

    companion object {
        val default = OFF
        val values = listOf(ON, OFF)

        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[autoCleanupIncludeUnread]?.key as Preferences.Key<Boolean>]) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}

operator fun AutoCleanupIncludeUnreadPreference.not(): AutoCleanupIncludeUnreadPreference =
    when (this) {
        AutoCleanupIncludeUnreadPreference.ON -> AutoCleanupIncludeUnreadPreference.OFF
        AutoCleanupIncludeUnreadPreference.OFF -> AutoCleanupIncludeUnreadPreference.ON
    }

sealed class AutoCleanupKeepStarredPreference(val value: Boolean) : Preference() {
    data object ON : AutoCleanupKeepStarredPreference(true)
    data object OFF : AutoCleanupKeepStarredPreference(false)

    override fun put(context: Context, scope: CoroutineScope) {
        scope.launch {
            context.dataStore.put(autoCleanupKeepStarred, value)
        }
    }

    fun toggle(context: Context, scope: CoroutineScope) = scope.launch {
        context.dataStore.put(autoCleanupKeepStarred, !value)
    }

    companion object {
        val default = ON
        val values = listOf(ON, OFF)

        fun fromPreferences(preferences: Preferences) =
            when (preferences[DataStoreKey.keys[autoCleanupKeepStarred]?.key as Preferences.Key<Boolean>]) {
                true -> ON
                false -> OFF
                else -> default
            }
    }
}

operator fun AutoCleanupKeepStarredPreference.not(): AutoCleanupKeepStarredPreference =
    when (this) {
        AutoCleanupKeepStarredPreference.ON -> AutoCleanupKeepStarredPreference.OFF
        AutoCleanupKeepStarredPreference.OFF -> AutoCleanupKeepStarredPreference.ON
    }
