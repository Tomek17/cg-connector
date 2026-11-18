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
    /** Ostrzeżenie: dane starsze niż 24 h (patrz [DataFreshness]). */
    val isStale: Boolean = false,
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

        val uiState: StateFlow<StatusUiState> =
            combine(
                statsRepository.observeSyncStatus(),
                statsRepository.observeWeeklySummaries(),
                isRefreshing,
            ) { status, summaries, refreshing ->
                val newestWeek = summaries.maxOfOrNull { it.periodStart }
                StatusUiState(
                    isLoading = false,
                    status = status,
                    serverState = status.serverState(),
                    isStale = DataFreshness.isStale(clock.instant(), status.lastRefreshSuccessAt, status.serverLastSyncAt),
                    currentWeek = summaries.filter { it.periodStart == newestWeek }.sortedBy { it.type },
                    isRefreshing = refreshing,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), StatusUiState())

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

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
