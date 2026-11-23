package pl.cgconnector.app.ui.plan

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.domain.workout.QueuedWorkout
import pl.cgconnector.app.domain.workout.WorkoutDefinition
import javax.inject.Inject

data class PlanWorkoutUiState(
    val isLoading: Boolean = true,
    val item: QueuedWorkout? = null,
    /** `null` = telefon nie zna definicji (np. serwer starszy niż ta wersja aplikacji). */
    val definition: WorkoutDefinition? = null,
) {
    /** Edytować można wpis, który jeszcze nie został usunięty. */
    val canEdit: Boolean get() = item?.canDelete == true && definition != null
}

/** Szczegóły wpisu Planu (argument [ARG_ID] = id wiersza kolejki). */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class PlanWorkoutViewModel
    @Inject
    constructor(
        savedStateHandle: SavedStateHandle,
        private val repository: WorkoutQueueRepository,
    ) : ViewModel() {
        private val id: Long = checkNotNull(savedStateHandle[ARG_ID])

        // Definicja może dojść z serwera dopiero przy odświeżeniu Planu, więc czytamy ją przy każdej zmianie kolejki.
        val uiState: StateFlow<PlanWorkoutUiState> =
            repository
                .observeQueue()
                .mapLatest { items ->
                    PlanWorkoutUiState(
                        isLoading = false,
                        item = items.firstOrNull { it.id == id },
                        definition = repository.definition(id),
                    )
                }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), PlanWorkoutUiState())

        companion object {
            /** Nazwa pola `PlanWorkoutDestination.id`. */
            const val ARG_ID = "id"
            private const val STOP_TIMEOUT_MS = 5_000L
        }
    }
