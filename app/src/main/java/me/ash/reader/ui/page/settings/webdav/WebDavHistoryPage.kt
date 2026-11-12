package me.ash.reader.ui.page.settings.webdav

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.service.backup.BackupHistoryEntry
import me.ash.reader.domain.service.backup.WebDavBackupEngine
import me.ash.reader.ui.component.base.Tips
import me.ash.reader.ui.page.settings.SettingItem

@Composable
internal fun WebDavHistoryPage(
    state: WebDavSyncViewModel.WebDavUiState,
    viewModel: WebDavSyncViewModel,
    onBack: () -> Unit,
) {
    var selection by remember { mutableStateOf<BackupHistoryEntry?>(null) }
    LaunchedEffect(Unit) { viewModel.refreshLocalData() }
    WebDavSettingsScaffold(R.string.webdav_history, state, viewModel::retry, onBack,
        description = stringResource(R.string.webdav_history_retention, WebDavBackupEngine.HISTORY_LIMIT)) {
        if (!state.isLoading && state.history.isEmpty()) {
            item { Tips(text = stringResource(R.string.webdav_history_empty)) }
        }
        items(state.history) { entry ->
            SettingItem(title = webDavActionTitle(entry.action),
                desc = formatWebDavTime(entry.time) + " · " + stringResource(if (entry.success) R.string.webdav_operation_success else R.string.webdav_operation_failed),
                onClick = { selection = entry })
        }
    }
    selection?.let { entry ->
        AlertDialog(onDismissRequest = { selection = null }, title = { Text(webDavActionTitle(entry.action)) },
            text = {
                Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                    Text(listOfNotNull(formatWebDavTime(entry.time), entry.generation, entry.detail,
                        stringResource(if (entry.success) R.string.webdav_operation_success else R.string.webdav_operation_failed)).joinToString("\n"))
                }
            },
            confirmButton = { TextButton(onClick = { selection = null }) { Text(stringResource(R.string.confirm)) } })
    }
}
