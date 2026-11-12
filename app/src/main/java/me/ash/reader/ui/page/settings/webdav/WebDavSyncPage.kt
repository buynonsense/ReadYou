package me.ash.reader.ui.page.settings.webdav

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.service.WebDavSyncService
import me.ash.reader.domain.service.backup.*
import me.ash.reader.ui.component.base.*
import me.ash.reader.ui.ext.DateFormat
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.ext.showToastLong
import me.ash.reader.ui.ext.toString
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.page.settings.accounts.connection.ServerSecuritySettings
import me.ash.reader.ui.theme.palette.onLight
import java.util.Date

@Composable
fun WebDavSyncPage(viewModel: WebDavSyncViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val state = viewModel.uiState.collectAsStateValue()
    val connectionTestSuccess = stringResource(R.string.webdav_test_success)
    val connectionTestFailure = stringResource(R.string.webdav_test_failed, state.connectionTestResult?.error.orEmpty())
    LaunchedEffect(state.connectionTestResult) {
        state.connectionTestResult?.let { result ->
            if (result.error == null) context.showToast(connectionTestSuccess)
            else context.showToastLong(connectionTestFailure)
            viewModel.consumeConnectionTestResult()
        }
    }
    val config = state.config
    val editable = state.configurationLoaded && !state.isLoading
    val connected = editable && config.configured
    val restorable = connected && state.supportsRestore
    var editingField by remember { mutableStateOf<String?>(null) }
    var editingValue by remember { mutableStateOf("") }
    var restoreOptions by remember { mutableStateOf(false) }
    var generation by remember { mutableStateOf<String?>(null) }
    var replaceConfirmation by remember { mutableStateOf(false) }
    var uploadConfirmation by remember { mutableStateOf(false) }
    var recoverySelection by remember { mutableStateOf<BackupRecoveryCopy?>(null) }
    var historySelection by remember { mutableStateOf<BackupHistoryEntry?>(null) }
    fun edit(field: String, value: String) { editingValue = value; editingField = field }

    RYScaffold(
        containerColor = MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
        navigationIcon = {
            FeedbackIconButton(imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back), tint = MaterialTheme.colorScheme.onSurface, onClick = onBack)
        },
    ) {
        LazyColumn {
            item {
                DisplayText(text = stringResource(R.string.webdav_sync), desc = "")
                Spacer(Modifier.height(16.dp))
                if (state.isLoading || state.progress != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                    state.progress?.let { progress ->
                        Tips(text = stageTitle(progress.stage) + if (progress.total > 0) " (${progress.completed}/${progress.total})" else "")
                    }
                }
                state.error?.let { Tips(text = it) }
                if (state.canRetry) SettingItem(title = stringResource(R.string.retry), enabled = editable, onClick = viewModel::retry)
                when (state.completedAction) {
                    WebDavSyncViewModel.Action.BACKUP -> Tips(text = stringResource(R.string.webdav_backup_success))
                    else -> state.result?.let { Tips(text = stringResource(R.string.webdav_sync_success, it.groupsAdded, it.feedsAdded, it.articlesAdded, it.articlesUpdated)) }
                }
                Subtitle(Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.connection))
                SettingItem(title = stringResource(R.string.server_url), desc = config.serverUrl, enabled = editable,
                    onClick = { edit("server", config.serverUrl) })
                SettingItem(title = stringResource(R.string.username), desc = config.username, enabled = editable,
                    onClick = { edit("username", config.username) })
                SettingItem(title = stringResource(R.string.password), desc = if (config.password.isNotEmpty()) "••••••••" else "",
                    enabled = !state.isLoading, onClick = { edit("password", config.password) })
                SettingItem(title = stringResource(R.string.webdav_remote_path), desc = config.remotePath, enabled = editable,
                    onClick = { edit("path", config.remotePath) })
                SettingItem(title = stringResource(R.string.webdav_account_id), desc = config.namespaceId, enabled = editable,
                    onClick = { edit("namespace", config.namespaceId) })
                Tips(text = stringResource(R.string.webdav_account_id_desc))
                ServerSecuritySettings(config.serverUrl)
                SettingItem(title = stringResource(R.string.webdav_test), enabled = connected, onClick = viewModel::testConnection)
                Spacer(Modifier.height(24.dp))
                Subtitle(Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.webdav_sync_section))
                if (state.configurationLoaded && !state.supportsRestore) Tips(text = stringResource(R.string.webdav_remote_account_limit))
                SettingItem(title = stringResource(R.string.webdav_sync_now), desc = stringResource(R.string.webdav_sync_now_desc),
                    enabled = restorable, onClick = viewModel::syncNow)
                SettingItem(title = stringResource(R.string.webdav_auto_sync), enabled = editable && state.supportsRestore,
                    onClick = { viewModel.saveConfig(config.copy(autoSync = !config.autoSync)) }) {
                    RYSwitch(activated = config.autoSync, enable = editable && state.supportsRestore) { viewModel.saveConfig(config.copy(autoSync = !config.autoSync)) }
                }
                SettingItem(title = stringResource(R.string.webdav_last_sync), desc = formatTime(state.lastSyncTime), enabled = false, onClick = {})
                Tips(text = stringResource(R.string.webdav_tips))
                Spacer(Modifier.height(24.dp))
                Subtitle(Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.webdav_backup_section))
                Tips(text = stringResource(R.string.webdav_backup_scope_desc))
                SettingItem(title = stringResource(R.string.webdav_upload), desc = stringResource(R.string.webdav_upload_desc),
                    enabled = connected, onClick = { uploadConfirmation = true })
                SettingItem(title = stringResource(R.string.webdav_restore), desc = stringResource(R.string.webdav_restore_desc),
                    enabled = restorable, onClick = { generation = null; restoreOptions = true })
                SettingItem(title = stringResource(R.string.webdav_include_articles), desc = stringResource(R.string.webdav_include_articles_desc),
                    enabled = editable, onClick = { viewModel.saveConfig(config.copy(includeArticles = !config.includeArticles)) }) {
                    RYSwitch(activated = config.includeArticles, enable = editable) { viewModel.saveConfig(config.copy(includeArticles = !config.includeArticles)) }
                }
                SettingItem(title = stringResource(R.string.webdav_include_content), desc = stringResource(R.string.webdav_include_content_desc),
                    enabled = editable && config.includeArticles, onClick = { viewModel.saveConfig(config.copy(includeFullContent = !config.includeFullContent)) }) {
                    RYSwitch(activated = config.includeFullContent && config.includeArticles, enable = editable && config.includeArticles) { viewModel.saveConfig(config.copy(includeFullContent = !config.includeFullContent)) }
                }
                Spacer(Modifier.height(24.dp))
                Subtitle(Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.webdav_versions))
                SettingItem(title = stringResource(R.string.webdav_load_versions), enabled = connected, onClick = viewModel::loadVersions)
                if (state.versionsLoaded && state.versions.isEmpty()) Tips(text = stringResource(R.string.webdav_no_backup))
            }
            items(state.versions, key = { it.generation }) { version ->
                SettingItem(title = formatTime(version.time), desc = version.generation, enabled = restorable,
                    onClick = { generation = version.generation; restoreOptions = true })
            }
            item {
                Spacer(Modifier.height(24.dp))
                Subtitle(Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.webdav_recovery_copies))
                Tips(text = stringResource(R.string.webdav_recovery_copies_desc))
            }
            items(state.recoveryCopies, key = { it.id }) { copy ->
                SettingItem(title = formatTime(copy.time), desc = stringResource(R.string.webdav_restore_local),
                    enabled = editable && state.supportsRestore, onClick = { recoverySelection = copy })
            }
            item {
                Spacer(Modifier.height(24.dp))
                Subtitle(Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.webdav_history))
            }
            items(state.history) { entry ->
                SettingItem(title = actionTitle(entry.action),
                    desc = formatTime(entry.time) + " · " + stringResource(if (entry.success) R.string.webdav_operation_success else R.string.webdav_operation_failed),
                    onClick = { historySelection = entry })
            }
            item { Spacer(Modifier.height(24.dp)); Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)) }
        }
    }

    val field = editingField
    if (field != null) {
        val title = stringResource(when (field) {
            "server" -> R.string.server_url
            "username" -> R.string.username
            "password" -> R.string.password
            "path" -> R.string.webdav_remote_path
            else -> R.string.webdav_account_id
        })
        TextFieldDialog(visible = true, title = title, value = editingValue, isPassword = field == "password",
            onValueChange = { editingValue = it }, onDismissRequest = { editingField = null }, onConfirm = { value ->
                if (field == "password" && !state.configurationLoaded) viewModel.reconnectPassword(value)
                else viewModel.saveConfig(when (field) {
                    "server" -> config.copy(serverUrl = value)
                    "username" -> config.copy(username = value)
                    "password" -> config.copy(password = value)
                    "path" -> config.copy(remotePath = value)
                    else -> config.copy(namespaceId = value.trim(), importLegacy = false)
                })
                editingField = null
            })
    }
    if (uploadConfirmation) AlertDialog(onDismissRequest = { uploadConfirmation = false },
        title = { Text(stringResource(R.string.webdav_upload)) }, text = { Text(stringResource(R.string.webdav_upload_confirm)) },
        confirmButton = { TextButton(onClick = { uploadConfirmation = false; viewModel.backup() }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { uploadConfirmation = false }) { Text(stringResource(R.string.cancel)) } })
    if (restoreOptions) AlertDialog(onDismissRequest = { restoreOptions = false },
        title = { Text(stringResource(R.string.webdav_restore)) }, text = { Text(stringResource(R.string.webdav_restore_modes_desc)) },
        confirmButton = {
            Column {
                TextButton(onClick = { restoreOptions = false; viewModel.restore(RestoreMode.MERGE, generation) }) { Text(stringResource(R.string.webdav_restore_merge)) }
                TextButton(onClick = { restoreOptions = false; replaceConfirmation = true }) { Text(stringResource(R.string.webdav_restore_replace)) }
            }
        }, dismissButton = { TextButton(onClick = { restoreOptions = false }) { Text(stringResource(R.string.cancel)) } })
    if (replaceConfirmation || recoverySelection != null) AlertDialog(
        onDismissRequest = { replaceConfirmation = false; recoverySelection = null },
        title = { Text(stringResource(R.string.webdav_restore_replace)) }, text = { Text(stringResource(R.string.webdav_replace_confirm)) },
        confirmButton = { TextButton(onClick = {
            val copy = recoverySelection
            replaceConfirmation = false; recoverySelection = null
            if (copy != null) viewModel.restoreRecovery(copy.id) else viewModel.restore(RestoreMode.REPLACE, generation)
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { replaceConfirmation = false; recoverySelection = null }) { Text(stringResource(R.string.cancel)) } })
    state.conflict?.let { conflict ->
        AlertDialog(onDismissRequest = viewModel::dismissConflict,
            title = { Text(stringResource(R.string.webdav_conflicts, conflict.total)) },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    Text(stringResource(R.string.webdav_conflicts_desc))
                    conflict.conflicts.forEach { row ->
                        Spacer(Modifier.height(12.dp))
                        Text(row.title, style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.webdav_conflict_local, row.local))
                        Text(stringResource(R.string.webdav_conflict_remote, row.remote))
                    }
                }
            },
            confirmButton = {
                Column {
                    TextButton(onClick = { viewModel.resolveConflict(ConflictResolution.KEEP_LOCAL) }) { Text(stringResource(R.string.webdav_keep_local)) }
                    TextButton(onClick = { viewModel.resolveConflict(ConflictResolution.KEEP_REMOTE) }) { Text(stringResource(R.string.webdav_keep_remote)) }
                    TextButton(onClick = { viewModel.resolveConflict(ConflictResolution.NEWEST) }) { Text(stringResource(R.string.webdav_keep_newest)) }
                }
            }, dismissButton = { TextButton(onClick = viewModel::dismissConflict) { Text(stringResource(R.string.cancel)) } })
    }
    historySelection?.let { entry ->
        AlertDialog(onDismissRequest = { historySelection = null }, title = { Text(actionTitle(entry.action)) },
            text = { Text(listOfNotNull(formatTime(entry.time), entry.generation, entry.detail,
                stringResource(if (entry.success) R.string.webdav_operation_success else R.string.webdav_operation_failed)).joinToString("\n")) },
            confirmButton = { TextButton(onClick = { historySelection = null }) { Text(stringResource(R.string.confirm)) } })
    }
}

@Composable
private fun formatTime(time: Long): String = if (time > 0) Date(time).toString(DateFormat.YYYY_MM_DD_HH_MM_SS) else stringResource(R.string.webdav_never_synced)

@Composable
private fun actionTitle(action: String): String = stringResource(when (action) {
    "backup" -> R.string.webdav_upload
    "restore" -> R.string.webdav_restore_merge
    "replace" -> R.string.webdav_restore_replace
    "recovery" -> R.string.webdav_restore_local
    else -> R.string.webdav_sync_now
})

@Composable
private fun stageTitle(stage: String): String = stringResource(when (stage) {
    "download" -> R.string.webdav_stage_download
    "verify" -> R.string.webdav_stage_verify
    "scan" -> R.string.webdav_stage_scan
    "upload" -> R.string.webdav_stage_upload
    "apply" -> R.string.webdav_stage_apply
    else -> R.string.syncing
})
