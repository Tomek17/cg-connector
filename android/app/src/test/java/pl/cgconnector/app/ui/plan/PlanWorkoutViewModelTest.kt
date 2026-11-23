package pl.cgconnector.app.ui.plan

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.domain.workout.StrengthExercise
import pl.cgconnector.app.domain.workout.StrengthWorkout
import pl.cgconnector.app.testing.FakeWorkoutQueueRepository
import pl.cgconnector.app.testing.MainDispatcherRule
import java.time.LocalDate

/** Robolectric, bo SavedStateHandle należy do warstwy Androida. */
@RunWith(AndroidJUnit4::class)
class PlanWorkoutViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `shows the entry with its definition and allows editing`() =
        runTest {
            val repository = FakeWorkoutQueueRepository()
            val id = repository.enqueue(StrengthWorkout("Siła", listOf(StrengthExercise("Plank", 3, seconds = 45))), LocalDate.of(2030, 1, 12))
            val viewModel = PlanWorkoutViewModel(SavedStateHandle(mapOf(PlanWorkoutViewModel.ARG_ID to id)), repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            val state = viewModel.uiState.value
            assertThat(state.item?.name).isEqualTo("Siła")
            assertThat(state.definition).isInstanceOf(StrengthWorkout::class.java)
            assertThat(state.canEdit).isTrue()

            repository.items.value = repository.items.value.map { it.copy(status = QueueStatus.DELETING) }
            assertThat(viewModel.uiState.value.canEdit).isFalse()
        }

    @Test
    fun `missing entry and unknown definition`() =
        runTest {
            val repository = FakeWorkoutQueueRepository(listOf(FakeWorkoutQueueRepository.queued(5, "Z Claude", createdInApp = false)))
            val viewModel = PlanWorkoutViewModel(SavedStateHandle(mapOf(PlanWorkoutViewModel.ARG_ID to 5L)), repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value.item).isNotNull()
            assertThat(viewModel.uiState.value.definition).isNull()
            assertThat(viewModel.uiState.value.canEdit).isFalse()
        }
}
