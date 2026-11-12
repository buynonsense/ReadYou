package me.ash.reader.ui.page.settings.accounts.connection

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.account.security.CredentialCipher
import me.ash.reader.domain.model.account.security.DESUtils
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import me.ash.reader.ui.page.settings.SettingItem
import me.ash.reader.ui.component.base.Subtitle

@Composable
fun LazyItemScope.AccountConnection(
    account: Account,
) {
    if (account.type.id in 5..8) {
        var edit by remember(account.id) { mutableStateOf(false) }
        SettingItem(title = stringResource(R.string.connection), onClick = { edit = true }) {}
        if (edit) RemoteAccountDialog(account.type, account, onDismiss = { edit = false }, onConnected = { edit = false })
        return
    }
    val unlock = remember(account.id, account.securityKey) { runCatching { CredentialCipher.decrypt(account.securityKey?.ifEmpty { DESUtils.empty } ?: DESUtils.empty) } }
    if (account.type != AccountType.Local && unlock.isFailure) {
        CredentialRecovery(account)
        return
    }
    if (account.type.id != AccountType.Local.id) {
        Subtitle(
            modifier = Modifier.padding(horizontal = 24.dp),
            text = stringResource(R.string.connection),
        )
    }
    when (account.type.id) {
        AccountType.Fever.id -> FeverConnection(account)
        AccountType.GoogleReader.id -> GoogleReaderConnection(account)
        AccountType.FreshRSS.id -> FreshRSSConnection(account)
        AccountType.Feedly.id -> {}
        AccountType.Inoreader.id -> {}
    }
    if (account.type.id != AccountType.Local.id) {
        Spacer(modifier = Modifier.height(24.dp))
    }
}
