package pl.cgconnector.app.ui.workout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.repository.UserTemplate
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.data.repository.WorkoutTemplateRepository
import pl.cgconnector.app.domain.AppZone
import pl.cgconnector.app.domain.workout.StrengthWorkout
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
    /** Szablony siłowe zapisane przez użytkownika. */
    val userTemplates: List<UserTemplate> = emptyList(),
    /** Jednorazowy komunikat „Zapisano szablon” (pokazany i skasowany przez ekran). */
    val templateSaved: Boolean = false,
    /** Edycja wpisu Planu: zapis zastępuje ten wpis zamiast dodawać nowy. */
    val isEditing: Boolean = false,
)

@HiltViewModel
class StrengthWizardViewModel
    @Inject
    constructor(
        private val repository: WorkoutQueueRepository,
        private val templates: WorkoutTemplateRepository,
        savedStateHandle: SavedStateHandle,
        clock: Clock,
    ) : ViewModel() {
        /** Wpis Planu do zastąpienia (edycja); [NO_COPY] = nowy trening. */
        private val replaceId: Long = savedStateHandle.get<Long>(ARG_REPLACE_ID) ?: NO_COPY
        private var lastKey = 0L
        private val nextKey = { ++lastKey }
        private val today = clock.instant().atZone(AppZone.WARSAW).toLocalDate()

        private val _state =
            MutableStateFlow(
                StrengthWizardState(
                    name = DEFAULT_NAME,
                    date = today.plusDays(1),
                    today = today,
                    exercises = emptyList(),
                ),
            )
        val state: StateFlow<StrengthWizardState> = _state.asStateFlow()

        /** Stan, od którego liczymy „niezapisane zmiany” (po skopiowaniu treningu z Planu: kopia). */
        private var initial = _state.value

        init {
            viewModelScope.launch {
                templates.observeTemplates().collect { all ->
                    _state.update { s -> s.copy(userTemplates = all.filter { it.definition is StrengthWorkout }) }
                }
            }
            val copyFrom = savedStateHandle.get<Long>(ARG_COPY_FROM_ID) ?: NO_COPY
            if (replaceId != NO_COPY) {
                viewModelScope.launch {
                    val item = repository.observeQueue().first().firstOrNull { it.id == replaceId }
                    (repository.definition(replaceId) as? StrengthWorkout)?.let { original ->
                        applyTemplate(original)
                        _state.update { it.copy(date = item?.date ?: it.date, isEditing = true) }
                        initial = _state.value
                    }
                }
            } else if (copyFrom != NO_COPY) {
                viewModelScope.launch {
                    (repository.definition(copyFrom) as? StrengthWorkout)?.let { copy ->
                        applyTemplate(copy)
                        initial = _state.value
                    }
                }
            }
        }

        /** Czy użytkownik coś zmienił od otwarcia kreatora (wtedy wyjście pyta o odrzucenie zmian). */
        fun hasUnsavedChanges(): Boolean =
            _state.value.let { !it.isSaved && (it.name != initial.name || it.date != initial.date || it.exercises != initial.exercises) }

        fun onNameChange(name: String) = _state.update { it.copy(name = name) }

        fun onDateChange(date: LocalDate) = _state.update { it.copy(date = date) }

        /** Ćwiczenia wybrane w wyszukiwarce (nazwy z katalogu Garmina), dopisane na końcu w kolejności wyboru. */
        fun addExercises(garminNames: List<String>) =
            _state.update { s -> s.copy(exercises = s.exercises + garminNames.map { StrengthForm.newExercise(nextKey(), it) }) }

        /** Zmiana ćwiczenia na inne z zachowaniem serii, powtórzeń i ciężaru. */
        fun replaceExercise(
            key: Long,
            garminName: String,
        ) = _state.update { s -> s.copy(exercises = s.exercises.map { if (it.key == key) it.copy(name = garminName) else it }) }

        fun removeExercise(key: Long) = _state.update { s -> s.copy(exercises = s.exercises.filterNot { it.key == key }) }

        /** Przesuwa ćwiczenie o jedno miejsce w górę ([up]) albo w dół. */
        fun moveExercise(
            key: Long,
            up: Boolean,
        ) = _state.update { s ->
            val index = s.exercises.indexOfFirst { it.key == key }
            val target = if (up) index - 1 else index + 1
            if (index < 0 || target !in s.exercises.indices) {
                s
            } else {
                s.copy(exercises = s.exercises.toMutableList().apply { add(target, removeAt(index)) })
            }
        }

        fun updateExercise(exercise: ExerciseForm) =
            _state.update { s -> s.copy(exercises = s.exercises.map { if (it.key == exercise.key) exercise else it }) }

        /** Szablon (wbudowany, własny albo kopia z Planu) zastępuje nazwę i ćwiczenia; data zostaje. */
        fun applyTemplate(template: StrengthWorkout) =
            _state.update { it.copy(name = template.name, exercises = StrengthForm.fromDefinition(template, nextKey), problems = emptyList()) }

        /** Zapisuje bieżący trening jako własny szablon; z błędami w ćwiczeniach pokazuje problemy i nie zapisuje. */
        fun saveAsTemplate() {
            val current = _state.value
            val definition = StrengthForm.toDefinition(current.name, current.exercises)
            val problems = WorkoutValidator.validate(definition)
            if (problems.isNotEmpty()) {
                _state.update { it.copy(problems = problems) }
                return
            }
            viewModelScope.launch {
                templates.save(definition)
                _state.update { it.copy(problems = emptyList(), templateSaved = true) }
            }
        }

        fun deleteTemplate(id: String) {
            viewModelScope.launch { templates.delete(id) }
        }

        fun onTemplateSavedShown() = _state.update { it.copy(templateSaved = false) }

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
                if (current.isEditing) {
                    repository.replace(replaceId, definition, current.date)
                } else {
                    repository.enqueue(definition, current.date)
                }
                _state.update { it.copy(isSaving = false, isSaved = true) }
            }
        }

        companion object {
            /** Nazwa pola musi zgadzać się z `StrengthWizardDestination.copyFromId`. */
            const val ARG_COPY_FROM_ID = "copyFromId"

            /** Nazwa pola musi zgadzać się z `StrengthWizardDestination.replaceId`. */
            const val ARG_REPLACE_ID = "replaceId"
            const val NO_COPY = -1L
            private const val DEFAULT_NAME = "Siła"
        }
    }
