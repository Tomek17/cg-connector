package pl.cgconnector.app.ui.goal

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.data.repository.Loaded
import pl.cgconnector.app.domain.LoadError
import pl.cgconnector.app.domain.RaceGoal
import pl.cgconnector.app.testing.FakeGoalRepository
import pl.cgconnector.app.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class GoalEditorViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val clock = Clock.fixed(Instant.parse("2026-11-23T10:00:00Z"), ZoneOffset.UTC)
    private val goal = RaceGoal("Półmaraton Warszawski", LocalDate.of(2027, 3, 28), RaceGoal.HALF_MARATHON_KM, 6300)

    @Test
    fun `new goal starts empty with half marathon and default date`() =
        runTest {
            val vm = GoalEditorViewModel(FakeGoalRepository(), clock)
            val s = vm.uiState.value
            assertThat(s.isLoading).isFalse()
            assertThat(s.hasGoal).isFalse()
            assertThat(s.presetKm).isEqualTo(RaceGoal.HALF_MARATHON_KM)
            assertThat(s.date).isEqualTo(LocalDate.of(2027, 2, 23))
        }

    @Test
    fun `existing goal fills the form`() =
        runTest {
            val vm = GoalEditorViewModel(FakeGoalRepository(goal), clock)
            val s = vm.uiState.value
            assertThat(s.hasGoal).isTrue()
            assertThat(s.name).isEqualTo("Półmaraton Warszawski")
            assertThat(s.presetKm).isEqualTo(RaceGoal.HALF_MARATHON_KM)
            assertThat(listOf(s.hours, s.minutes, s.seconds)).containsExactly("1", "45", "0").inOrder()
            assertThat(s.targetSeconds).isEqualTo(6300)
            assertThat(Math.round(s.paceSecPerKm!!)).isEqualTo(299)
        }

    @Test
    fun `custom distance goal is shown as other`() =
        runTest {
            val vm = GoalEditorViewModel(FakeGoalRepository(goal.copy(distanceKm = 15.5)), clock)
            assertThat(vm.uiState.value.presetKm).isNull()
            assertThat(vm.uiState.value.customKm).isEqualTo("15.5")
        }

    @Test
    fun `invalid form shows problems and does not save`() =
        runTest {
            val repo = FakeGoalRepository()
            val vm = GoalEditorViewModel(repo, clock)
            vm.choosePreset(null)
            vm.setCustomKm("abc")
            vm.setMinutes("99")
            vm.save()
            assertThat(vm.uiState.value.problems).containsExactly(GoalProblem.NAME, GoalProblem.DISTANCE, GoalProblem.TIME)
            assertThat(repo.goal.value).isNull()
            vm.setName("Bieg")
            assertThat(vm.uiState.value.problems).doesNotContain(GoalProblem.NAME)
        }

    @Test
    fun `saves goal with custom distance and no time`() =
        runTest {
            val repo = FakeGoalRepository()
            val vm = GoalEditorViewModel(repo, clock)
            vm.setName(" Bieg Niepodległości ")
            vm.choosePreset(null)
            vm.setCustomKm("15,5")
            vm.setDate(LocalDate.of(2027, 11, 11))
            vm.save()
            assertThat(repo.goal.value).isEqualTo(RaceGoal("Bieg Niepodległości", LocalDate.of(2027, 11, 11), 15.5, null))
            assertThat(vm.uiState.value.done).isTrue()
        }

    @Test
    fun `failed save keeps form and shows error`() =
        runTest {
            val repo = FakeGoalRepository(result = Loaded.Failed(LoadError.OFFLINE))
            val vm = GoalEditorViewModel(repo, clock)
            vm.setName("Maraton")
            vm.choosePreset(RaceGoal.MARATHON_KM)
            vm.setHours("3")
            vm.setMinutes("30")
            vm.save()
            assertThat(vm.uiState.value.error).isEqualTo(LoadError.OFFLINE)
            assertThat(vm.uiState.value.done).isFalse()
            assertThat(vm.uiState.value.isSaving).isFalse()
        }

    @Test
    fun `time parts accept only digits and save as seconds`() =
        runTest {
            val repo = FakeGoalRepository()
            val vm = GoalEditorViewModel(repo, clock)
            vm.setName("Półmaraton")
            vm.setHours("1")
            vm.setMinutes("4:5")
            vm.setSeconds("30s")
            assertThat(vm.uiState.value.minutes).isEqualTo("45")
            assertThat(vm.uiState.value.seconds).isEqualTo("30")
            vm.save()
            assertThat(repo.goal.value?.targetSeconds).isEqualTo(3600 + 45 * 60 + 30)
        }

    @Test
    fun `delete clears goal`() =
        runTest {
            val repo = FakeGoalRepository(goal)
            val vm = GoalEditorViewModel(repo, clock)
            vm.delete()
            assertThat(repo.goal.value).isNull()
            assertThat(vm.uiState.value.done).isTrue()
        }
}
