package pl.cgconnector.app.ui.workout

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.domain.workout.StrengthExercise
import pl.cgconnector.app.domain.workout.StrengthWorkout
import pl.cgconnector.app.domain.workout.WorkoutProblem
import pl.cgconnector.app.domain.workout.WorkoutValidator
import pl.cgconnector.app.testing.FakeWorkoutQueueRepository
import pl.cgconnector.app.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class StrengthWizardTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2030-01-10T08:00:00Z"), ZoneOffset.UTC)
    private val repository = FakeWorkoutQueueRepository()
    private val viewModel = StrengthWizardViewModel(repository, clock)

    @Test
    fun `form maps reps, time, weight with comma and rest`() {
        val definition =
            StrengthForm.toDefinition(
                "Siła A",
                listOf(
                    ExerciseForm(1, name = " Barbell Back Squat ", sets = "3", reps = "5", weightKg = "82,5", restSeconds = "120"),
                    ExerciseForm(2, name = "Plank", sets = "3", byTime = true, seconds = "45"),
                ),
            )

        assertThat(definition.exercises)
            .containsExactly(
                StrengthExercise("Barbell Back Squat", sets = 3, reps = 5, weightKg = 82.5, restSeconds = 120),
                StrengthExercise("Plank", sets = 3, seconds = 45, restSeconds = 60),
            ).inOrder()
    }

    @Test
    fun `invalid numbers become problems with exercise number`() {
        val definition =
            StrengthForm.toDefinition("Siła", listOf(ExerciseForm(1, name = "Plank", sets = "x", restSeconds = "abc")))

        assertThat(WorkoutValidator.validate(definition)).containsExactly(
            WorkoutProblem(WorkoutProblem.Code.SETS_OUT_OF_RANGE, 1),
            WorkoutProblem(WorkoutProblem.Code.REST_OUT_OF_RANGE, 1),
        )
    }

    @Test
    fun `suggestions come from catalog and disappear on exact match`() {
        assertThat(StrengthForm.suggestionsFor("squat")).contains("Barbell Back Squat")
        assertThat(StrengthForm.suggestionsFor("plank")).isEmpty()
        assertThat(StrengthForm.suggestionsFor("")).isEmpty()
    }

    @Test
    fun `empty exercise name blocks save, valid workout goes to queue`() {
        viewModel.save()
        assertThat(viewModel.state.value.problems.map { it.code }).containsExactly(WorkoutProblem.Code.EXERCISE_NAME_INVALID)
        assertThat(repository.enqueued).isEmpty()

        val exercise = viewModel.state.value.exercises.single()
        viewModel.updateExercise(exercise.copy(name = "Push-up"))
        viewModel.addExercise()
        viewModel.removeExercise(viewModel.state.value.exercises.last().key)
        viewModel.save()

        assertThat(viewModel.state.value.isSaved).isTrue()
        val (definition, date) = repository.enqueued.single()
        assertThat((definition as StrengthWorkout).exercises.single().name).isEqualTo("Push-up")
        assertThat(date).isEqualTo(LocalDate.of(2030, 1, 11))
    }

    @Test
    fun `unsaved changes are tracked`() {
        assertThat(viewModel.hasUnsavedChanges()).isFalse()

        viewModel.addExercise()

        assertThat(viewModel.hasUnsavedChanges()).isTrue()
    }
}
