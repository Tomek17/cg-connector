package pl.cgconnector.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.ConfigValidator
import pl.cgconnector.app.domain.TokenError
import pl.cgconnector.app.domain.UrlError
import pl.cgconnector.app.ui.status.StatusSectionRoute

@Composable
fun SettingsRoute(
    onSaved: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val resources = LocalResources.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                SettingsEvent.Saved -> onSaved()
                SettingsEvent.SaveFailed ->
                    snackbarHostState.showSnackbar(resources.getString(R.string.settings_save_failed))
            }
        }
    }

    SettingsScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onServerUrlChange = viewModel::onServerUrlChange,
        onTokenChange = viewModel::onTokenChange,
        onSave = viewModel::save,
        statusSection = { StatusSectionRoute() },
        inviteSection = { InviteSectionRoute(urlErrorText = { urlErrorText(it) }) },
        accountSection = { AccountSectionRoute(serverUrl = state.serverUrl) },
        personInviteSection = { PersonInviteSectionRoute() },
        updateSection = { UpdateSectionRoute() },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    snackbarHostState: SnackbarHostState,
    onServerUrlChange: (String) -> Unit,
    onTokenChange: (String) -> Unit,
    onSave: () -> Unit,
    statusSection: @Composable () -> Unit = {},
    inviteSection: @Composable () -> Unit = {},
    accountSection: @Composable () -> Unit = {},
    personInviteSection: @Composable () -> Unit = {},
    updateSection: @Composable () -> Unit = {},
) {
    var tokenVisible by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.settings_title)) }) },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        if (state.isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(padding).padding(24.dp))
            return@Scaffold
        }
        val urlError = state.urlError
        val tokenError = state.tokenError
        val urlSupportingText: (@Composable () -> Unit)? =
            if (urlError != null) {
                { Text(urlErrorText(urlError)) }
            } else {
                null
            }
        val tokenSupportingText: (@Composable () -> Unit)? =
            if (tokenError != null) {
                { Text(tokenErrorText(tokenError)) }
            } else if (state.hasSavedToken) {
                { Text(stringResource(R.string.settings_token_saved_hint)) }
            } else {
                null
            }

        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            updateSection()

            // Nowa osoba: kod zaproszenia wystarczy (token, Garmin i Claude konfiguruje się niżej).
            if (!state.hasSavedToken && !state.tokenLost) {
                inviteSection()
                HorizontalDivider()
                Text(stringResource(R.string.settings_token_alternative), style = MaterialTheme.typography.titleMedium)
            }

            Text(stringResource(R.string.settings_intro), style = MaterialTheme.typography.bodyMedium)

            if (state.tokenLost) {
                Text(
                    stringResource(R.string.settings_token_lost),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            OutlinedTextField(
                value = state.serverUrl,
                onValueChange = onServerUrlChange,
                label = { Text(stringResource(R.string.settings_server_url_label)) },
                placeholder = { Text(stringResource(R.string.settings_server_url_hint)) },
                isError = urlError != null,
                supportingText = urlSupportingText,
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = state.token,
                onValueChange = onTokenChange,
                label = { Text(stringResource(R.string.settings_token_label)) },
                isError = tokenError != null,
                supportingText = tokenSupportingText,
                singleLine = true,
                visualTransformation = if (tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions =
                    KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        autoCorrectEnabled = false,
                        imeAction = ImeAction.Done,
                    ),
                trailingIcon = {
                    TextButton(onClick = { tokenVisible = !tokenVisible }) {
                        Text(
                            stringResource(
                                if (tokenVisible) R.string.settings_hide_token else R.string.settings_show_token,
                            ),
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = onSave, enabled = !state.isSaving, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.settings_save))
            }

            // Stan połączenia ma sens dopiero po pierwszej konfiguracji.
            if (state.hasSavedToken) {
                HorizontalDivider()
                statusSection()
                HorizontalDivider()
                accountSection()
                personInviteSection()
            }
        }
    }
}

@Composable
private fun urlErrorText(error: UrlError): String =
    stringResource(
        when (error) {
            UrlError.EMPTY -> R.string.error_url_empty
            UrlError.INVALID -> R.string.error_url_invalid
            UrlError.NOT_HTTPS -> R.string.error_url_not_https
            UrlError.HAS_CREDENTIALS -> R.string.error_url_has_credentials
            UrlError.HAS_QUERY -> R.string.error_url_has_query
        },
    )

@Composable
private fun tokenErrorText(error: TokenError): String =
    when (error) {
        TokenError.EMPTY -> stringResource(R.string.error_token_empty)
        TokenError.TOO_SHORT -> stringResource(R.string.error_token_too_short, ConfigValidator.MIN_TOKEN_LENGTH)
        TokenError.WHITESPACE -> stringResource(R.string.error_token_whitespace)
    }
