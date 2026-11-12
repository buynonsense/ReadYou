package me.ash.reader.ui.page.settings.webdav

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.ui.component.base.RYSwitch
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.component.base.Tips
import me.ash.reader.ui.page.settings.SettingItem

@Composable
internal fun WebDavBackupPage(
    state: WebDavSyncViewModel.WebDavUiState,
    viewModel: WebDavSyncViewModel,
    onBack: () -> Unit,
    onUpload: () -> Unit,
    onRestore: () -> Unit,
    onVersions: () -> Unit,
    onRecovery: () -> Unit,
) {
    val config = state.config
    val editable = state.configurationLoaded && !state.isLoading
    val connected = editable && config.configured
    WebDavSettingsScaffold(R.string.webdav_backup_section, state, viewModel::retry, onBack, showResult = true) {
        item {
            Subtitle(Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.webdav_backup_options))
            SettingItem(title = stringResource(R.string.webdav_include_articles), desc = stringResource(R.string.webdav_include_articles_desc),
                enabled = editable, onClick = { viewModel.saveConfig(config.copy(includeArticles = !config.includeArticles)) }) {
                RYSwitch(activated = config.includeArticles, enable = editable) {
                    viewModel.saveConfig(config.copy(includeArticles = !config.includeArticles))
                }
            }
            SettingItem(title = stringResource(R.string.webdav_include_content), desc = stringResource(R.string.webdav_include_content_desc),
                enabled = editable && config.includeArticles, onClick = { viewModel.saveConfig(config.copy(includeFullContent = !config.includeFullContent)) }) {
                RYSwitch(activated = config.includeFullContent && config.includeArticles, enable = editable && config.includeArticles) {
                    viewModel.saveConfig(config.copy(includeFullContent = !config.includeFullContent))
                }
            }
            Tips(text = stringResource(R.string.webdav_backup_scope_desc))
            Spacer(Modifier.height(24.dp))
            Subtitle(Modifier.padding(horizontal = 24.dp), text = stringResource(R.string.webdav_backup_actions))
            SettingItem(title = stringResource(R.string.webdav_upload), desc = stringResource(R.string.webdav_upload_desc),
                enabled = connected, onClick = onUpload)
            SettingItem(title = stringResource(R.string.webdav_restore), desc = stringResource(R.string.webdav_restore_desc),
                enabled = connected && state.supportsRestore, onClick = onRestore)
            SettingItem(title = stringResource(R.string.webdav_versions), desc = stringResource(R.string.webdav_versions_desc),
                enabled = connected, onClick = onVersions)
            SettingItem(title = stringResource(R.string.webdav_recovery_copies), desc = stringResource(R.string.webdav_recovery_navigation_desc),
                onClick = onRecovery)
        }
    }
}

@Composable
internal fun WebDavVersionsPage(
    state: WebDavSyncViewModel.WebDavUiState,
    viewModel: WebDavSyncViewModel,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
) {
    val connected = state.configurationLoaded && !state.isLoading && state.config.configured
    LaunchedEffect(Unit) { viewModel.loadVersions() }
    WebDavSettingsScaffold(R.string.webdav_versions, state, viewModel::retry, onBack,
        description = stringResource(R.string.webdav_versions_desc), showResult = true) {
        item {
            SettingItem(title = stringResource(R.string.webdav_load_versions), enabled = connected, onClick = viewModel::loadVersions)
            if (state.versionsLoaded && state.versions.isEmpty()) Tips(text = stringResource(R.string.webdav_no_backup))
        }
        items(state.versions, key = { it.generation }) { version ->
            SettingItem(title = formatWebDavTime(version.time), desc = stringResource(R.string.webdav_restore_version),
                enabled = connected && state.supportsRestore, onClick = { onRestore(version.generation) })
        }
    }
}

@Composable
internal fun WebDavRecoveryPage(
    state: WebDavSyncViewModel.WebDavUiState,
    viewModel: WebDavSyncViewModel,
    onBack: () -> Unit,
    onRestore: (String) -> Unit,
) {
    LaunchedEffect(Unit) { viewModel.refreshLocalData() }
    WebDavSettingsScaffold(R.string.webdav_recovery_copies, state, viewModel::retry, onBack, showResult = true) {
        item {
            Tips(text = stringResource(R.string.webdav_recovery_copies_desc))
            if (!state.isLoading && state.recoveryCopies.isEmpty()) Tips(text = stringResource(R.string.webdav_no_recovery_copies))
        }
        items(state.recoveryCopies, key = { it.id }) { copy ->
            SettingItem(title = formatWebDavTime(copy.time), desc = stringResource(R.string.webdav_restore_local),
                enabled = state.configurationLoaded && !state.isLoading && state.supportsRestore, onClick = { onRestore(copy.id) })
        }
    }
}
