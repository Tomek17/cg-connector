package pl.cgconnector.app.ui.workout

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.domain.workout.FreeformWorkout
import pl.cgconnector.app.domain.workout.WorkoutJson
import pl.cgconnector.app.domain.workout.WorkoutProblem
import pl.cgconnector.app.domain.workout.WorkoutSport
import pl.cgconnector.app.domain.workout.WorkoutValidator
import pl.cgconnector.app.testing.FakeWorkoutQueueRepository
import pl.cgconnector.app.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class FreeformWizardTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2030-01-10T08:00:00Z"), ZoneOffset.UTC)
    private val repository = FakeWorkoutQueueRepository()

    private fun wizard(vararg args: Pair<String, Any>) = FreeformWizardViewModel(repository, SavedStateHandle(mapOf(*args)), clock)

    @Test
    fun `bouldering saves without description and keeps minutes`() {
        val viewModel = wizard("sport" to "bouldering")
        assertThat(viewModel.state.value.name).isEqualTo("Bouldering")

        viewModel.onMinutesChange("90 min")
        viewModel.save()

        assertThat(viewModel.state.value.isSaved).isTrue()
        val (definition, date) = repository.enqueued.single()
        assertThat(definition).isEqualTo(FreeformWorkout("Bouldering", WorkoutSport.BOULDERING, "", 90))
        assertThat(date).isEqualTo(LocalDate.of(2030, 1, 11))
    }

    @Test
    fun `other needs a description`() {
        val viewModel = wizard()
        assertThat(viewModel.state.value.sport).isEqualTo(WorkoutSport.OTHER)

        viewModel.save()
        assertThat(viewModel.state.value.problems.map { it.code }).containsExactly(WorkoutProblem.Code.DESCRIPTION_EMPTY)

        viewModel.onDescriptionChange(" Rower 1 h spokojnie ")
        viewModel.save()
        assertThat((repository.enqueued.single().first as FreeformWorkout).description).isEqualTo("Rower 1 h spokojnie")
    }

    @Test
    fun `switching kind follows default name only`() {
        val viewModel = wizard()
        viewModel.onSportChange(WorkoutSport.BOULDERING)
        assertThat(viewModel.state.value.name).isEqualTo("Bouldering")

        viewModel.onNameChange("Ścianka Łomża")
        viewModel.onSportChange(WorkoutSport.OTHER)
        assertThat(viewModel.state.value.name).isEqualTo("Ścianka Łomża")
    }

    @Test
    fun `copy from plan prefills without counting as a change`() =
        runTest {
            val id = repository.enqueue(FreeformWorkout("Ścianka", WorkoutSport.BOULDERING, "4×4", 60), LocalDate.of(2030, 1, 5))

            val copy = wizard("copyFromId" to id)

            assertThat(copy.state.value.sport).isEqualTo(WorkoutSport.BOULDERING)
            assertThat(copy.state.value.description).isEqualTo("4×4")
            assertThat(copy.state.value.minutes).isEqualTo("60")
            assertThat(copy.hasUnsavedChanges()).isFalse()
        }

    @Test
    fun `json round trip and limits`() {
        val workout = FreeformWorkout("Joga", WorkoutSport.OTHER, "30 min rozciągania", 30)
        val json = WorkoutJson.encode(workout)

        assertThat(json["sport"]!!.jsonPrimitive.content).isEqualTo("other")
        assertThat(json["minutes"]!!.jsonPrimitive.int).isEqualTo(30)
        assertThat(WorkoutJson.decode(json)).isEqualTo(workout)
        assertThat(WorkoutJson.encode(FreeformWorkout("B", WorkoutSport.BOULDERING, "")).containsKey("description")).isFalse()

        val tooLong = FreeformWorkout("X", WorkoutSport.OTHER, "a".repeat(WorkoutValidator.MAX_DESCRIPTION + 1), 0)
        assertThat(WorkoutValidator.validate(tooLong).map { it.code })
            .containsExactly(WorkoutProblem.Code.DESCRIPTION_TOO_LONG, WorkoutProblem.Code.MINUTES_OUT_OF_RANGE)
    }
}
