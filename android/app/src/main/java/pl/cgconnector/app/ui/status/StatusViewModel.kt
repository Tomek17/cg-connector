package pl.cgconnector.app.ui.status

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.repository.ActivityRepository
import pl.cgconnector.app.data.repository.StatsRepository
import pl.cgconnector.app.domain.DataFreshness
import pl.cgconnector.app.domain.Freshness
import pl.cgconnector.app.domain.PeriodSummary
import pl.cgconnector.app.domain.ServerState
import pl.cgconnector.app.domain.SyncStatus
import pl.cgconnector.app.domain.serverState
import java.time.Clock
import javax.inject.Inject

data class StatusUiState(
    val isLoading: Boolean = true,
    val status: SyncStatus = SyncStatus(),
    val serverState: ServerState = ServerState.UNKNOWN,
    /** Świeżość danych wg progów [DataFreshness]; przeliczana też co minutę przez [StatusViewModel.tick]. */
    val freshness: Freshness = Freshness.FRESH,
    /** Podsumowanie najnowszego tygodnia z serwera, po jednym wierszu na typ. */
    val currentWeek: List<PeriodSummary> = emptyList(),
    val isRefreshing: Boolean = false,
)

@HiltViewModel
class StatusViewModel
    @Inject
    constructor(
        statsRepository: StatsRepository,
        private val activityRepository: ActivityRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val isRefreshing = MutableStateFlow(false)

        /** Bieżący czas; bez tego ostrzeżenie o starych danych nie pojawiłoby się, dopóki nic nie zmieni się w Room. */
        private val now = MutableStateFlow(clock.instant())

        val uiState: StateFlow<StatusUiState> =
            combine(
                statsRepository.observeSyncStatus(),
                statsRepository.observeWeeklySummaries(),
                isRefreshing,
                now,
            ) { status, summaries, refreshing, now ->
                val newestWeek = summaries.maxOfOrNull { it.periodStart }
                StatusUiState(
                    isLoading = false,
                    status = status,
                    serverState = status.serverState(),
                    freshness = DataFreshness.evaluate(now, status.lastRefreshSuccessAt, status.serverLastSyncAt),
                    currentWeek = summaries.filter { it.periodStart == newestWeek }.sortedBy { it.type },
                    isRefreshing = refreshing,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatusUiState())

        /** Wywoływane przy wejściu na ekran i co minutę, póki ekran jest widoczny. */
        fun tick() {
            now.value = clock.instant()
        }

        /** Pełne odświeżenie; wynik trafia do Room, a ekran czyta go z [uiState]. */
        fun refresh() {
            if (isRefreshing.value) return
            isRefreshing.value = true
            viewModelScope.launch {
                try {
                    activityRepository.refresh()
                } finally {
                    isRefreshing.value = false
                }
            }
        }

        companion object {
            const val TICK_INTERVAL_MS = 60_000L
            private const val STOP_TIMEOUT_MS = 5_000L
        }
    }
