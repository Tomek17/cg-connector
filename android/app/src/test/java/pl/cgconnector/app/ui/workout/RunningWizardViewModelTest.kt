package pl.cgconnector.app.ui.workout

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.domain.workout.BuiltInTemplates
import pl.cgconnector.app.domain.workout.RepeatBlock
import pl.cgconnector.app.domain.workout.RunStepKind
import pl.cgconnector.app.domain.workout.RunningWorkout
import pl.cgconnector.app.domain.workout.WorkoutProblem
import pl.cgconnector.app.domain.workout.WorkoutValidator
import pl.cgconnector.app.testing.FakeWorkoutQueueRepository
import pl.cgconnector.app.testing.FakeWorkoutTemplateRepository
import pl.cgconnector.app.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class RunningWizardViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // 23:30 UTC = 00:30 następnego dnia w Warszawie: „dziś” liczymy w strefie Europe/Warsaw.
    private val clock = Clock.fixed(Instant.parse("2030-01-09T23:30:00Z"), ZoneOffset.UTC)
    private val repository = FakeWorkoutQueueRepository()
    private val templates = FakeWorkoutTemplateRepository()
    // Leniwie: model musi powstać po ustawieniu Dispatchers.Main przez regułę, inaczej viewModelScope działa w tle.
    private val viewModel by lazy { RunningWizardViewModel(repository, templates, SavedStateHandle(), clock) }

    @Test
    fun `defaults to tomorrow and saves template to queue`() {
        assertThat(viewModel.state.value.today).isEqualTo(LocalDate.of(2030, 1, 10))
        assertThat(viewModel.state.value.date).isEqualTo(LocalDate.of(2030, 1, 11))

        viewModel.save()

        assertThat(viewModel.state.value.isSaved).isTrue()
        val (definition, date) = repository.enqueued.single()
        assertThat(definition.name).isEqualTo("Interwały")
        assertThat((definition as RunningWorkout).items).hasSize(3)
        assertThat(date).isEqualTo(LocalDate.of(2030, 1, 11))
    }

    @Test
    fun `problems block saving`() {
        viewModel.onNameChange(" ")
        viewModel.onDateChange(LocalDate.of(2030, 1, 9))

        viewModel.save()

        assertThat(viewModel.state.value.problems.map { it.code })
            .containsExactly(WorkoutProblem.Code.NAME_EMPTY, WorkoutProblem.Code.DATE_IN_PAST)
        assertThat(repository.enqueued).isEmpty()
        assertThat(viewModel.state.value.isSaved).isFalse()
    }

    @Test
    fun `steps can be added, edited and removed`() {
        viewModel.state.value.blocks
            .forEach { viewModel.removeBlock(it.key) }
        viewModel.addStep(RunStepKind.WARMUP)
        val block = viewModel.state.value.blocks.single()
        viewModel.updateStep(block.key, block.steps.single().copy(endType = EndType.LAP))
        viewModel.addRepeat()
        viewModel.onRepeatTimesChange(viewModel.state.value.blocks[1].key, "8")

        viewModel.save()

        val workout = repository.enqueued.single().first as RunningWorkout
        assertThat(workout.items).hasSize(2)
        assertThat((workout.items[1] as RepeatBlock).times).isEqualTo(8)
    }

    @Test
    fun `unsaved changes are tracked until save`() {
        assertThat(viewModel.hasUnsavedChanges()).isFalse()

        viewModel.onNameChange("Tempo 20 min")
        assertThat(viewModel.hasUnsavedChanges()).isTrue()

        viewModel.save()
        assertThat(viewModel.state.value.isSaved).isTrue()
        assertThat(viewModel.hasUnsavedChanges()).isFalse()
    }

    @Test
    fun `adding a step counts as a change`() {
        viewModel.addStep(RunStepKind.COOLDOWN)

        assertThat(viewModel.hasUnsavedChanges()).isTrue()
    }

    @Test
    fun `built-in templates map to valid forms`() {
        BuiltInTemplates.RUNNING.forEach { template ->
            viewModel.applyTemplate(template)

            val definition = RunningForm.toDefinition(viewModel.state.value.name, viewModel.state.value.blocks)
            assertThat(definition).isEqualTo(template)
            assertThat(WorkoutValidator.validate(definition)).isEmpty()
        }
    }

    @Test
    fun `copy from plan prefills steps`() =
        runTest {
            val id = repository.enqueue(BuiltInTemplates.RUNNING[1], LocalDate.of(2030, 1, 5))

            val copy = RunningWizardViewModel(repository, templates, SavedStateHandle(mapOf("copyFromId" to id)), clock)

            assertThat(copy.state.value.name).isEqualTo("Interwały 10×400 m")
            assertThat(copy.state.value.blocks[1].repeatTimes).isEqualTo("10")
            assertThat(copy.state.value.blocks[1].steps[0].distanceKm).isEqualTo("0,4")
            assertThat(copy.hasUnsavedChanges()).isFalse()
        }

    @Test
    fun `own template is saved and can be deleted`() {
        viewModel.saveAsTemplate()
        val saved = viewModel.state.value.userTemplates.single()
        assertThat(saved.definition.name).isEqualTo("Interwały")

        viewModel.deleteTemplate(saved.id)

        assertThat(viewModel.state.value.userTemplates).isEmpty()
    }
}
