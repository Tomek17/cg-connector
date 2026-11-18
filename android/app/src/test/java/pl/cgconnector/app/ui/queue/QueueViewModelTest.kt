package pl.cgconnector.app.ui.queue

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.testing.FakeWorkoutQueueRepository
import pl.cgconnector.app.testing.FakeWorkoutQueueRepository.Companion.queued
import pl.cgconnector.app.testing.MainDispatcherRule

@OptIn(ExperimentalCoroutinesApi::class)
class QueueViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun `shows queue and deletes after confirmation`() =
        runTest {
            val repository = FakeWorkoutQueueRepository(listOf(queued(1, "Interwały"), queued(2, "Siła A")))
            val viewModel = QueueViewModel(repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value.items.map { it.name }).containsExactly("Interwały", "Siła A").inOrder()

            viewModel.askDelete(viewModel.uiState.value.items[0])
            assertThat(viewModel.uiState.value.confirmDelete?.id).isEqualTo(1)
            assertThat(repository.deleteRequests).isEmpty()

            viewModel.confirmDelete()
            assertThat(viewModel.uiState.value.confirmDelete).isNull()
            assertThat(repository.deleteRequests).containsExactly(1L)
            assertThat(viewModel.uiState.value.items[0].canDelete).isFalse()
        }

    @Test
    fun `dismiss does not delete`() =
        runTest {
            val repository = FakeWorkoutQueueRepository(listOf(queued(1, "Bieg")))
            val viewModel = QueueViewModel(repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.askDelete(viewModel.uiState.value.items[0])
            viewModel.dismissDelete()

            assertThat(viewModel.uiState.value.confirmDelete).isNull()
            assertThat(repository.deleteRequests).isEmpty()
        }

    @Test
    fun `finished entries cannot be deleted`() =
        runTest {
            val repository =
                FakeWorkoutQueueRepository(
                    listOf(queued(1, "Usunięty", status = QueueStatus.DELETED), queued(2, "W toku", status = QueueStatus.DELETING)),
                )
            val viewModel = QueueViewModel(repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.uiState.value.items.forEach { viewModel.askDelete(it) }

            assertThat(viewModel.uiState.value.confirmDelete).isNull()
        }

    @Test
    fun `retry only for failed entries`() =
        runTest {
            val repository =
                FakeWorkoutQueueRepository(
                    listOf(queued(1, "Błąd", status = QueueStatus.FAILED), queued(2, "Wysłany", status = QueueStatus.SENT)),
                )
            val viewModel = QueueViewModel(repository)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.uiState.value.items.forEach { viewModel.retry(it) }

            assertThat(repository.retryRequests).containsExactly(1L)
        }

    @Test
    fun `refresh triggers background sync`() {
        val repository = FakeWorkoutQueueRepository()
        QueueViewModel(repository).refresh()
        assertThat(repository.refreshCalls).isEqualTo(1)
    }
}
