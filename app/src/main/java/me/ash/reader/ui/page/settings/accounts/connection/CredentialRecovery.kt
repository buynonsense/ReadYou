package me.ash.reader.ui.page.settings.accounts.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.security.GoogleReaderSecurityKey
import me.ash.reader.ui.component.base.RYOutlineTextField
import me.ash.reader.ui.page.settings.accounts.AccountViewModel

@Composable
fun CredentialRecovery(account: Account, viewModel: AccountViewModel = hiltViewModel()) {
    var url by remember(account.id) { mutableStateOf("") }
    var username by remember(account.id) { mutableStateOf("") }
    var password by remember(account.id) { mutableStateOf("") }
    var loading by remember(account.id) { mutableStateOf(false) }
    var error by remember(account.id) { mutableStateOf<String?>(null) }
    Column(Modifier.padding(horizontal = 24.dp)) {
        Text(stringResource(R.string.credentials_unavailable), color = MaterialTheme.colorScheme.error)
        RYOutlineTextField(Modifier.fillMaxWidth(), value = url, onValueChange = { url = it }, label = stringResource(R.string.server_url), readOnly = loading)
        ServerSecuritySettings(url)
        RYOutlineTextField(Modifier.fillMaxWidth(), value = username, onValueChange = { username = it }, label = stringResource(R.string.username), readOnly = loading, requestFocus = false)
        RYOutlineTextField(Modifier.fillMaxWidth(), value = password, onValueChange = { password = it }, label = stringResource(R.string.password), readOnly = loading, isPassword = true, requestFocus = false)
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Button(enabled = !loading && url.isNotBlank() && username.isNotBlank() && password.isNotEmpty(), onClick = {
            loading = true
            viewModel.reconnect(requireNotNull(account.id), GoogleReaderSecurityKey(url.trim(), username, password, null)) {
                loading = false
                error = it?.message
            }
        }) { Text(stringResource(R.string.reconnect_account)) }
    }
}
