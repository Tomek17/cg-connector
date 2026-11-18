package pl.cgconnector.app.ui.workout

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
import pl.cgconnector.app.domain.workout.WorkoutProblem
import pl.cgconnector.app.domain.workout.WorkoutValidator
import java.time.Clock
import java.time.LocalDate
import javax.inject.Inject

data class StrengthWizardState(
    val name: String,
    val date: LocalDate,
    val today: LocalDate,
    val exercises: List<ExerciseForm>,
    val problems: List<WorkoutProblem> = emptyList(),
    val isSaving: Boolean = false,
    val isSaved: Boolean = false,
)

@HiltViewModel
class StrengthWizardViewModel
    @Inject
    constructor(
        private val repository: WorkoutQueueRepository,
        clock: Clock,
    ) : ViewModel() {
        private var lastKey = 0L
        private val today = clock.instant().atZone(AppZone.WARSAW).toLocalDate()

        private val _state =
            MutableStateFlow(
                StrengthWizardState(
                    name = DEFAULT_NAME,
                    date = today.plusDays(1),
                    today = today,
                    exercises = listOf(ExerciseForm(++lastKey)),
                ),
            )
        val state: StateFlow<StrengthWizardState> = _state.asStateFlow()

        fun onNameChange(name: String) = _state.update { it.copy(name = name) }

        fun onDateChange(date: LocalDate) = _state.update { it.copy(date = date) }

        fun addExercise() = _state.update { it.copy(exercises = it.exercises + ExerciseForm(++lastKey)) }

        fun removeExercise(key: Long) = _state.update { s -> s.copy(exercises = s.exercises.filterNot { it.key == key }) }

        fun updateExercise(exercise: ExerciseForm) =
            _state.update { s -> s.copy(exercises = s.exercises.map { if (it.key == exercise.key) exercise else it }) }

        fun save() {
            val current = _state.value
            if (current.isSaving || current.isSaved) return
            val definition = StrengthForm.toDefinition(current.name, current.exercises)
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

        private companion object {
            const val DEFAULT_NAME = "Siła"
        }
    }
