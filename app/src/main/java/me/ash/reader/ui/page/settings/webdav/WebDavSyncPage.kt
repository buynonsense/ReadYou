package me.ash.reader.ui.page.settings.webdav

import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import kotlinx.serialization.Serializable
import me.ash.reader.R
import me.ash.reader.domain.service.backup.*
import me.ash.reader.ui.component.base.RYSwitch
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.component.base.Tips
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.ext.showToastLong
import me.ash.reader.ui.motion.materialSharedAxisXIn
import me.ash.reader.ui.motion.materialSharedAxisXOut
import me.ash.reader.ui.page.settings.SelectableSettingGroupItem
import me.ash.reader.ui.page.settings.SettingItem

@Serializable
private enum class WebDavDestination : NavKey { Overview, Connection, Backup, Versions, Recovery, History }

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

    // Share the entry's ViewModel so navigation preserves the account and
    // the active operation, including its conflict resolution and retry.
    val backStack = rememberNavBackStack(WebDavDestination.Overview)
    val navigateBack: () -> Unit = {
        if (backStack.size > 1) backStack.removeLastOrNull() else onBack()
    }
    var generation by rememberSaveable { mutableStateOf<String?>(null) }
    var restoreOptions by rememberSaveable { mutableStateOf(false) }
    var replaceConfirmation by rememberSaveable { mutableStateOf(false) }
    var uploadConfirmation by rememberSaveable { mutableStateOf(false) }
    var recoveryId by rememberSaveable { mutableStateOf<String?>(null) }
    val requestRestore: (String?) -> Unit = { generation = it; restoreOptions = true }

    NavDisplay(
        modifier = Modifier.fillMaxSize(),
        backStack = backStack,
        entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator()),
        onBack = navigateBack,
        transitionSpec = {
            materialSharedAxisXIn(initialOffsetX = { (it * 0.10f).toInt() }) togetherWith
                materialSharedAxisXOut(targetOffsetX = { -(it * 0.10f).toInt() })
        },
        popTransitionSpec = {
            materialSharedAxisXIn(initialOffsetX = { -(it * 0.10f).toInt() }) togetherWith
                materialSharedAxisXOut(targetOffsetX = { (it * 0.10f).toInt() })
        },
        predictivePopTransitionSpec = {
            materialSharedAxisXIn(initialOffsetX = { -(it * 0.10f).toInt() }) togetherWith
                materialSharedAxisXOut(targetOffsetX = { (it * 0.10f).toInt() })
        },
        entryProvider = { destination ->
            NavEntry(destination) {
                when (destination) {
                    WebDavDestination.Overview -> WebDavOverviewPage(state, viewModel, onBack,
                        onConnection = { backStack.add(WebDavDestination.Connection) },
                        onBackup = { backStack.add(WebDavDestination.Backup) },
                        onHistory = { backStack.add(WebDavDestination.History) })
                    WebDavDestination.Connection -> WebDavConnectionPage(state, viewModel, navigateBack)
                    WebDavDestination.Backup -> WebDavBackupPage(state, viewModel, navigateBack,
                        onUpload = { uploadConfirmation = true },
                        onRestore = { requestRestore(null) },
                        onVersions = { backStack.add(WebDavDestination.Versions) },
                        onRecovery = { backStack.add(WebDavDestination.Recovery) })
                    WebDavDestination.Versions -> WebDavVersionsPage(state, viewModel, navigateBack, requestRestore)
                    WebDavDestination.Recovery -> WebDavRecoveryPage(state, viewModel, navigateBack,
                        onRestore = { recoveryId = it })
                    WebDavDestination.History -> WebDavHistoryPage(state, viewModel, navigateBack)
                }
            }
        },
    )

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
    if (replaceConfirmation || recoveryId != null) AlertDialog(
        onDismissRequest = { replaceConfirmation = false; recoveryId = null },
        title = { Text(stringResource(R.string.webdav_restore_replace)) }, text = { Text(stringResource(R.string.webdav_replace_confirm)) },
        confirmButton = { TextButton(onClick = {
            val selectedRecovery = recoveryId
            replaceConfirmation = false; recoveryId = null
            if (selectedRecovery != null) viewModel.restoreRecovery(selectedRecovery) else viewModel.restore(RestoreMode.REPLACE, generation)
        }) { Text(stringResource(R.string.confirm)) } },
        dismissButton = { TextButton(onClick = { replaceConfirmation = false; recoveryId = null }) { Text(stringResource(R.string.cancel)) } })
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
}

@Composable
private fun WebDavOverviewPage(
    state: WebDavSyncViewModel.WebDavUiState,
    viewModel: WebDavSyncViewModel,
    onBack: () -> Unit,
    onConnection: () -> Unit,
    onBackup: () -> Unit,
    onHistory: () -> Unit,
) {
    val editable = state.configurationLoaded && !state.isLoading
    val config = state.config
    WebDavSettingsScaffold(R.string.webdav_sync, state, viewModel::retry, onBack, showResult = true) {
        item {
            SelectableSettingGroupItem(title = stringResource(R.string.connection),
                desc = if (config.configured) config.serverUrl else stringResource(R.string.webdav_connection_desc),
                icon = Icons.Outlined.Cloud, onClick = onConnection)
            Spacer(Modifier.height(24.dp))
            Subtitle(Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.webdav_sync_section))
            if (state.configurationLoaded && !state.supportsRestore) Tips(text = stringResource(R.string.webdav_remote_account_limit))
            SettingItem(title = stringResource(R.string.webdav_sync_now), desc = stringResource(R.string.webdav_sync_now_desc),
                enabled = editable && config.configured && state.supportsRestore, onClick = viewModel::syncNow)
            SettingItem(title = stringResource(R.string.webdav_auto_sync), enabled = editable && state.supportsRestore,
                onClick = { viewModel.saveConfig(config.copy(autoSync = !config.autoSync)) }) {
                RYSwitch(activated = config.autoSync, enable = editable && state.supportsRestore) {
                    viewModel.saveConfig(config.copy(autoSync = !config.autoSync))
                }
            }
            SettingItem(title = stringResource(R.string.webdav_last_sync), desc = formatWebDavTime(state.lastSyncTime),
                enabled = false, onClick = {})
            Spacer(Modifier.height(24.dp))
            Subtitle(Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.advanced))
            SelectableSettingGroupItem(title = stringResource(R.string.webdav_backup_section),
                desc = stringResource(R.string.webdav_backup_navigation_desc), icon = Icons.Outlined.Backup, onClick = onBackup)
            SelectableSettingGroupItem(title = stringResource(R.string.webdav_history),
                desc = stringResource(R.string.webdav_history_retention, WebDavBackupEngine.HISTORY_LIMIT),
                icon = Icons.Outlined.History, onClick = onHistory)
        }
    }
}
