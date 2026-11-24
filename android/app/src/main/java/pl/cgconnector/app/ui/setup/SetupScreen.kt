package pl.cgconnector.app.ui.setup

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import pl.cgconnector.app.R
import pl.cgconnector.app.data.onboarding.SetupStep
import pl.cgconnector.app.domain.UrlError
import pl.cgconnector.app.ui.settings.AccountUiState
import pl.cgconnector.app.ui.settings.AccountViewModel
import pl.cgconnector.app.ui.settings.CLAUDE_CONNECTORS_URL
import pl.cgconnector.app.ui.settings.CopyField
import pl.cgconnector.app.ui.settings.GarminStage
import pl.cgconnector.app.ui.settings.InviteUiState
import pl.cgconnector.app.ui.settings.InviteViewModel
import pl.cgconnector.app.ui.settings.MessageText
import pl.cgconnector.app.ui.settings.UpdateSectionRoute
import pl.cgconnector.app.ui.settings.openInBrowser

/** Kreator pierwszego uruchomienia: prowadzi nową osobę bez żadnej instrukcji z zewnątrz. */
@Composable
fun SetupRoute(
    onFinished: () -> Unit,
    onManualSetup: () -> Unit,
    viewModel: SetupViewModel = hiltViewModel(),
    inviteViewModel: InviteViewModel = hiltViewModel(),
    accountViewModel: AccountViewModel = hiltViewModel(),
) {
    val step by viewModel.step.collectAsStateWithLifecycle()
    val serverUrl by viewModel.serverUrl.collectAsStateWithLifecycle()
    val exit by viewModel.exit.collectAsStateWithLifecycle()
    val invite by inviteViewModel.uiState.collectAsStateWithLifecycle()
    val account by accountViewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(exit) {
        when (exit) {
            SetupExit.HOME -> onFinished()
            SetupExit.MANUAL -> onManualSetup()
            null -> Unit
        }
    }
    // Stan konta Garmin czytamy dopiero po połączeniu z serwerem (wcześniej nie ma tokenu).
    LaunchedEffect(step) {
        if (step == SetupStep.GARMIN) accountViewModel.refreshStatus()
    }
    BackHandler(enabled = step == SetupStep.SERVER) { viewModel.back() }

    Surface(Modifier.fillMaxSize()) {
        val current = step
        if (current == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Surface
        }
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .imePadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StepHeader(current)
            when (current) {
                SetupStep.WELCOME ->
                    WelcomeStep(
                        inviteReady = invite.fromLink,
                        onStart = viewModel::start,
                        onManual = viewModel::manualSetup,
                    )
                SetupStep.SERVER ->
                    ServerStep(
                        state = invite,
                        onServerUrlChange = inviteViewModel::onServerUrlChange,
                        onCodeChange = inviteViewModel::onCodeChange,
                        onConnect = inviteViewModel::redeem,
                        onBack = { viewModel.back() },
                    )
                SetupStep.GARMIN -> GarminLoginStep(account, accountViewModel, onNext = viewModel::next)
                SetupStep.CLAUDE ->
                    ClaudeStep(
                        state = account,
                        serverUrl = serverUrl,
                        onNewCode = accountViewModel::newClaudeCode,
                        onNext = viewModel::next,
                    )
                SetupStep.DONE -> DoneStep(onFinish = viewModel::finish)
            }
        }
    }
}

/** „Krok 2 z 3” nad trzema właściwymi krokami (powitanie i koniec bez licznika). */
@Composable
private fun StepHeader(step: SetupStep) {
    val number =
        when (step) {
            SetupStep.SERVER -> 1
            SetupStep.GARMIN -> 2
            SetupStep.CLAUDE -> 3
            else -> return
        }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.setup_step_of, number, STEPS),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(progress = { number / STEPS.toFloat() }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun WelcomeStep(
    inviteReady: Boolean,
    onStart: () -> Unit,
    onManual: () -> Unit,
) {
    UpdateSectionRoute()
    Text(stringResource(R.string.setup_welcome_title), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.setup_welcome_text), style = MaterialTheme.typography.bodyLarge)
    Text(stringResource(R.string.setup_welcome_need_title), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.setup_welcome_need), style = MaterialTheme.typography.bodyLarge)
    if (inviteReady) {
        Text(
            stringResource(R.string.setup_welcome_invite_ready),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.primary,
        )
    }
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_start)) }
    Spacer(Modifier.height(24.dp))
    TextButton(onClick = onManual) { Text(stringResource(R.string.setup_manual)) }
}

@Composable
private fun ServerStep(
    state: InviteUiState,
    onServerUrlChange: (String) -> Unit,
    onCodeChange: (String) -> Unit,
    onConnect: () -> Unit,
    onBack: () -> Unit,
) {
    Text(stringResource(R.string.setup_server_title), style = MaterialTheme.typography.headlineSmall)
    Text(
        stringResource(if (state.fromLink) R.string.setup_server_from_link else R.string.setup_server_manual),
        style = MaterialTheme.typography.bodyLarge,
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
        value = state.code,
        onValueChange = onCodeChange,
        label = { Text(stringResource(R.string.invite_code_label)) },
        isError = state.codeMissing,
        supportingText = codeSupportingText,
        singleLine = true,
        keyboardOptions =
            KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
        modifier = Modifier.fillMaxWidth(),
    )
    state.message?.let { MessageText(it, null) }
    Button(onClick = onConnect, enabled = !state.isWorking, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(if (state.isWorking) R.string.account_working else R.string.setup_connect))
    }
    TextButton(onClick = onBack) { Text(stringResource(R.string.setup_back)) }
}

@Composable
private fun GarminLoginStep(
    state: AccountUiState,
    viewModel: AccountViewModel,
    onNext: () -> Unit,
) {
    Text(stringResource(R.string.setup_garmin_title), style = MaterialTheme.typography.headlineSmall)
    when (state.garminStage) {
        GarminStage.CONNECTED -> {
            Text(
                stringResource(R.string.setup_garmin_done),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_next)) }
        }
        GarminStage.MFA -> {
            Text(stringResource(R.string.setup_garmin_mfa), style = MaterialTheme.typography.bodyLarge)
            OutlinedTextField(
                value = state.mfaCode,
                onValueChange = viewModel::onMfaChange,
                label = { Text(stringResource(R.string.garmin_mfa_code)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            state.message?.let { MessageText(it, state.retryAt) }
            Button(
                onClick = viewModel::submitMfa,
                enabled = !state.isWorking && state.mfaCode.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(if (state.isWorking) R.string.account_working else R.string.garmin_mfa_send))
            }
            TextButton(onClick = viewModel::cancelLogin) { Text(stringResource(R.string.setup_garmin_restart)) }
        }
        // Stan jeszcze nieznany (albo serwer nie odpowiedział): od razu formularz, błąd pokaże logowanie.
        GarminStage.LOGIN, GarminStage.UNKNOWN -> {
            Text(stringResource(R.string.setup_garmin_intro), style = MaterialTheme.typography.bodyLarge)
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
                    KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false, imeAction = ImeAction.Done),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                stringResource(R.string.setup_garmin_careful),
                style = MaterialTheme.typography.bodyMedium,
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
    }
}

@Composable
private fun ClaudeStep(
    state: AccountUiState,
    serverUrl: String,
    onNewCode: () -> Unit,
    onNext: () -> Unit,
) {
    val context = LocalContext.current
    val code = state.claudeCode
    Text(stringResource(R.string.setup_claude_title), style = MaterialTheme.typography.headlineSmall)
    Text(stringResource(R.string.setup_claude_intro), style = MaterialTheme.typography.bodyLarge)

    NumberedStep(1, stringResource(R.string.setup_claude_1)) {
        if (code == null) {
            OutlinedButton(onClick = onNewCode, enabled = !state.claudeWorking) {
                Text(stringResource(if (state.claudeWorking) R.string.account_working else R.string.setup_claude_make_code))
            }
            if (state.claudeFailed) Text(stringResource(R.string.account_network), color = MaterialTheme.colorScheme.error)
        } else {
            Text(stringResource(R.string.setup_claude_code_ready), color = MaterialTheme.colorScheme.primary)
        }
    }
    NumberedStep(2, stringResource(R.string.setup_claude_2)) {
        CopyField(
            label = stringResource(R.string.claude_url_label),
            value = "$serverUrl/mcp",
            copyLabel = stringResource(R.string.claude_copy_url),
            sensitive = false,
        )
        OutlinedButton(onClick = { openInBrowser(context, CLAUDE_CONNECTORS_URL) }, enabled = code != null) {
            Text(stringResource(R.string.claude_open))
        }
    }
    NumberedStep(3, stringResource(R.string.setup_claude_3))
    NumberedStep(4, stringResource(R.string.setup_claude_4)) {
        if (code != null) {
            CopyField(
                label = stringResource(R.string.claude_code_label),
                value = code,
                copyLabel = stringResource(R.string.claude_copy),
                sensitive = true,
            )
        }
    }
    NumberedStep(5, stringResource(R.string.setup_claude_5))
    Text(
        stringResource(R.string.claude_code_hint),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_claude_done)) }
    TextButton(onClick = onNext) { Text(stringResource(R.string.setup_claude_skip)) }
}

@Composable
private fun NumberedStep(
    number: Int,
    text: String,
    content: @Composable () -> Unit = {},
) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(
            shape = CircleShape,
            color = MaterialTheme.colorScheme.primaryContainer,
            modifier = Modifier.size(28.dp),
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(number.toString(), style = MaterialTheme.typography.labelLarge)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, style = MaterialTheme.typography.bodyLarge)
            content()
        }
    }
}

@Composable
private fun DoneStep(onFinish: () -> Unit) {
    Text(stringResource(R.string.setup_done_title), style = MaterialTheme.typography.headlineMedium)
    Text(stringResource(R.string.setup_done_text), style = MaterialTheme.typography.bodyLarge)
    Button(onClick = onFinish, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.setup_done_go)) }
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

private const val STEPS = 3
