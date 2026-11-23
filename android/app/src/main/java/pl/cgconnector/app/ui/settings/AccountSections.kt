package pl.cgconnector.app.ui.settings

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.PersistableBundle
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.UrlError

/** Pierwsze uruchomienie z kodem zaproszenia od Tomka (zamiast wklejania tokenu). */
@Composable
fun InviteSectionRoute(
    urlErrorText: @Composable (UrlError) -> String,
    viewModel: InviteViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.invite_title), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(if (state.fromLink) R.string.invite_from_link else R.string.invite_intro),
            style = MaterialTheme.typography.bodyMedium,
            color = if (state.fromLink) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        )
        val urlError = state.urlError
        val urlSupportingText: (@Composable () -> Unit)? =
            if (urlError != null) {
                { Text(urlErrorText(urlError)) }
            } else {
                null
            }
        val codeSupportingText: (@Composable () -> Unit)? =
            if (state.codeMissing) {
                { Text(stringResource(R.string.invite_code_missing)) }
            } else {
                null
            }
        OutlinedTextField(
            value = state.serverUrl,
            onValueChange = viewModel::onServerUrlChange,
            label = { Text(stringResource(R.string.settings_server_url_label)) },
            placeholder = { Text(stringResource(R.string.settings_server_url_hint)) },
            isError = urlError != null,
            supportingText = urlSupportingText,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.code,
            onValueChange = viewModel::onCodeChange,
            label = { Text(stringResource(R.string.invite_code_label)) },
            isError = state.codeMissing,
            supportingText = codeSupportingText,
            singleLine = true,
            keyboardOptions =
                KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    autoCorrectEnabled = false,
                    imeAction = ImeAction.Done,
                ),
            modifier = Modifier.fillMaxWidth(),
        )
        state.message?.let { MessageText(it, null) }
        Button(onClick = viewModel::redeem, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(if (state.isWorking) R.string.account_working else R.string.invite_connect))
        }
    }
}

/** Konto Garmin (logowanie z aplikacji, z MFA) i podłączenie Claude. */
@Composable
fun AccountSectionRoute(
    serverUrl: String,
    viewModel: AccountViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
        GarminSection(state, viewModel)
        ClaudeSection(state, serverUrl, viewModel)
    }
}

@Composable
private fun GarminSection(
    state: AccountUiState,
    viewModel: AccountViewModel,
) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.garmin_title), style = MaterialTheme.typography.titleMedium)
        when (state.garminStage) {
            GarminStage.UNKNOWN -> Unit
            GarminStage.CONNECTED -> {
                Text(stringResource(R.string.garmin_connected), style = MaterialTheme.typography.bodyMedium)
                state.message?.let { MessageText(it, state.retryAt) }
                OutlinedButton(onClick = viewModel::startRelogin) { Text(stringResource(R.string.garmin_relogin)) }
            }
            GarminStage.LOGIN -> {
                Text(stringResource(R.string.garmin_login_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = state.email,
                    onValueChange = viewModel::onEmailChange,
                    label = { Text(stringResource(R.string.garmin_email)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = state.password,
                    onValueChange = viewModel::onPasswordChange,
                    label = { Text(stringResource(R.string.garmin_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions =
                        KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            autoCorrectEnabled = false,
                            imeAction = ImeAction.Done,
                        ),
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    stringResource(R.string.garmin_one_attempt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                state.message?.let { MessageText(it, state.retryAt) }
                Button(
                    onClick = viewModel::login,
                    enabled = !state.isWorking && state.email.isNotBlank() && state.password.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(stringResource(if (state.isWorking) R.string.account_working else R.string.garmin_connect))
                }
            }
            GarminStage.MFA -> {
                Text(stringResource(R.string.garmin_mfa_intro), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = state.mfaCode,
                    onValueChange = viewModel::onMfaChange,
                    label = { Text(stringResource(R.string.garmin_mfa_code)) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth(),
                )
                state.message?.let { MessageText(it, state.retryAt) }
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(onClick = viewModel::submitMfa, enabled = !state.isWorking && state.mfaCode.isNotBlank()) {
                        Text(stringResource(if (state.isWorking) R.string.account_working else R.string.garmin_mfa_send))
                    }
                    TextButton(onClick = viewModel::cancelLogin) { Text(stringResource(R.string.account_cancel)) }
                }
            }
        }
    }
}

@Composable
private fun ClaudeSection(
    state: AccountUiState,
    serverUrl: String,
    viewModel: AccountViewModel,
) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.claude_title), style = MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.claude_intro, "$serverUrl/mcp"), style = MaterialTheme.typography.bodyMedium)
        val code = state.claudeCode
        if (code == null) {
            OutlinedButton(onClick = { confirm = true }, enabled = !state.claudeWorking) {
                Text(stringResource(if (state.claudeWorking) R.string.account_working else R.string.claude_new_code))
            }
            if (state.claudeFailed) {
                Text(stringResource(R.string.account_network), color = MaterialTheme.colorScheme.error)
            }
        } else {
            Text(code, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
            Button(
                onClick = {
                    copySensitive(context, "$serverUrl/mcp")
                    openInBrowser(context, CLAUDE_CONNECTORS_URL)
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.claude_open)) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { copySensitive(context, code) }) { Text(stringResource(R.string.claude_copy)) }
                TextButton(onClick = viewModel::hideClaudeCode) { Text(stringResource(R.string.claude_hide)) }
            }
            Text(
                stringResource(R.string.claude_code_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.claude_confirm_title)) },
            text = { Text(stringResource(R.string.claude_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    viewModel.newClaudeCode()
                }) { Text(stringResource(R.string.claude_new_code)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.account_cancel)) } },
        )
    }
}

@Composable
internal fun MessageText(
    message: AccountMessage,
    retryAt: String?,
) {
    val ok = message == AccountMessage.GARMIN_CONNECTED_SYNC
    val text =
        when (message) {
            AccountMessage.INVITE_INVALID -> stringResource(R.string.invite_invalid)
            AccountMessage.NETWORK -> stringResource(R.string.account_network)
            AccountMessage.BAD_CREDENTIALS -> stringResource(R.string.garmin_bad_credentials)
            AccountMessage.MFA_EXPIRED -> stringResource(R.string.garmin_mfa_expired)
            AccountMessage.RATE_LIMITED -> stringResource(R.string.garmin_rate_limited)
            AccountMessage.TOO_MANY_ATTEMPTS -> stringResource(R.string.garmin_too_many_attempts)
            AccountMessage.KEY_CHANGED -> stringResource(R.string.garmin_key_changed)
            AccountMessage.GARMIN_CONNECTED_SYNC -> stringResource(R.string.garmin_connected_sync)
        }
    val suffix = retryAt?.let { " " + stringResource(R.string.account_retry_at, formatRetry(it)) }.orEmpty()
    Text(
        text + suffix,
        style = MaterialTheme.typography.bodyMedium,
        color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
    )
}

/** `2026-11-24T13:05:00+01:00` → `24.11 13:05`; nieznany format zostaje bez zmian. */
internal fun formatRetry(iso: String): String {
    val match = Regex("""^\d{4}-(\d{2})-(\d{2})T(\d{2}):(\d{2})""").find(iso) ?: return iso
    val (month, day, hour, minute) = match.destructured
    return "$day.$month $hour:$minute"
}

internal fun copySensitive(
    context: Context,
    text: String,
) {
    val clip = ClipData.newPlainText("CG-connector", text)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        clip.description.extras = PersistableBundle().apply { putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true) }
    }
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(clip)
}

internal const val CLAUDE_CONNECTORS_URL = "https://claude.ai/customize/connectors"

/**
 * Otwiera adres w przeglądarce, nie w aplikacji, która go przechwytuje (np. aplikacja Claude dla
 * claude.ai): własny konektor dodaje się na stronie claude.ai.
 */
internal fun openInBrowser(
    context: Context,
    url: String,
) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    val probe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    val browser =
        context.packageManager
            .resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo
            ?.packageName
    // „android” = okno wyboru (brak domyślnej przeglądarki): wtedy system pyta sam.
    if (browser != null && browser != "android") intent.setPackage(browser)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    }
}

/** Karta „Dostępna nowa wersja” (pokazywana tylko, gdy na GitHubie jest nowsze wydanie). */
@Composable
fun UpdateSectionRoute(viewModel: UpdateViewModel = hiltViewModel()) {
    val update by viewModel.update.collectAsStateWithLifecycle()
    val available = update ?: return
    val uriHandler = LocalUriHandler.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.update_available, available.version),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(stringResource(R.string.update_hint), style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { uriHandler.openUri(available.downloadUrl) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.update_download))
        }
    }
}
