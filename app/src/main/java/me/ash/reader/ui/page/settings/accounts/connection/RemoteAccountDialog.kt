package me.ash.reader.ui.page.settings.accounts.connection

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import me.ash.reader.R
import me.ash.reader.domain.model.account.Account
import me.ash.reader.domain.model.account.AccountType
import me.ash.reader.domain.model.account.security.RemoteReaderSecurityKey
import me.ash.reader.ui.component.base.RYDialog
import me.ash.reader.ui.component.base.RYOutlineTextField
import me.ash.reader.ui.page.settings.accounts.AccountViewModel

@Composable
fun RemoteAccountDialog(
    type: AccountType,
    existing: Account? = null,
    onDismiss: () -> Unit,
    onConnected: (Account?) -> Unit,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val uri = LocalUriHandler.current
    val decoded = remember(existing?.id, existing?.securityKey) {
        runCatching { if (existing == null) RemoteReaderSecurityKey() else RemoteReaderSecurityKey.from(existing.securityKey) }
    }
    val initial = decoded.getOrNull() ?: RemoteReaderSecurityKey()
    var url by remember { mutableStateOf(initial.serverUrl ?: when (type) {
        AccountType.Feedly -> "https://cloud.feedly.com"
        AccountType.Inoreader -> "https://www.inoreader.com"
        else -> ""
    }) }
    var username by remember { mutableStateOf(initial.username.orEmpty()) }
    var password by remember { mutableStateOf(initial.password.orEmpty()) }
    var token by remember { mutableStateOf(initial.accessToken.orEmpty()) }
    var clientId by remember { mutableStateOf(initial.clientId.orEmpty()) }
    var clientSecret by remember { mutableStateOf(initial.clientSecret.orEmpty()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val tokenService = type == AccountType.Feedly || type == AccountType.Inoreader
    val name = type.toDesc(context)
    RYDialog(
        visible = true,
        onDismissRequest = { if (!loading) onDismiss() },
        title = { Text(name) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (decoded.isFailure) Text(stringResource(R.string.credentials_unavailable), color = MaterialTheme.colorScheme.error)
                Text(stringResource(R.string.remote_service_connection_help))
                RYOutlineTextField(Modifier.fillMaxWidth(), value = url, onValueChange = { url = it }, label = stringResource(R.string.server_url), readOnly = loading)
                ServerSecuritySettings(url)
                if (tokenService || type == AccountType.Miniflux) {
                    RYOutlineTextField(Modifier.fillMaxWidth(), value = token, onValueChange = { token = it }, label = stringResource(R.string.api_access_token), isPassword = true, readOnly = loading, requestFocus = false)
                }
                if (type == AccountType.Miniflux || type == AccountType.TinyTinyRss) {
                    RYOutlineTextField(Modifier.fillMaxWidth(), value = username, onValueChange = { username = it }, label = stringResource(R.string.username), readOnly = loading, requestFocus = false)
                    RYOutlineTextField(Modifier.fillMaxWidth(), value = password, onValueChange = { password = it }, label = stringResource(R.string.password), isPassword = true, readOnly = loading, requestFocus = false)
                }
                if (type == AccountType.Inoreader) {
                    RYOutlineTextField(Modifier.fillMaxWidth(), value = clientId, onValueChange = { clientId = it }, label = stringResource(R.string.api_application_id), readOnly = loading, requestFocus = false)
                    RYOutlineTextField(Modifier.fillMaxWidth(), value = clientSecret, onValueChange = { clientSecret = it }, label = stringResource(R.string.api_application_key), isPassword = true, readOnly = loading, requestFocus = false)
                }
                if (tokenService) {
                    Text(stringResource(R.string.api_token_requirements))
                    TextButton(onClick = { uri.openUri(if (type == AccountType.Feedly) "https://developers.feedly.com/reference/authentication" else "https://www.inoreader.com/developers/") }) {
                        Text(stringResource(R.string.api_token_help))
                    }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(enabled = !loading && url.isNotBlank() && (if (tokenService) token.isNotBlank() else token.isNotBlank() || (username.isNotBlank() && password.isNotEmpty())), onClick = {
                loading = true
                error = null
                val credentials = RemoteReaderSecurityKey(url.trim(), username, password, token.trim(), clientId.trim(), clientSecret.trim(), initial.clientCertificateAlias)
                if (existing != null) {
                    viewModel.reconnect(requireNotNull(existing.id), credentials) {
                        loading = false
                        error = it?.message
                        if (it == null) onConnected(existing)
                    }
                } else {
                    viewModel.addAccount(Account(type = type, name = name), credentials) { account, exception ->
                        loading = false
                        error = exception?.message
                        if (account != null) onConnected(account)
                    }
                }
            }) { Text(stringResource(if (loading) R.string.loading else R.string.connection)) }
        },
        dismissButton = { TextButton(enabled = !loading, onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}
