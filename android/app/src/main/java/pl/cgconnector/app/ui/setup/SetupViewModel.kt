package pl.cgconnector.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.onboarding.SetupProgressStore
import pl.cgconnector.app.data.onboarding.SetupStep
import pl.cgconnector.app.data.settings.ServerConfigRepository
import pl.cgconnector.app.domain.ConfigState
import javax.inject.Inject

/** Jak kreator się kończy: aplikacja albo ręczna konfiguracja w Ustawieniach. */
enum class SetupExit { HOME, MANUAL }

/**
 * Kreator pierwszego uruchomienia: powitanie → serwer (kod zaproszenia) → Garmin → Claude → gotowe.
 * Same kroki (zaproszenie, logowanie, kod Claude) robią [pl.cgconnector.app.ui.settings.InviteViewModel]
 * i [pl.cgconnector.app.ui.settings.AccountViewModel]; tu jest tylko kolejność i zapamiętany krok.
 */
@HiltViewModel
class SetupViewModel
    @Inject
    constructor(
        private val configRepository: ServerConfigRepository,
        private val progress: SetupProgressStore,
    ) : ViewModel() {
        /** `null` do czasu odczytania zapisanego kroku. */
        private val _step = MutableStateFlow<SetupStep?>(null)
        val step: StateFlow<SetupStep?> = _step.asStateFlow()

        /** Adres zapisanego serwera (do konektora Claude), także po ponownym uruchomieniu w połowie kreatora. */
        val serverUrl: StateFlow<String> =
            configRepository.state
                .map { (it as? ConfigState.Configured)?.config?.serverUrl.orEmpty() }
                .stateIn(viewModelScope, SharingStarted.Eagerly, "")

        /** Ustawiane dopiero po zapisaniu końca kreatora, żeby nawigacja nie przerwała zapisu. */
        private val _exit = MutableStateFlow<SetupExit?>(null)
        val exit: StateFlow<SetupExit?> = _exit.asStateFlow()

        init {
            viewModelScope.launch {
                val configured = configRepository.state.first() is ConfigState.Configured
                go(resume(progress.step.first(), configured))
                // Udana wymiana zaproszenia zapisuje konfigurację: przechodzimy do Garmina.
                configRepository.state.collect { state ->
                    if (state is ConfigState.Configured && _step.value in BEFORE_SERVER) go(SetupStep.GARMIN)
                }
            }
        }

        fun start() = go(SetupStep.SERVER)

        /** Wstecz działa tylko przed połączeniem z serwerem; potem nie ma do czego wracać. */
        fun back(): Boolean {
            if (_step.value != SetupStep.SERVER) return false
            go(SetupStep.WELCOME)
            return true
        }

        fun next() {
            when (_step.value) {
                SetupStep.GARMIN -> go(SetupStep.CLAUDE)
                SetupStep.CLAUDE -> go(SetupStep.DONE)
                else -> Unit
            }
        }

        fun finish() = leave(SetupExit.HOME)

        /** „Mam token”: kreator się kończy, dalej jak dotąd w Ustawieniach. */
        fun manualSetup() = leave(SetupExit.MANUAL)

        private fun leave(exit: SetupExit) {
            viewModelScope.launch {
                progress.finish()
                _exit.value = exit
            }
        }

        private fun go(step: SetupStep) {
            if (_step.value == step) return
            _step.value = step
            viewModelScope.launch { progress.save(step) }
        }

        companion object {
            private val BEFORE_SERVER = setOf(null, SetupStep.WELCOME, SetupStep.SERVER)

            /** Krok po ponownym uruchomieniu: bez konfiguracji najwyżej serwer, z konfiguracją co najmniej Garmin. */
            fun resume(
                saved: SetupStep?,
                configured: Boolean,
            ): SetupStep =
                if (!configured) {
                    if (saved == SetupStep.SERVER) SetupStep.SERVER else SetupStep.WELCOME
                } else {
                    if (saved in BEFORE_SERVER) SetupStep.GARMIN else checkNotNull(saved)
                }
        }
    }
