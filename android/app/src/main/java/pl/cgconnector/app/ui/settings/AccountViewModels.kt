package pl.cgconnector.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.onboarding.GarminLoginResult
import pl.cgconnector.app.data.onboarding.GarminStep
import pl.cgconnector.app.data.onboarding.InvalidInviteException
import pl.cgconnector.app.data.onboarding.LoginKeyMismatchException
import pl.cgconnector.app.data.onboarding.OnboardingRepository
import pl.cgconnector.app.domain.ConfigValidator
import pl.cgconnector.app.domain.UrlError
import pl.cgconnector.app.domain.UrlValidation
import javax.inject.Inject

/** Komunikat po nieudanym kroku (mapowany na tekst w UI). */
enum class AccountMessage {
    INVITE_INVALID,
    NETWORK,
    BAD_CREDENTIALS,
    MFA_EXPIRED,
    RATE_LIMITED,
    TOO_MANY_ATTEMPTS,
    KEY_CHANGED,
    GARMIN_CONNECTED_SYNC,
}

data class InviteUiState(
    val serverUrl: String = "",
    val code: String = "",
    val urlError: UrlError? = null,
    val codeMissing: Boolean = false,
    val isWorking: Boolean = false,
    val message: AccountMessage? = null,
)

/** Wymiana kodu zaproszenia `cgi_…` na token aplikacji (pierwsze uruchomienie). */
@HiltViewModel
class InviteViewModel
    @Inject
    constructor(
        private val repository: OnboardingRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(InviteUiState())
        val uiState: StateFlow<InviteUiState> = _uiState.asStateFlow()

        fun onServerUrlChange(value: String) = _uiState.update { it.copy(serverUrl = value, urlError = null) }

        fun onCodeChange(value: String) = _uiState.update { it.copy(code = value, codeMissing = false, message = null) }

        fun redeem() {
            val state = _uiState.value
            if (state.isWorking) return
            val url = ConfigValidator.validateUrl(state.serverUrl)
            val codeMissing = state.code.isBlank()
            if (url is UrlValidation.Invalid || codeMissing) {
                _uiState.update { it.copy(urlError = (url as? UrlValidation.Invalid)?.error, codeMissing = codeMissing) }
                return
            }
            val normalized = (url as UrlValidation.Valid).normalizedUrl
            _uiState.update { it.copy(isWorking = true, message = null, serverUrl = normalized) }
            viewModelScope.launch {
                val message =
                    try {
                        repository.redeemInvite(normalized, state.code)
                        null
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: InvalidInviteException) {
                        AccountMessage.INVITE_INVALID
                    } catch (_: Exception) {
                        AccountMessage.NETWORK
                    }
                // Po sukcesie ekran Ustawień sam przełącza się na stan „połączono” (obserwuje konfigurację).
                _uiState.update { it.copy(isWorking = false, message = message, code = if (message == null) "" else it.code) }
            }
        }
    }

enum class GarminStage { UNKNOWN, CONNECTED, LOGIN, MFA }

data class AccountUiState(
    val garminStage: GarminStage = GarminStage.UNKNOWN,
    val email: String = "",
    val password: String = "",
    val mfaCode: String = "",
    val isWorking: Boolean = false,
    val message: AccountMessage? = null,
    val retryAt: String? = null,
    /** Świeżo wygenerowany kod CG, pokazywany do skopiowania (nie jest nigdzie zapisywany). */
    val claudeCode: String? = null,
    val claudeWorking: Boolean = false,
    val claudeFailed: Boolean = false,
)

/** Logowanie serwera do Garmina z aplikacji (z MFA) i kod do podłączenia Claude. */
@HiltViewModel
class AccountViewModel
    @Inject
    constructor(
        private val repository: OnboardingRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(AccountUiState())
        val uiState: StateFlow<AccountUiState> = _uiState.asStateFlow()

        init {
            refreshStatus()
        }

        fun refreshStatus() {
            viewModelScope.launch {
                val status =
                    try {
                        repository.garminStatus()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        return@launch
                    }
                _uiState.update {
                    if (it.garminStage == GarminStage.MFA) it else it.copy(garminStage = stageFor(status))
                }
            }
        }

        fun onEmailChange(value: String) = _uiState.update { it.copy(email = value, message = null) }

        fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value, message = null) }

        fun onMfaChange(value: String) = _uiState.update { it.copy(mfaCode = value, message = null) }

        /** „Zaloguj ponownie” przy połączonym koncie. */
        fun startRelogin() = _uiState.update { it.copy(garminStage = GarminStage.LOGIN, message = null) }

        fun cancelLogin() {
            _uiState.update {
                it.copy(garminStage = GarminStage.UNKNOWN, password = "", mfaCode = "", message = null)
            }
            refreshStatus()
        }

        fun login() {
            val state = _uiState.value
            if (state.isWorking || state.email.isBlank() || state.password.isEmpty()) return
            perform { repository.garminLogin(state.email, state.password) }
        }

        fun submitMfa() {
            val state = _uiState.value
            if (state.isWorking || state.mfaCode.isBlank()) return
            perform { repository.garminMfa(state.mfaCode) }
        }

        private fun perform(step: suspend () -> GarminStep) {
            _uiState.update { it.copy(isWorking = true, message = null, retryAt = null) }
            viewModelScope.launch {
                val outcome =
                    try {
                        step()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: LoginKeyMismatchException) {
                        GarminStep(GarminLoginResult.KEY_CHANGED)
                    } catch (_: Exception) {
                        null
                    }
                _uiState.update { applyStep(it.copy(isWorking = false), outcome) }
            }
        }

        private fun applyStep(
            state: AccountUiState,
            step: GarminStep?,
        ): AccountUiState =
            when (step?.result) {
                null, GarminLoginResult.NOT_CONFIGURED -> state.copy(message = AccountMessage.NETWORK)
                GarminLoginResult.OK ->
                    state.copy(
                        garminStage = GarminStage.CONNECTED,
                        password = "",
                        mfaCode = "",
                        message = AccountMessage.GARMIN_CONNECTED_SYNC,
                    )
                GarminLoginResult.NEEDS_MFA -> state.copy(garminStage = GarminStage.MFA, password = "")
                GarminLoginResult.MFA_EXPIRED ->
                    state.copy(garminStage = GarminStage.LOGIN, mfaCode = "", message = AccountMessage.MFA_EXPIRED)
                GarminLoginResult.BAD_CREDENTIALS ->
                    state.copy(
                        garminStage = GarminStage.LOGIN,
                        password = "",
                        mfaCode = "",
                        message = AccountMessage.BAD_CREDENTIALS,
                    )
                GarminLoginResult.RATE_LIMITED ->
                    state.copy(message = AccountMessage.RATE_LIMITED, retryAt = step?.retryAt, password = "")
                GarminLoginResult.TOO_MANY_ATTEMPTS ->
                    state.copy(message = AccountMessage.TOO_MANY_ATTEMPTS, retryAt = step?.retryAt, password = "")
                GarminLoginResult.KEY_CHANGED -> state.copy(message = AccountMessage.KEY_CHANGED, password = "")
            }

        fun newClaudeCode() {
            if (_uiState.value.claudeWorking) return
            _uiState.update { it.copy(claudeWorking = true, claudeFailed = false) }
            viewModelScope.launch {
                val code =
                    try {
                        repository.newClaudeCode()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                _uiState.update { it.copy(claudeWorking = false, claudeCode = code, claudeFailed = code == null) }
            }
        }

        fun hideClaudeCode() = _uiState.update { it.copy(claudeCode = null) }

        private fun stageFor(status: String?): GarminStage = if (status == "ok") GarminStage.CONNECTED else GarminStage.LOGIN
    }
