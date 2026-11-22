package pl.cgconnector.app.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.domain.AppZone
import pl.cgconnector.app.domain.workout.FreeformWorkout
import pl.cgconnector.app.domain.workout.WorkoutProblem
import pl.cgconnector.app.domain.workout.WorkoutSport
import pl.cgconnector.app.domain.workout.WorkoutValidator
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class FreeformWizardState(
    val sport: WorkoutSport,
    val name: String,
    val description: String,
    /** Czas w minutach jako tekst z pola; pusty = do naciśnięcia okrążenia. */
    val minutes: String,
    val date: LocalDate,
    val today: LocalDate,
    val problems: List<WorkoutProblem> = emptyList(),
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
)

/** Kreator treningu „Inne” albo bouldering: nazwa, opis, opcjonalny czas i dzień. */
@HiltViewModel
class FreeformWizardViewModel
    @Inject
    constructor(
        private val repository: WorkoutQueueRepository,
        savedStateHandle: SavedStateHandle,
        clock: Clock,
    ) : ViewModel() {
        private val today = clock.instant().atZone(AppZone.WARSAW).toLocalDate()

        private val _state: MutableStateFlow<FreeformWizardState>
        val state: StateFlow<FreeformWizardState>

        /** Stan, od którego liczymy „niezapisane zmiany” (po skopiowaniu z Planu: kopia). */
        private var initial: FreeformWizardState

        init {
            val sport =
                savedStateHandle.get<String>(ARG_SPORT)?.let(WorkoutSport::fromApi)
                    ?.takeIf { it == WorkoutSport.OTHER || it == WorkoutSport.BOULDERING }
                    ?: WorkoutSport.OTHER
            _state =
                MutableStateFlow(
                    FreeformWizardState(
                        sport = sport,
                        name = defaultName(sport),
                        description = "",
                        minutes = "",
                        date = today.plusDays(1),
                        today = today,
                    ),
                )
            state = _state.asStateFlow()
            initial = _state.value
            val copyFrom = savedStateHandle.get<Long>(ARG_COPY_FROM_ID) ?: NO_COPY
            if (copyFrom != NO_COPY) {
                viewModelScope.launch {
                    (repository.definition(copyFrom) as? FreeformWorkout)?.let { copy ->
                        _state.update {
                            it.copy(
                                sport = copy.sport,
                                name = copy.name,
                                description = copy.description,
                                minutes = copy.minutes?.toString().orEmpty(),
                            )
                        }
                        initial = _state.value
                    }
                }
            }
        }

        fun hasUnsavedChanges(): Boolean =
            _state.value.let {
                !it.isSaved &&
                    (it.name != initial.name || it.description != initial.description || it.minutes != initial.minutes || it.date != initial.date)
            }

        /** Zmiana rodzaju; domyślna nazwa idzie za rodzajem, dopóki użytkownik jej nie zmienił. */
        fun onSportChange(sport: WorkoutSport) =
            _state.update {
                val name = if (it.name == defaultName(it.sport)) defaultName(sport) else it.name
                it.copy(sport = sport, name = name)
            }

        fun onNameChange(name: String) = _state.update { it.copy(name = name) }

        fun onDescriptionChange(description: String) = _state.update { it.copy(description = description) }

        fun onMinutesChange(minutes: String) = _state.update { it.copy(minutes = minutes.filter(Char::isDigit).take(4)) }

        fun onDateChange(date: LocalDate) = _state.update { it.copy(date = date) }

        fun save() {
            val current = _state.value
            if (current.isSaving || current.isSaved) return
            val minutes = current.minutes.trim().takeIf { it.isNotEmpty() }?.toIntOrNull()
            val definition = FreeformWorkout(current.name.trim(), current.sport, current.description.trim(), minutes)
            val problems =
                WorkoutValidator.validate(definition) + listOfNotNull(WorkoutValidator.validateDate(current.date, today))
            if (problems.isNotEmpty()) {
                _state.update { it.copy(problems = problems) }
                return
            }
            _state.update { it.copy(problems = emptyList(), isSaving = true) }
            viewModelScope.launch {
                repository.enqueue(definition, current.date)
                _state.update { it.copy(isSaving = false, isSaved = true) }
            }
        }

        companion object {
            /** Nazwy pól muszą zgadzać się z `FreeformWizardDestination`. */
            const val ARG_SPORT = "sport"
            const val ARG_COPY_FROM_ID = "copyFromId"
            const val NO_COPY = -1L

            fun defaultName(sport: WorkoutSport): String = if (sport == WorkoutSport.BOULDERING) "Bouldering" else "Inne"
        }
    }
