package pl.cgconnector.app.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.repository.GarminSyncRepository
import pl.cgconnector.app.data.repository.SyncNowOutcome
import javax.inject.Inject

data class SyncNowUiState(
    val isSyncing: Boolean = false,
    /** Wynik ostatniej próby; `null` = jeszcze nie było albo trwa. */
    val outcome: SyncNowOutcome? = null,
)

/** Przycisk „Pobierz teraz z Garmina” (ekran Dziś i Ustawienia). */
@HiltViewModel
class SyncNowViewModel
    @Inject
    constructor(
        private val repository: GarminSyncRepository,
    ) : ViewModel() {
        private val state = MutableStateFlow(SyncNowUiState())
        val uiState: StateFlow<SyncNowUiState> = state.asStateFlow()

        fun syncNow() {
            if (state.value.isSyncing) return
            state.value = SyncNowUiState(isSyncing = true)
            viewModelScope.launch {
                state.value = SyncNowUiState(outcome = repository.syncNow())
            }
        }
    }
