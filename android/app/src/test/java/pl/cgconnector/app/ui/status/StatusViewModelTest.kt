package pl.cgconnector.app.ui.status

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.PeriodSummary
import pl.cgconnector.app.domain.RefreshOutcome
import pl.cgconnector.app.domain.ServerState
import pl.cgconnector.app.domain.SyncStatus
import pl.cgconnector.app.testing.FakeActivityRepository
import pl.cgconnector.app.testing.FakeStatsRepository
import pl.cgconnector.app.testing.MainDispatcherRule
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@OptIn(ExperimentalCoroutinesApi::class)
class StatusViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val now = Instant.parse("2026-11-18T15:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test
    fun `fresh data, server online, current week only`() =
        runTest {
            val stats =
                FakeStatsRepository(
                    status =
                        SyncStatus(
                            serverLastSyncAt = now.minusSeconds(3_600),
                            lastRefreshAttemptAt = now.minusSeconds(600),
                            lastRefreshOutcome = RefreshOutcome.SUCCESS,
                            lastRefreshSuccessAt = now.minusSeconds(600),
                        ),
                    summaries =
                        listOf(
                            summary("2026-11-16", ActivityType.STRENGTH),
                            summary("2026-11-16", ActivityType.RUNNING),
                            summary("2026-11-09", ActivityType.RUNNING),
                        ),
                )
            val viewModel = StatusViewModel(stats, FakeActivityRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            val state = viewModel.uiState.value
            assertThat(state.isLoading).isFalse()
            assertThat(state.serverState).isEqualTo(ServerState.ONLINE)
            assertThat(state.isStale).isFalse()
            assertThat(state.currentWeek.map { it.type })
                .containsExactly(ActivityType.RUNNING, ActivityType.STRENGTH)
                .inOrder()
        }

    @Test
    fun `offline server with old data shows warning`() =
        runTest {
            val stats =
                FakeStatsRepository(
                    status =
                        SyncStatus(
                            serverLastSyncAt = now.minusSeconds(30 * 3_600),
                            lastRefreshAttemptAt = now.minusSeconds(60),
                            lastRefreshOutcome = RefreshOutcome.UNAVAILABLE,
                            lastRefreshSuccessAt = now.minusSeconds(26 * 3_600),
                        ),
                )
            val viewModel = StatusViewModel(stats, FakeActivityRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value.serverState).isEqualTo(ServerState.OFFLINE)
            assertThat(viewModel.uiState.value.isStale).isTrue()
        }

    @Test
    fun `never refreshed is unknown and stale`() =
        runTest {
            val viewModel = StatusViewModel(FakeStatsRepository(), FakeActivityRepository(), clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            assertThat(viewModel.uiState.value.serverState).isEqualTo(ServerState.UNKNOWN)
            assertThat(viewModel.uiState.value.isStale).isTrue()
        }

    @Test
    fun `check now triggers full refresh`() =
        runTest {
            val activities = FakeActivityRepository()
            val viewModel = StatusViewModel(FakeStatsRepository(), activities, clock)
            backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { viewModel.uiState.collect {} }

            viewModel.refresh()

            assertThat(activities.refreshCalls).isEqualTo(1)
            assertThat(viewModel.uiState.value.isRefreshing).isFalse()
        }

    private fun summary(
        start: String,
        type: ActivityType,
    ) = PeriodSummary(
        period = "week",
        periodStart = LocalDate.parse(start),
        periodEnd = LocalDate.parse(start).plusDays(6),
        type = type,
        count = 1,
        distanceM = null,
        durationSec = 3_600,
        elevationGainM = null,
        avgPaceMinPerKm = null,
    )
}
