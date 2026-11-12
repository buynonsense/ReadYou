package me.ash.reader.infrastructure.preference

import android.content.Context
import com.google.gson.Gson
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** User-visible sync results survive process death; credentials never enter this store. */
@Singleton
class AccountSyncStatusStore @Inject constructor(@ApplicationContext context: Context) {
    data class Status(
        val running: Boolean = false,
        val stage: String = "",
        val completed: Int = 0,
        val total: Int = 0,
        val lastSuccess: Long = 0,
        val error: String? = null,
        val pendingStateError: String? = null,
        val webDavError: String? = null,
    )
    private val preferences = context.getSharedPreferences("account_sync_status", Context.MODE_PRIVATE)
    private val gson = Gson()
    private val _statuses = MutableStateFlow(preferences.all.mapNotNull { (key, value) ->
        val id = key.toIntOrNull() ?: return@mapNotNull null
        runCatching { gson.fromJson(value as String, Status::class.java) }.getOrNull()?.let { status ->
            id to if (status.running) status.copy(running = false, stage = "interrupted") else status
        }
    }.toMap())
    val statuses = _statuses.asStateFlow()

    @Synchronized private fun change(id: Int, block: (Status) -> Status) {
        val status = block(_statuses.value[id] ?: Status())
        _statuses.update { it + (id to status) }
        preferences.edit().putString(id.toString(), gson.toJson(status)).apply()
    }
    fun begin(id: Int) = change(id) { it.copy(running = true, stage = "rss", completed = 0, total = 0, error = null) }
    fun progress(id: Int, stage: String, completed: Int = 0, total: Int = 0) = change(id) { it.copy(stage = stage, completed = completed, total = total) }
    fun succeeded(id: Int) = change(id) { it.copy(running = false, stage = "", lastSuccess = System.currentTimeMillis(), error = null) }
    fun failed(id: Int, message: String?) = change(id) { it.copy(running = false, error = message?.take(1_024) ?: "Sync failed") }
    fun stateError(id: Int, message: String?) = change(id) { it.copy(pendingStateError = message?.take(1_024)) }
    fun webDavError(id: Int, message: String?) = change(id) { it.copy(webDavError = message?.take(1_024)) }
    fun current(id: Int) = _statuses.value[id]
}
