package me.ash.reader.ui.page.settings.webdav

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import me.ash.reader.R
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.component.base.Tips
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.page.settings.accounts.connection.ServerSecuritySettings

private enum class ConnectionField { Server, Username, Password, Path }

@Composable
internal fun WebDavConnectionPage(
    state: WebDavSyncViewModel.WebDavUiState,
    viewModel: WebDavSyncViewModel,
    onBack: () -> Unit,
) {
    val config = state.config
    val editable = state.configurationLoaded && !state.isLoading
    var editingField by rememberSaveable { mutableStateOf<ConnectionField?>(null) }
    var editingValue by rememberSaveable { mutableStateOf("") }
    fun edit(field: ConnectionField, value: String) { editingValue = value; editingField = field }

    WebDavSettingsScaffold(R.string.connection, state, viewModel::retry, onBack) {
        item {
            SettingItem(title = stringResource(R.string.server_url), desc = config.serverUrl, enabled = editable,
                onClick = { edit(ConnectionField.Server, config.serverUrl) })
            SettingItem(title = stringResource(R.string.username), desc = config.username, enabled = editable,
                onClick = { edit(ConnectionField.Username, config.username) })
            SettingItem(title = stringResource(R.string.password), desc = if (config.password.isNotEmpty()) "••••••••" else "",
                enabled = !state.isLoading, onClick = { edit(ConnectionField.Password, config.password) })
            SettingItem(title = stringResource(R.string.webdav_remote_path), desc = config.remotePath, enabled = editable,
                onClick = { edit(ConnectionField.Path, config.remotePath) })
            ServerSecuritySettings(config.serverUrl)
            SettingItem(title = stringResource(R.string.webdav_test), enabled = editable && config.configured,
                onClick = viewModel::testConnection)
            Tips(text = stringResource(R.string.webdav_tips))
        }
    }

    editingField?.let { field ->
        val title = stringResource(when (field) {
            ConnectionField.Server -> R.string.server_url
            ConnectionField.Username -> R.string.username
            ConnectionField.Password -> R.string.password
            ConnectionField.Path -> R.string.webdav_remote_path
        })
        TextFieldDialog(visible = true, title = title, value = editingValue, isPassword = field == ConnectionField.Password,
            onValueChange = { editingValue = it }, onDismissRequest = { editingField = null }, onConfirm = { value ->
                if (field == ConnectionField.Password && !state.configurationLoaded) viewModel.reconnectPassword(value)
                else viewModel.saveConfig(when (field) {
                    ConnectionField.Server -> config.copy(serverUrl = value)
                    ConnectionField.Username -> config.copy(username = value)
                    ConnectionField.Password -> config.copy(password = value)
                    ConnectionField.Path -> config.copy(remotePath = value)
                })
                editingField = null
            })
    }
}
