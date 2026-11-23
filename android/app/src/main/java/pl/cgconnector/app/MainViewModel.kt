package pl.cgconnector.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import pl.cgconnector.app.data.onboarding.SetupProgressStore
import pl.cgconnector.app.data.onboarding.SetupStep
import pl.cgconnector.app.data.settings.ServerConfigRepository
import pl.cgconnector.app.domain.ConfigState
import javax.inject.Inject

/** Pierwszy ekran po uruchomieniu. */
enum class StartScreen {
    /** Kreator pierwszego uruchomienia (nowa osoba albo kreator przerwany w połowie). */
    SETUP,

    /** Ustawienia: token trzeba wkleić ponownie (np. po zmianie blokady ekranu). */
    SETTINGS,
    HOME,
}

sealed interface StartState {
    data object Loading : StartState

    data class Ready(val start: StartScreen) : StartState
}

@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        configRepository: ServerConfigRepository,
        setupProgress: SetupProgressStore,
    ) : ViewModel() {
        val startState: StateFlow<StartState> =
            combine(configRepository.state, setupProgress.step) { config, step -> StartState.Ready(startScreen(config, step)) }
                .take(1)
                .stateIn(viewModelScope, SharingStarted.Eagerly, StartState.Loading)

        companion object {
            fun startScreen(
                config: ConfigState,
                step: SetupStep?,
            ): StartScreen =
                when {
                    config is ConfigState.NotConfigured -> StartScreen.SETUP
                    config is ConfigState.TokenMissing -> StartScreen.SETTINGS
                    step != null -> StartScreen.SETUP
                    else -> StartScreen.HOME
                }
        }
    }
