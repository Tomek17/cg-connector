package pl.cgconnector.app.ui.activities

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.data.repository.RefreshResult
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.testing.FakeActivityRepository
import pl.cgconnector.app.testing.MainDispatcherRule
import pl.cgconnector.app.testing.testActivity

@OptIn(ExperimentalCoroutinesApi::class)
class ActivitiesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val run = testActivity(1, ActivityType.RUNNING)
    private val gym = testActivity(2, ActivityType.STRENGTH)

    @Test
    fun `shows all activities and filters by type`() =
        runTest {
            val viewModel = ActivitiesViewModel(FakeActivityRepository(listOf(run, gym)))
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value.isLoading).isFalse()
            assertThat(viewModel.uiState.value.activities).containsExactly(run, gym)

            viewModel.onTypeSelected(ActivityType.STRENGTH)
            assertThat(viewModel.uiState.value.selectedType).isEqualTo(ActivityType.STRENGTH)
            assertThat(viewModel.uiState.value.activities).containsExactly(gym)

            viewModel.onTypeSelected(null)
            assertThat(viewModel.uiState.value.activities).containsExactly(run, gym)
        }

    @Test
    fun `server unavailable is reported as event, not crash`() =
        runTest {
            val viewModel = ActivitiesViewModel(FakeActivityRepository(listOf(run), RefreshResult.Unavailable))

            viewModel.events.test {
                viewModel.refresh()
                assertThat(awaitItem()).isEqualTo(ActivitiesEvent.ServerUnavailable)
            }
        }

    @Test
    fun `unauthorized is reported as separate event`() =
        runTest {
            val viewModel = ActivitiesViewModel(FakeActivityRepository(listOf(run), RefreshResult.Unauthorized))

            viewModel.events.test {
                viewModel.refresh()
                assertThat(awaitItem()).isEqualTo(ActivitiesEvent.Unauthorized)
            }
        }

    @Test
    fun `invalid response is reported as separate event`() =
        runTest {
            val viewModel = ActivitiesViewModel(FakeActivityRepository(listOf(run), RefreshResult.InvalidResponse))

            viewModel.events.test {
                viewModel.refresh()
                assertThat(awaitItem()).isEqualTo(ActivitiesEvent.InvalidResponse)
            }
        }
}
