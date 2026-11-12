package me.ash.reader.domain.service.backup

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import me.ash.reader.infrastructure.preference.LanguagesPreference
import me.ash.reader.ui.ext.PreferencesKey
import me.ash.reader.ui.ext.dataStore

/** An allowlist: account identities, credentials, TLS policies and device paths never leave the device. */
internal object WebDavSettings {
    private val names = setOf(
        "themeIndex", "customPrimaryColor", "darkTheme", "amoledDarkTheme", "basicFonts",
        "feedsFilterBarStyle", "feedsFilterBarPadding", "feedsFilterBarTonalElevation",
        "feedsTopBarTonalElevation", "feedsGroupListExpand", "feedsGroupListTonalElevation",
        "flowFilterBarStyle", "flowFilterBarPadding", "flowFilterBarTonalElevation", "flowTopBarTonalElevation",
        "flowArticleListFeedIcon", "flowArticleListFeedName", "flowArticleListImage", "flowArticleListDescription",
        "flowArticleListTime", "flowArticleListDateStickyHeader", "flowArticleListTonalElevation",
        "flowArticleListReadStatusIndicator", "flowArticleListSortUnreadArticles",
        "readingRender", "readingBoldCharacters", "readingTheme", "readingPageTonalElevation",
        "readingAutoHideToolbar", "readingTextFontSize", "readingTextLineHeight", "readingTextLetterSpacing",
        "readingTextHorizontalPadding", "readingTextAlign", "readingTextBold", "readingTitleAlign",
        "readingSubheadAlign", "readingFonts", "readingTitleBold", "readingSubheadBold",
        "readingTitleUpperCase", "readingSubheadUpperCase", "readingImageHorizontalPadding",
        "readingImageRoundedCorners", "readingImageMaximize", "initialPage", "initialFilter",
        "swipeStartAction", "swipeEndAction", "markAsReadOnScroll", "hideEmptyGroups",
        "pullToLoadNextFeed", "pullToSwitchArticle", "openLink", "sharedContent", "languages",
    )
    private val keys = PreferencesKey.keys.filterKeys { it in names }

    fun records(preferences: Preferences, time: Long): List<BackupRecord> = keys.values.map { setting ->
        BackupRecord(key("setting", setting.name), "setting",
            payload("name" to setting.name, "value" to preferences[setting.key]), time)
    }

    suspend fun scan(context: Context, time: Long) = records(context.dataStore.data.first(), time)

    fun validate(record: BackupRecord) {
        val name = record.payload.string("name")
        val setting = requireNotNull(keys[name]) { "Unsupported app setting: $name" }
        require(record.key == key("setting", setting.name) && !record.deleted) { "Invalid app setting identity" }
        require(record.payload.keySet().all { it in setOf("name", "value") }) { "Invalid app setting fields" }
        val value = record.payload["value"]?.takeUnless { it.isJsonNull } ?: return
        require(value.isJsonPrimitive) { "Invalid app setting value" }
        val primitive = value.asJsonPrimitive
        require(when (setting) {
            is PreferencesKey.BooleanKey -> primitive.isBoolean
            is PreferencesKey.StringKey -> primitive.isString && primitive.asString.length <= 4_096
            is PreferencesKey.IntKey -> primitive.isNumber && runCatching { primitive.asBigDecimal.intValueExact() }.isSuccess
            is PreferencesKey.LongKey -> primitive.isNumber && runCatching { primitive.asBigDecimal.longValueExact() }.isSuccess
            is PreferencesKey.FloatKey -> primitive.isNumber && primitive.asFloat.isFinite()
        }) { "Invalid value for app setting: $name" }
    }

    suspend fun apply(context: Context, workspace: BackupWorkspace) {
        val changes = workspace.records("merged", "setting").toList()
        if (changes.isEmpty()) return // Legacy backups do not reset app settings.
        changes.forEach(::validate)
        var language: LanguagesPreference? = null
        context.dataStore.edit { preferences ->
            val fresh = records(preferences, 0).associateBy { it.key }
            for (record in workspace.records("local", "setting")) {
                check(fresh[record.key]?.hash == record.hash) { "App settings changed during WebDAV sync. Retry to include those changes." }
            }
            for (record in changes) {
                if (fresh[record.key]?.hash == record.hash) continue
                val setting = keys.getValue(record.payload.string("name")!!)
                write(preferences, setting, record.payload["value"]?.takeUnless { it.isJsonNull })
                if (setting.name == "languages") language = LanguagesPreference.fromPreferences(preferences)
            }
        }
        language?.let { withContext(Dispatchers.Main) { LanguagesPreference.setLocale(it) } }
    }

    private fun write(preferences: MutablePreferences, setting: PreferencesKey, value: JsonElement?) {
        if (value == null) { preferences.remove(setting.key); return }
        when (setting) {
            is PreferencesKey.BooleanKey -> preferences[setting.key] = value.asBoolean
            is PreferencesKey.StringKey -> preferences[setting.key] = value.asString
            is PreferencesKey.IntKey -> preferences[setting.key] = value.asInt
            is PreferencesKey.LongKey -> preferences[setting.key] = value.asLong
            is PreferencesKey.FloatKey -> preferences[setting.key] = value.asFloat
        }
    }
}
