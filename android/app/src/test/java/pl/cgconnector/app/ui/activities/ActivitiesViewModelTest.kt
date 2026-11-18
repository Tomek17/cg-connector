package pl.cgconnector.app.ui.activities

import app.cash.turbine.test
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.data.repository.ActivityRepository
import pl.cgconnector.app.data.repository.RefreshResult
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.testing.MainDispatcherRule
import java.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class ActivitiesViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val run = activity(1, ActivityType.RUNNING)
    private val gym = activity(2, ActivityType.STRENGTH)

    private class FakeRepository(
        activities: List<Activity>,
        var refreshResult: RefreshResult = RefreshResult.Success(0),
    ) : ActivityRepository {
        val data = MutableStateFlow(activities)

        override fun observeActivities(type: ActivityType?): Flow<List<Activity>> =
            data.map { list -> list.filter { type == null || it.type == type } }

        override suspend fun refresh(): RefreshResult = refreshResult
    }

    @Test
    fun `shows all activities and filters by type`() =
        runTest {
            val viewModel = ActivitiesViewModel(FakeRepository(listOf(run, gym)))
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
            val viewModel = ActivitiesViewModel(FakeRepository(listOf(run), RefreshResult.Unavailable))

            viewModel.events.test {
                viewModel.refresh()
                assertThat(awaitItem()).isEqualTo(ActivitiesEvent.ServerUnavailable)
            }
        }

    @Test
    fun `unauthorized is reported as separate event`() =
        runTest {
            val viewModel = ActivitiesViewModel(FakeRepository(listOf(run), RefreshResult.Unauthorized))

            viewModel.events.test {
                viewModel.refresh()
                assertThat(awaitItem()).isEqualTo(ActivitiesEvent.Unauthorized)
            }
        }

    @Test
    fun `invalid response is reported as separate event`() =
        runTest {
            val viewModel = ActivitiesViewModel(FakeRepository(listOf(run), RefreshResult.InvalidResponse))

            viewModel.events.test {
                viewModel.refresh()
                assertThat(awaitItem()).isEqualTo(ActivitiesEvent.InvalidResponse)
            }
        }

    private fun activity(
        id: Long,
        type: ActivityType,
    ) = Activity(
        id = id,
        type = type,
        name = "A$id",
        startTime = Instant.parse("2026-11-0${id}T07:00:00Z"),
        durationSec = 1800,
        distanceM = null,
        avgHr = null,
        maxHr = null,
        elevationGainM = null,
        avgPowerW = null,
        avgCadenceSpm = null,
    )
}
