package me.ash.reader.ui.page.settings.accounts.connection

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import me.ash.reader.R
import me.ash.reader.infrastructure.net.ServerSecurityStore
import me.ash.reader.ui.component.base.RYSwitch
import me.ash.reader.ui.component.base.TextFieldDialog
import me.ash.reader.ui.page.settings.SettingItem
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

@Composable
fun ServerSecuritySettings(serverUrl: String) {
    val context = LocalContext.current
    val store = remember(context) { ServerSecurityStore(context) }
    val url = serverUrl.toHttpUrlOrNull() ?: return
    val origin = store.origin(serverUrl)
    var allowsHttp by remember(origin) { mutableStateOf(store.allowsHttp(serverUrl)) }
    var certificate by remember(origin) { mutableStateOf(store.certificatePem(serverUrl).orEmpty()) }
    var editCertificate by remember(origin) { mutableStateOf(false) }
    var draft by remember(origin) { mutableStateOf("") }
    var error by remember(origin) { mutableStateOf<String?>(null) }

    if (!url.isHttps) {
        val toggle = {
            allowsHttp = !allowsHttp
            store.setAllowsHttp(serverUrl, allowsHttp)
        }
        SettingItem(
            title = stringResource(R.string.server_allow_http),
            desc = stringResource(R.string.server_allow_http_desc, origin.orEmpty()),
            onClick = toggle,
        ) { RYSwitch(activated = allowsHttp, onClick = toggle) }
    } else {
        SettingItem(
            title = stringResource(R.string.server_trusted_certificate),
            desc = if (certificate.isEmpty()) stringResource(R.string.server_system_trust)
                else stringResource(R.string.server_custom_trust, origin.orEmpty()),
            onClick = { draft = certificate; error = null; editCertificate = true },
        ) {}
    }
    error?.let { Text(text = it, color = MaterialTheme.colorScheme.error) }
    TextFieldDialog(
        visible = editCertificate,
        title = stringResource(R.string.server_trusted_certificate),
        value = draft,
        placeholder = stringResource(R.string.server_certificate_pem_hint),
        singleLine = false,
        onValueChange = { draft = it },
        onDismissRequest = { editCertificate = false },
        onConfirm = { pem ->
            runCatching { store.setCertificate(serverUrl, pem) }.fold(
                onSuccess = { certificate = pem.trim(); editCertificate = false },
                onFailure = { failure -> error = failure.message },
            )
        },
    )
}
