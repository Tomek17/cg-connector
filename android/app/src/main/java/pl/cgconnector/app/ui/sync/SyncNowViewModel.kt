package pl.cgconnector.app.ui.sync

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.repository.GarminSyncRepository
import pl.cgconnector.app.data.repository.StatsRepository
import pl.cgconnector.app.data.repository.SyncNowOutcome
import java.time.Clock
import java.time.Instant
import javax.inject.Inject

data class SyncNowUiState(
    val isSyncing: Boolean = false,
    /** Wynik ostatniej próby; `null` = jeszcze nie było albo trwa. */
    val outcome: SyncNowOutcome? = null,
    /** Przycisk wyłączony do tej chwili (limit: raz na godzinę); `null` = można pobierać. */
    val nextAllowedAt: Instant? = null,
) {
    val canSync: Boolean get() = !isSyncing && nextAllowedAt == null
}

/** Przycisk „Pobierz teraz z Garmina” (ekran Dziś i Ustawienia). */
@HiltViewModel
class SyncNowViewModel
    @Inject
    constructor(
        private val repository: GarminSyncRepository,
        stats: StatsRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val attempt = MutableStateFlow(SyncNowUiState())
        private val blockedUntil = MutableStateFlow<Instant?>(null)

        // Co pół minuty, żeby przycisk sam się włączył, gdy minie godzina.
        private val ticker =
            flow {
                while (true) {
                    emit(clock.instant())
                    delay(TICK_MS)
                }
            }

        val uiState: StateFlow<SyncNowUiState> =
            combine(attempt, stats.observeSyncStatus(), blockedUntil, ticker) { current, status, until, now ->
                current.copy(nextAllowedAt = SyncNowLimit.nextAllowedAt(status.serverLastSyncAt, until, now))
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), SyncNowUiState())

        fun syncNow() {
            if (!uiState.value.canSync || attempt.value.isSyncing) return
            val startedAt = clock.instant()
            attempt.value = SyncNowUiState(isSyncing = true)
            viewModelScope.launch {
                val outcome = repository.syncNow()
                blockedUntil.value =
                    when (outcome) {
                        is SyncNowOutcome.Finished, SyncNowOutcome.StillRunning -> startedAt.plus(SyncNowLimit.INTERVAL)
                        is SyncNowOutcome.Refused ->
                            outcome.retryAt?.toInstant()?.takeIf { outcome.reason == SyncNowOutcome.Reason.TOO_SOON }
                                ?: blockedUntil.value
                        is SyncNowOutcome.Failed -> blockedUntil.value
                    }
                attempt.value = SyncNowUiState(outcome = outcome)
            }
        }

        private companion object {
            const val TICK_MS = 30_000L
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
