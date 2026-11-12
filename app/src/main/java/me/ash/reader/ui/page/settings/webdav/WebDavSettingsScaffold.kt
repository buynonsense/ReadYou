package me.ash.reader.ui.page.settings.webdav

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.util.Date
import me.ash.reader.R
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.Tips
import me.ash.reader.ui.ext.DateFormat
import me.ash.reader.ui.ext.toString
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.theme.palette.onLight

@Composable
internal fun WebDavSettingsScaffold(
    @StringRes title: Int,
    state: WebDavSyncViewModel.WebDavUiState,
    onRetry: () -> Unit,
    onBack: () -> Unit,
    description: String = "",
    showResult: Boolean = false,
    content: LazyListScope.() -> Unit,
) {
    RYScaffold(
        containerColor = MaterialTheme.colorScheme.surface onLight MaterialTheme.colorScheme.inverseOnSurface,
        navigationIcon = {
            FeedbackIconButton(imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = stringResource(R.string.back), tint = MaterialTheme.colorScheme.onSurface, onClick = onBack)
        },
    ) {
        LazyColumn {
            item { DisplayText(text = stringResource(title), desc = description) }
            item {
                if (state.isLoading || state.progress != null) {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                    state.progress?.let { progress ->
                        Tips(text = stageTitle(progress.stage) + if (progress.total > 0) " (${progress.completed}/${progress.total})" else "")
                    }
                }
                state.error?.let { Tips(text = it) }
                if (state.canRetry) SettingItem(title = stringResource(R.string.retry),
                    enabled = !state.isLoading, onClick = onRetry)
                if (showResult) when (state.completedAction) {
                    WebDavSyncViewModel.Action.BACKUP -> Tips(text = stringResource(R.string.webdav_backup_success))
                    else -> state.result?.let {
                        Tips(text = stringResource(R.string.webdav_sync_success, it.groupsAdded, it.feedsAdded, it.articlesAdded, it.articlesUpdated))
                    }
                }
            }
            content()
            item {
                Spacer(Modifier.height(24.dp))
                Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
            }
        }
    }
}

@Composable
internal fun formatWebDavTime(time: Long): String =
    if (time > 0) Date(time).toString(DateFormat.YYYY_MM_DD_HH_MM_SS) else stringResource(R.string.webdav_never_synced)

@Composable
internal fun webDavActionTitle(action: String): String = stringResource(when (action) {
    "auto-sync" -> R.string.webdav_auto_sync_action
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
