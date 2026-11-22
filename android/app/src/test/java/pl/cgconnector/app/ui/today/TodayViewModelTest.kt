package pl.cgconnector.app.ui.today

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.Freshness
import pl.cgconnector.app.domain.RefreshOutcome
import pl.cgconnector.app.domain.ServerState
import pl.cgconnector.app.domain.SyncStatus
import pl.cgconnector.app.domain.Vo2MaxPoint
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.testing.FakeActivityRepository
import pl.cgconnector.app.testing.FakeStatsRepository
import pl.cgconnector.app.testing.FakeWorkoutQueueRepository
import pl.cgconnector.app.testing.FakeWorkoutQueueRepository.Companion.queued
import pl.cgconnector.app.testing.MainDispatcherRule
import pl.cgconnector.app.testing.testActivity
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class TodayViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    // Środa 25 listopada 2026, 12:00 w Warszawie.
    private val now = Instant.parse("2026-11-25T11:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 11, 25)

    @Test
    fun `combines next workout, current week, vo2max and server state`() =
        runTest {
            val activities =
                FakeActivityRepository(
                    listOf(
                        testActivity(3, ActivityType.RUNNING, Instant.parse("2026-11-24T06:00:00Z")),
                        testActivity(2, ActivityType.CLIMBING, Instant.parse("2026-11-23T17:00:00Z")),
                        testActivity(1, ActivityType.RUNNING, Instant.parse("2026-11-20T06:00:00Z")),
                    ),
                )
            val queue =
                FakeWorkoutQueueRepository(
                    listOf(
                        queued(1, "Wczorajszy", date = today.minusDays(1)),
                        queued(2, "Za tydzień", date = today.plusDays(7)),
                        queued(3, "Usunięty", date = today, status = QueueStatus.DELETED),
                        queued(4, "Jutro interwały", date = today.plusDays(1), status = QueueStatus.SENT),
                    ),
                )
            val stats =
                FakeStatsRepository(
                    vo2max =
                        listOf(
                            Vo2MaxPoint(LocalDate.of(2026, 10, 1), 52.0, "running"),
                            Vo2MaxPoint(LocalDate.of(2026, 11, 20), 53.0, "running"),
                            Vo2MaxPoint(LocalDate.of(2026, 11, 21), 49.0, "cycling"),
                        ),
                    status =
                        SyncStatus(
                            serverLastSyncAt = now.minusSeconds(3_600),
                            lastRefreshOutcome = RefreshOutcome.SUCCESS,
                            lastRefreshSuccessAt = now.minusSeconds(600),
                        ),
                )
            val viewModel = TodayViewModel(activities, stats, queue, clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            val state = viewModel.uiState.value
            assertThat(state.isLoading).isFalse()
            assertThat(state.today).isEqualTo(today)
            assertThat(state.nextWorkout?.name).isEqualTo("Jutro interwały")
            assertThat(state.week.weekStart).isEqualTo(LocalDate.of(2026, 11, 23))
            assertThat(state.week.activities.map { it.id }).containsExactly(3L, 2L)
            assertThat(state.vo2Latest?.value).isEqualTo(53.0)
            assertThat(state.vo2Change).isEqualTo(1.0)
            assertThat(state.serverState).isEqualTo(ServerState.ONLINE)
            assertThat(state.freshness).isEqualTo(Freshness.FRESH)
            assertThat(state.dataAsOf).isEqualTo(now.minusSeconds(3_600))
        }

    @Test
    fun `empty install has no workout, empty week and never fetched data`() =
        runTest {
            val viewModel =
                TodayViewModel(FakeActivityRepository(), FakeStatsRepository(), FakeWorkoutQueueRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            val state = viewModel.uiState.value
            assertThat(state.nextWorkout).isNull()
            assertThat(state.week.count).isEqualTo(0)
            assertThat(state.vo2Latest).isNull()
            assertThat(state.freshness).isEqualTo(Freshness.NEVER_FETCHED)
            assertThat(state.dataAsOf).isNull()
        }

    @Test
    fun `refresh triggers full refresh once`() =
        runTest {
            val activities = FakeActivityRepository()
            val viewModel = TodayViewModel(activities, FakeStatsRepository(), FakeWorkoutQueueRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.refresh()

            assertThat(activities.refreshCalls).isEqualTo(1)
            assertThat(viewModel.uiState.value.isRefreshing).isFalse()
        }

    @Test
    fun `next workout ignores pending deletes and keeps failed ones`() {
        val items =
            listOf(
                queued(1, "Do usunięcia", date = LocalDate.of(2026, 11, 25)).copy(deleteRequested = true),
                queued(2, "Błąd", date = LocalDate.of(2026, 11, 26), status = QueueStatus.FAILED),
            )

        assertThat(TodayViewModel.nextWorkout(items, LocalDate.of(2026, 11, 25))?.id).isEqualTo(2L)
    }
}
