package pl.cgconnector.app.ui.workout

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.domain.workout.RepeatBlock
import pl.cgconnector.app.domain.workout.RunStepKind
import pl.cgconnector.app.domain.workout.RunningWorkout
import pl.cgconnector.app.domain.workout.WorkoutProblem
import pl.cgconnector.app.testing.FakeWorkoutQueueRepository
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
    private val viewModel = RunningWizardViewModel(repository, clock)

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
}
