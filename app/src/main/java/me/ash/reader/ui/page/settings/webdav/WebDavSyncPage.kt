package me.ash.reader.ui.page.settings.webdav

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.domain.service.WebDavSyncService
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.RYSwitch
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.component.base.Tips
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.ext.DateFormat
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToast
import me.ash.reader.ui.ext.showToastLong
import me.ash.reader.ui.ext.toString
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.theme.palette.onLight
import java.util.Date

@Composable
fun WebDavSyncPage(
    viewModel: WebDavSyncViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val uiState = viewModel.uiState.collectAsStateValue()
    val config = uiState.config

    var serverDialogVisible by remember { mutableStateOf(false) }
    var usernameDialogVisible by remember { mutableStateOf(false) }
    var passwordDialogVisible by remember { mutableStateOf(false) }
    var remotePathDialogVisible by remember { mutableStateOf(false) }
    var editingValue by remember { mutableStateOf("") }

    RYScaffold(
        containerColor =
            MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
        navigationIcon = {
            FeedbackIconButton(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back),
                tint = MaterialTheme.colorScheme.onSurface,
                onClick = onBack,
            )
        },
        content = {
            LazyColumn {
                item {
                    DisplayText(text = stringResource(R.string.webdav_sync), desc = "")
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item {
                    Subtitle(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(R.string.connection),
                    )
                    SettingItem(
                        title = stringResource(R.string.server_url),
                        desc = config.serverUrl,
                        onClick = {
                            editingValue = config.serverUrl
                            serverDialogVisible = true
                        },
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.username),
                        desc = config.username,
                        onClick = {
                            editingValue = config.username
                            usernameDialogVisible = true
                        },
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.password),
                        desc = if (config.password.isNotBlank()) "••••••••" else "",
                        onClick = {
                            editingValue = config.password
                            passwordDialogVisible = true
                        },
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.webdav_remote_path),
                        desc = config.remotePath,
                        onClick = {
                            editingValue = config.remotePath
                            remotePathDialogVisible = true
                        },
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.webdav_test),
                        onClick = {
                            viewModel.testConnection { result ->
                                result.fold(
                                    onSuccess = {
                                        context.showToastLong(
                                            context.getString(R.string.webdav_test_success)
                                        )
                                    },
                                    onFailure = {
                                        context.showToast(
                                            context.getString(
                                                R.string.webdav_failed,
                                                it.message ?: "",
                                            )
                                        )
                                    },
                                )
                            }
                        },
                    ) {}
                    Spacer(modifier = Modifier.height(24.dp))
                }
                item {
                    Subtitle(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(R.string.webdav_sync_section),
                    )
                    SettingItem(
                        title = stringResource(R.string.webdav_sync_now),
                        desc = stringResource(R.string.webdav_sync_now_desc),
                        onClick = {
                            viewModel.syncNow { result ->
                                result.fold(
                                    onSuccess = {
                                        context.showToastLong(
                                            context.getString(
                                                R.string.webdav_sync_success,
                                                it.groupsAdded,
                                                it.feedsAdded,
                                                it.articlesAdded,
                                                it.articlesUpdated,
                                            )
                                        )
                                    },
                                    onFailure = {
                                        context.showToast(
                                            context.getString(
                                                R.string.webdav_failed,
                                                it.message ?: "",
                                            )
                                        )
                                    },
                                )
                            }
                        },
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.webdav_upload),
                        desc = stringResource(R.string.webdav_upload_desc),
                        onClick = {
                            viewModel.backup { result ->
                                result.fold(
                                    onSuccess = {
                                        context.showToastLong(
                                            context.getString(R.string.webdav_backup_success)
                                        )
                                    },
                                    onFailure = {
                                        context.showToast(
                                            context.getString(
                                                R.string.webdav_failed,
                                                it.message ?: "",
                                            )
                                        )
                                    },
                                )
                            }
                        },
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.webdav_restore),
                        desc = stringResource(R.string.webdav_restore_desc),
                        onClick = {
                            viewModel.restore { result ->
                                result.fold(
                                    onSuccess = {
                                        context.showToastLong(
                                            context.getString(
                                                R.string.webdav_sync_success,
                                                it.groupsAdded,
                                                it.feedsAdded,
                                                it.articlesAdded,
                                                it.articlesUpdated,
                                            )
                                        )
                                    },
                                    onFailure = {
                                        context.showToast(
                                            context.getString(
                                                R.string.webdav_failed,
                                                it.message ?: "",
                                            )
                                        )
                                    },
                                )
                            }
                        },
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.webdav_auto_sync),
                        onClick = {
                            viewModel.saveConfig(
                                config.copy(autoSync = !config.autoSync)
                            )
                        },
                    ) {
                        RYSwitch(activated = config.autoSync) {
                            viewModel.saveConfig(
                                config.copy(autoSync = !config.autoSync)
                            )
                        }
                    }
                    SettingItem(
                        title = stringResource(R.string.webdav_last_sync),
                        desc =
                            if (uiState.lastSyncTime > 0L) {
                                Date(uiState.lastSyncTime).toString(DateFormat.YYYY_MM_DD_HH_MM_SS)
                            } else {
                                stringResource(R.string.webdav_never_synced)
                            },
                        enabled = false,
                        onClick = {},
                    ) {}
                    Tips(text = stringResource(R.string.webdav_tips))
                    Spacer(modifier = Modifier.height(24.dp))
                }
                item {
                    Spacer(modifier = Modifier.height(24.dp))
                    Spacer(
                        modifier = Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars)
                    )
                }
            }
        },
    )

    TextFieldDialog(
        visible = serverDialogVisible,
        title = stringResource(R.string.server_url),
        value = editingValue,
        placeholder = "https://dav.example.com/remote.php/dav",
        onValueChange = { editingValue = it },
        onDismissRequest = { serverDialogVisible = false },
        onConfirm = {
            viewModel.saveConfig(config.copy(serverUrl = it))
            serverDialogVisible = false
        },
    )

    TextFieldDialog(
        visible = usernameDialogVisible,
        title = stringResource(R.string.username),
        value = editingValue,
        onValueChange = { editingValue = it },
        onDismissRequest = { usernameDialogVisible = false },
        onConfirm = {
            viewModel.saveConfig(config.copy(username = it))
            usernameDialogVisible = false
        },
    )

    TextFieldDialog(
        visible = passwordDialogVisible,
        title = stringResource(R.string.password),
        value = editingValue,
        isPassword = true,
        onValueChange = { editingValue = it },
        onDismissRequest = { passwordDialogVisible = false },
        onConfirm = {
            viewModel.saveConfig(config.copy(password = it))
            passwordDialogVisible = false
        },
    )

    TextFieldDialog(
        visible = remotePathDialogVisible,
        title = stringResource(R.string.webdav_remote_path),
        value = editingValue,
        placeholder = WebDavSyncService.DEFAULT_REMOTE_PATH,
        onValueChange = { editingValue = it },
        onDismissRequest = { remotePathDialogVisible = false },
        onConfirm = {
            viewModel.saveConfig(config.copy(remotePath = it))
            remotePathDialogVisible = false
        },
    )
}
