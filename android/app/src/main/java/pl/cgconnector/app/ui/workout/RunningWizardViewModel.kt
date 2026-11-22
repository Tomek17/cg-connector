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
import pl.cgconnector.app.data.repository.UserTemplate
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.data.repository.WorkoutTemplateRepository
import pl.cgconnector.app.domain.AppZone
import pl.cgconnector.app.domain.workout.RunStepKind
import pl.cgconnector.app.domain.workout.RunningWorkout
import pl.cgconnector.app.domain.workout.WorkoutProblem
import pl.cgconnector.app.domain.workout.WorkoutValidator
import java.time.Clock
import java.time.LocalDate

import javax.inject.Inject

data class RunningWizardState(
    val name: String,
    val date: LocalDate,
    val today: LocalDate,
    val blocks: List<BlockForm>,
    val problems: List<WorkoutProblem> = emptyList(),
    val isSaving: Boolean = false,
    /** Zapisany w kolejce: ekran wraca do listy. */
    val isSaved: Boolean = false,
    /** Szablony biegowe zapisane przez użytkownika. */
    val userTemplates: List<UserTemplate> = emptyList(),
    /** Jednorazowy komunikat „Zapisano szablon” (pokazany i skasowany przez ekran). */
    val templateSaved: Boolean = false,
)

@HiltViewModel
class RunningWizardViewModel
    @Inject
    constructor(
        private val repository: WorkoutQueueRepository,
        private val templates: WorkoutTemplateRepository,
        savedStateHandle: SavedStateHandle,
        clock: Clock,
    ) : ViewModel() {
        private var lastKey = 0L
        private val nextKey = { ++lastKey }
        private val today = clock.instant().atZone(AppZone.WARSAW).toLocalDate()

        private val _state =
            MutableStateFlow(
                RunningWizardState(
                    name = DEFAULT_NAME,
                    date = today.plusDays(1),
                    today = today,
                    blocks = RunningForm.template(nextKey),
                ),
            )
        val state: StateFlow<RunningWizardState> = _state.asStateFlow()
        /** Stan, od którego liczymy „niezapisane zmiany” (po skopiowaniu treningu z Planu: kopia). */
        private var initial = _state.value

        init {
            viewModelScope.launch {
                templates.observeTemplates().collect { all ->
                    _state.update { s -> s.copy(userTemplates = all.filter { it.definition is RunningWorkout }) }
                }
            }
            val copyFrom = savedStateHandle.get<Long>(ARG_COPY_FROM_ID) ?: NO_COPY
            if (copyFrom != NO_COPY) {
                viewModelScope.launch {
                    (repository.definition(copyFrom) as? RunningWorkout)?.let { copy ->
                        applyTemplate(copy)
                        initial = _state.value
                    }
                }
            }
        }

        /** Szablon (wbudowany, własny albo kopia z Planu) zastępuje nazwę i kroki; data zostaje. */
        fun applyTemplate(template: RunningWorkout) =
            _state.update { it.copy(name = template.name, blocks = RunningForm.fromDefinition(template, nextKey), problems = emptyList()) }

        /** Zapisuje bieżący trening jako własny szablon; z błędami w krokach pokazuje problemy i nie zapisuje. */
        fun saveAsTemplate() {
            val current = _state.value
            val definition = RunningForm.toDefinition(current.name, current.blocks)
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

        /** Czy użytkownik coś zmienił od otwarcia kreatora (wtedy wyjście pyta o odrzucenie zmian). */
        fun hasUnsavedChanges(): Boolean =
            _state.value.let { !it.isSaved && (it.name != initial.name || it.date != initial.date || it.blocks != initial.blocks) }

        fun onNameChange(name: String) = _state.update { it.copy(name = name) }

        fun onDateChange(date: LocalDate) = _state.update { it.copy(date = date) }

        fun addStep(kind: RunStepKind) = updateBlocks { it + BlockForm(nextKey(), listOf(StepForm(nextKey(), kind, duration = "5:00"))) }

        fun addRepeat() = updateBlocks { it + RunningForm.interval(nextKey) }

        fun removeBlock(blockKey: Long) = updateBlocks { blocks -> blocks.filterNot { it.key == blockKey } }

        fun onRepeatTimesChange(
            blockKey: Long,
            times: String,
        ) = updateBlocks { blocks -> blocks.map { if (it.key == blockKey) it.copy(repeatTimes = times) else it } }

        fun updateStep(
            blockKey: Long,
            step: StepForm,
        ) = updateBlocks { blocks ->
            blocks.map { block ->
                if (block.key != blockKey) block else block.copy(steps = block.steps.map { if (it.key == step.key) step else it })
            }
        }

        fun save() {
            val current = _state.value
            if (current.isSaving || current.isSaved) return
            val definition = RunningForm.toDefinition(current.name, current.blocks)
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

        private fun updateBlocks(transform: (List<BlockForm>) -> List<BlockForm>) =
            _state.update { it.copy(blocks = transform(it.blocks)) }

        companion object {
            /** Nazwa pola musi zgadzać się z `RunningWizardDestination.copyFromId`. */
            const val ARG_COPY_FROM_ID = "copyFromId"
            const val NO_COPY = -1L
            private const val DEFAULT_NAME = "Interwały"
        }
    }
