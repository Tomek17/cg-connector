package pl.cgconnector.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.settings.ServerConfigRepository
import pl.cgconnector.app.domain.ConfigValidator
import pl.cgconnector.app.domain.ServerConfig
import pl.cgconnector.app.domain.TokenError
import pl.cgconnector.app.domain.UrlError
import pl.cgconnector.app.domain.UrlValidation
import javax.inject.Inject

data class SettingsUiState(
    val serverUrl: String = "",
    /** Nowy token wpisany przez użytkownika. Zapisany token nigdy nie wraca do pola tekstowego. */
    val token: String = "",
    val hasSavedToken: Boolean = false,
    val urlError: UrlError? = null,
    val tokenError: TokenError? = null,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
)

sealed interface SettingsEvent {
    data object Saved : SettingsEvent

    data object SaveFailed : SettingsEvent
}

@HiltViewModel
class SettingsViewModel
    @Inject
    constructor(
        private val repository: ServerConfigRepository,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(SettingsUiState())
        val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

        private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
        val events: Flow<SettingsEvent> = _events.receiveAsFlow()

        init {
            viewModelScope.launch {
                val current = repository.config.first()
                _uiState.update {
                    it.copy(
                        serverUrl = current?.serverUrl.orEmpty(),
                        hasSavedToken = current != null,
                        isLoading = false,
                    )
                }
            }
        }

        fun onServerUrlChange(value: String) {
            _uiState.update { it.copy(serverUrl = value, urlError = null) }
        }

        fun onTokenChange(value: String) {
            _uiState.update { it.copy(token = value, tokenError = null) }
        }

        fun save() {
            val state = _uiState.value
            if (state.isSaving || state.isLoading) return

            val urlResult = ConfigValidator.validateUrl(state.serverUrl)
            val keepSavedToken = state.token.isBlank() && state.hasSavedToken
            val tokenError = if (keepSavedToken) null else ConfigValidator.validateToken(state.token)

            if (urlResult is UrlValidation.Invalid || tokenError != null) {
                _uiState.update {
                    it.copy(urlError = (urlResult as? UrlValidation.Invalid)?.error, tokenError = tokenError)
                }
                return
            }
            val normalizedUrl = (urlResult as UrlValidation.Valid).normalizedUrl

            _uiState.update { it.copy(isSaving = true) }
            viewModelScope.launch {
                try {
                    val token =
                        if (keepSavedToken) {
                            repository.config.first()?.token ?: error("Saved token disappeared")
                        } else {
                            state.token.trim()
                        }
                    repository.save(ServerConfig(serverUrl = normalizedUrl, token = token))
                    _uiState.update {
                        it.copy(serverUrl = normalizedUrl, token = "", hasSavedToken = true, isSaving = false)
                    }
                    _events.send(SettingsEvent.Saved)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    _uiState.update { it.copy(isSaving = false) }
                    _events.send(SettingsEvent.SaveFailed)
                }
            }
        }
    }
