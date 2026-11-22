package pl.cgconnector.app.ui.workout

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.domain.workout.BuiltInTemplates
import pl.cgconnector.app.domain.workout.StrengthExercise
import pl.cgconnector.app.domain.workout.StrengthWorkout
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
class StrengthWizardTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2030-01-10T08:00:00Z"), ZoneOffset.UTC)
    private val repository = FakeWorkoutQueueRepository()
    private val templates = FakeWorkoutTemplateRepository()
    private val viewModel = StrengthWizardViewModel(repository, templates, SavedStateHandle(), clock)

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
    fun `no exercises blocks save, picked exercises go to queue`() {
        viewModel.save()
        assertThat(viewModel.state.value.problems.map { it.code }).containsExactly(WorkoutProblem.Code.NO_EXERCISES)
        assertThat(repository.enqueued).isEmpty()

        viewModel.addExercises(listOf("Push-up", "Plank", "Pull-up"))
        viewModel.removeExercise(viewModel.state.value.exercises.last().key)
        viewModel.save()

        assertThat(viewModel.state.value.isSaved).isTrue()
        val (definition, date) = repository.enqueued.single()
        val exercises = (definition as StrengthWorkout).exercises
        assertThat(exercises.map { it.name }).containsExactly("Push-up", "Plank").inOrder()
        // Deska dostaje od razu tryb „na czas”.
        assertThat(exercises[1].seconds).isEqualTo(30)
        assertThat(exercises[1].reps).isNull()
        assertThat(date).isEqualTo(LocalDate.of(2030, 1, 11))
    }

    @Test
    fun `exercises can be replaced and reordered`() {
        viewModel.addExercises(listOf("Push-up", "Pull-up"))
        val (first, second) = viewModel.state.value.exercises
        viewModel.updateExercise(first.copy(sets = "5"))

        viewModel.replaceExercise(first.key, "Diamond Push-up")
        viewModel.moveExercise(second.key, up = true)

        val exercises = viewModel.state.value.exercises
        assertThat(exercises.map { it.name }).containsExactly("Pull-up", "Diamond Push-up").inOrder()
        assertThat(exercises[1].sets).isEqualTo("5")
    }

    @Test
    fun `built-in template fills name and exercises`() {
        viewModel.applyTemplate(BuiltInTemplates.STRENGTH.first())

        val state = viewModel.state.value
        assertThat(state.name).isEqualTo("Siła – nogi")
        assertThat(state.exercises.map { StrengthForm.displayName(it.name) }).contains("Przysiad ze sztangą")
        viewModel.save()
        assertThat(viewModel.state.value.isSaved).isTrue()
    }

    @Test
    fun `current workout can be saved as own template`() {
        viewModel.addExercises(listOf("Pull-up"))
        viewModel.onNameChange("Moje ciąganie")

        viewModel.saveAsTemplate()

        assertThat(viewModel.state.value.templateSaved).isTrue()
        assertThat(viewModel.state.value.userTemplates.map { it.definition.name }).containsExactly("Moje ciąganie")
    }

    @Test
    fun `copy from plan prefills wizard without counting as a change`() =
        runTest {
            val source =
                StrengthWorkout("Siła A", listOf(StrengthExercise("Barbell Deadlift", sets = 5, reps = 3, weightKg = 120.0)))
            val id = repository.enqueue(source, LocalDate.of(2030, 1, 5))

            val copy = StrengthWizardViewModel(repository, templates, SavedStateHandle(mapOf("copyFromId" to id)), clock)

            val exercise = copy.state.value.exercises.single()
            assertThat(copy.state.value.name).isEqualTo("Siła A")
            assertThat(exercise.weightKg).isEqualTo("120")
            assertThat(exercise.sets).isEqualTo("5")
            assertThat(copy.hasUnsavedChanges()).isFalse()
        }

    @Test
    fun `unsaved changes are tracked`() {
        assertThat(viewModel.hasUnsavedChanges()).isFalse()

        viewModel.addExercises(listOf("Plank"))

        assertThat(viewModel.hasUnsavedChanges()).isTrue()
    }
}
