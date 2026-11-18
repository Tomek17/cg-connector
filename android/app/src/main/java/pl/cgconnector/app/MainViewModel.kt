package pl.cgconnector.app

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.take
import pl.cgconnector.app.data.settings.ServerConfigRepository
import pl.cgconnector.app.domain.ConfigState
import javax.inject.Inject

sealed interface StartState {
    data object Loading : StartState

    data class Ready(val isConfigured: Boolean) : StartState
}

/** Ustala ekran startowy: konfiguracja, jeśli brak adresu/tokenu, inaczej lista treningów. */
@HiltViewModel
class MainViewModel
    @Inject
    constructor(
        configRepository: ServerConfigRepository,
    ) : ViewModel() {
        val startState: StateFlow<StartState> =
            configRepository.state
                .take(1)
                .map { StartState.Ready(isConfigured = it is ConfigState.Configured) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, StartState.Loading)
    }
