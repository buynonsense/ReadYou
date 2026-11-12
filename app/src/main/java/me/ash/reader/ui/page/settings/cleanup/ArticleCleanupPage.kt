package me.ash.reader.ui.page.settings.cleanup

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.domain.data.ArticleCleanupUseCase
import me.ash.reader.infrastructure.preference.ArticleCleanupRetentionPreference
import me.ash.reader.infrastructure.preference.LocalAutoCleanupArticles
import me.ash.reader.infrastructure.preference.LocalAutoCleanupIncludeUnread
import me.ash.reader.infrastructure.preference.LocalAutoCleanupKeepStarred
import me.ash.reader.infrastructure.preference.LocalAutoCleanupRetention
import me.ash.reader.infrastructure.preference.not
import me.ash.reader.ui.component.base.DisplayText
import me.ash.reader.ui.component.base.FeedbackIconButton
import me.ash.reader.ui.component.base.RYDialog
import me.ash.reader.ui.component.base.RYScaffold
import me.ash.reader.ui.component.base.RYSwitch
import me.ash.reader.ui.component.base.RadioDialog
import me.ash.reader.ui.component.base.RadioDialogOption
import me.ash.reader.ui.component.base.Subtitle
import me.ash.reader.ui.component.base.Tips
import me.ash.reader.ui.ext.collectAsStateValue
import me.ash.reader.ui.ext.showToastLong
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.theme.palette.onLight

@Composable
fun ArticleCleanupPage(
    onBack: () -> Unit,
    viewModel: ArticleCleanupViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val resources = LocalResources.current
    val stats = viewModel.stats.collectAsStateValue(initial = ArticleCleanupUseCase.Stats())
    val uiState = viewModel.uiState.collectAsStateValue()

    val cleanupSettings = viewModel.settings.collectAsStateValue()
    val autoCleanupArticles = cleanupSettings.enabled
    val autoCleanupRetention = cleanupSettings.retention
    val autoCleanupIncludeUnread = cleanupSettings.includeUnread
    val autoCleanupKeepStarred = cleanupSettings.keepStarred

    var retentionDialogVisible by remember { mutableStateOf(false) }
    var manualRetention by remember {
        mutableStateOf<ArticleCleanupRetentionPreference>(ArticleCleanupRetentionPreference.default)
    }
    var manualIncludeUnread by remember { mutableStateOf(false) }
    var manualKeepStarred by remember { mutableStateOf(true) }

    LaunchedEffect(uiState.cleanupDialogVisible, manualRetention, manualIncludeUnread, manualKeepStarred) {
        if (uiState.cleanupDialogVisible) {
            viewModel.preview(manualRetention, manualIncludeUnread, manualKeepStarred)
        }
    }

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
                    DisplayText(text = stringResource(R.string.article_cleanup), desc = "")
                    uiState.error?.let { Tips(text = it) }
                    Spacer(modifier = Modifier.height(16.dp))
                }
                item {
                    Subtitle(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(R.string.cleanup_statistics),
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        StatCard(
                            value = stats.total,
                            label = stringResource(R.string.cleanup_total_articles),
                            modifier = Modifier.weight(1f),
                        )
                        StatCard(
                            value = stats.unread,
                            label = stringResource(R.string.cleanup_unread_articles),
                            modifier = Modifier.weight(1f),
                        )
                        StatCard(
                            value = stats.read,
                            label = stringResource(R.string.cleanup_read_articles),
                            modifier = Modifier.weight(1f),
                        )
                        StatCard(
                            value = stats.starred,
                            label = stringResource(R.string.cleanup_starred_articles),
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                }
                item {
                    Subtitle(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(R.string.cleanup_auto),
                    )
                    SettingItem(
                        title = stringResource(R.string.cleanup_auto_enable),
                        onClick = {
                            viewModel.updateSettings { it.copy(enabled = !it.enabled) }
                        },
                    ) {
                        RYSwitch(activated = autoCleanupArticles) {
                            viewModel.updateSettings { it.copy(enabled = !it.enabled) }
                        }
                    }
                    SettingItem(
                        title = stringResource(R.string.cleanup_retention),
                        desc = autoCleanupRetention.toDesc(context),
                        onClick = { retentionDialogVisible = true },
                    ) {}
                    SettingItem(
                        title = stringResource(R.string.cleanup_include_unread),
                        onClick = {
                            viewModel.updateSettings { it.copy(includeUnread = !it.includeUnread) }
                        },
                    ) {
                        RYSwitch(activated = autoCleanupIncludeUnread) {
                            viewModel.updateSettings { it.copy(includeUnread = !it.includeUnread) }
                        }
                    }
                    SettingItem(
                        title = stringResource(R.string.cleanup_keep_starred),
                        onClick = {
                            viewModel.updateSettings { it.copy(keepStarred = !it.keepStarred) }
                        },
                    ) {
                        RYSwitch(activated = autoCleanupKeepStarred) {
                            viewModel.updateSettings { it.copy(keepStarred = !it.keepStarred) }
                        }
                    }
                    Tips(text = stringResource(R.string.cleanup_auto_tips))
                    Spacer(modifier = Modifier.height(24.dp))
                }
                item {
                    Subtitle(
                        modifier = Modifier.padding(horizontal = 24.dp),
                        text = stringResource(R.string.cleanup_manual),
                    )
                    SettingItem(
                        title = stringResource(R.string.cleanup_now),
                        desc = stringResource(R.string.cleanup_now_desc),
                        onClick = {
                            manualRetention = autoCleanupRetention
                            manualIncludeUnread = autoCleanupIncludeUnread
                            manualKeepStarred = autoCleanupKeepStarred
                            viewModel.showCleanupDialog()
                        },
                    ) {}
                    Tips(text = stringResource(R.string.cleanup_manual_tips))
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

    RadioDialog(
        visible = retentionDialogVisible,
        title = stringResource(R.string.cleanup_retention),
        options =
            ArticleCleanupRetentionPreference.values.map {
                RadioDialogOption(
                    text = it.toDesc(context),
                    selected = it == autoCleanupRetention,
                ) {
                    viewModel.updateSettings { current -> current.copy(retention = it) }
                }
            },
    ) {
        retentionDialogVisible = false
    }

    RYDialog(
        visible = uiState.cleanupDialogVisible,
        onDismissRequest = { viewModel.hideCleanupDialog() },
        icon = {
            Icon(
                imageVector = Icons.Outlined.DeleteSweep,
                contentDescription = stringResource(R.string.cleanup_now),
            )
        },
        title = { Text(text = stringResource(R.string.cleanup_dialog_title)) },
        text = {
            LazyColumn {
                item {
                    Text(text = stringResource(R.string.cleanup_retention))
                    Spacer(modifier = Modifier.height(8.dp))
                }
                items(ArticleCleanupRetentionPreference.values.size) { index ->
                    val retention = ArticleCleanupRetentionPreference.values[index]
                    Row(
                        modifier =
                            Modifier.fillMaxWidth().clip(CircleShape).clickable {
                                manualRetention = retention
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = retention == manualRetention,
                            onClick = { manualRetention = retention },
                        )
                        Text(
                            modifier = Modifier.padding(start = 6.dp),
                            text = retention.toDesc(context),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                item {
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = stringResource(R.string.cleanup_include_unread))
                        RYSwitch(activated = manualIncludeUnread) {
                            manualIncludeUnread = !manualIncludeUnread
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(text = stringResource(R.string.cleanup_keep_starred))
                        RYSwitch(activated = manualKeepStarred) {
                            manualKeepStarred = !manualKeepStarred
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.cleanup_preview, uiState.previewCount),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !uiState.isCleaning,
                onClick = {
                    viewModel.cleanup(
                        retention = manualRetention,
                        includeUnread = manualIncludeUnread,
                        keepStarred = manualKeepStarred,
                    ) { deleted ->
                        context.showToastLong(
                            resources.getString(R.string.cleanup_done_toast, deleted)
                        )
                    }
                }
            ) {
                Text(text = stringResource(R.string.cleanup_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = { viewModel.hideCleanupDialog() }) {
                Text(text = stringResource(R.string.cancel))
            }
        },
    )
}

@Composable
private fun StatCard(
    value: Int,
    label: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color =
            MaterialTheme.colorScheme.secondaryContainer onLight
                MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Box(modifier = Modifier.padding(vertical = 14.dp)) {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = value.toString(),
                    style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
