package pl.cgconnector.app.ui.today

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
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.AppZone
import pl.cgconnector.app.domain.DataFreshness
import pl.cgconnector.app.domain.Freshness
import pl.cgconnector.app.domain.ServerState
import pl.cgconnector.app.domain.SyncStatus
import pl.cgconnector.app.domain.Vo2MaxPoint
import pl.cgconnector.app.domain.WeekGrouping
import pl.cgconnector.app.domain.WeekSummary
import pl.cgconnector.app.domain.serverState
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.domain.workout.QueuedWorkout
import pl.cgconnector.app.ui.vo2max.Vo2MaxViewModel
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

data class TodayUiState(
    val isLoading: Boolean = true,
    val today: LocalDate = LocalDate.MIN,
    /** Najbliższy aktywny trening z kolejki (dziś lub później). */
    val nextWorkout: QueuedWorkout? = null,
    val week: WeekSummary = WeekSummary(LocalDate.MIN, emptyList()),
    /** Ostatni pomiar VO2max dla biegu (albo pierwszego dostępnego sportu). */
    val vo2Latest: Vo2MaxPoint? = null,
    val vo2Change: Double? = null,
    val serverState: ServerState = ServerState.UNKNOWN,
    val freshness: Freshness = Freshness.FRESH,
    /** Z kiedy są dane: ostatni sync serwera z Garminem, a gdy go brak, ostatnie odświeżenie aplikacji. */
    val dataAsOf: Instant? = null,
    val isRefreshing: Boolean = false,
)

/** Ekran „Dziś”: najważniejsze rzeczy z kolejki, tygodnia, VO2max i stanu serwera w jednym miejscu. */
@HiltViewModel
class TodayViewModel
    @Inject
    constructor(
        private val activityRepository: ActivityRepository,
        statsRepository: StatsRepository,
        queueRepository: WorkoutQueueRepository,
        private val clock: Clock,
    ) : ViewModel() {
        private val isRefreshing = MutableStateFlow(false)
        private val now = MutableStateFlow(clock.instant())

        private val data =
            combine(
                activityRepository.observeActivities(null),
                queueRepository.observeQueue(),
                statsRepository.observeVo2Max(),
                statsRepository.observeSyncStatus(),
            ) { activities, queue, vo2, status -> Data(activities, queue, vo2, status) }

        val uiState: StateFlow<TodayUiState> =
            combine(data, isRefreshing, now) { data, refreshing, now ->
                val today = now.atZone(AppZone.WARSAW).toLocalDate()
                val vo2 = Vo2MaxViewModel.buildState(data.vo2, chosen = null)
                TodayUiState(
                    isLoading = false,
                    today = today,
                    nextWorkout = nextWorkout(data.queue, today),
                    week = WeekGrouping.currentWeek(data.activities, today),
                    vo2Latest = vo2.latest,
                    vo2Change = vo2.change,
                    serverState = data.status.serverState(),
                    freshness = DataFreshness.evaluate(now, data.status.lastRefreshSuccessAt, data.status.serverLastSyncAt),
                    dataAsOf = data.status.serverLastSyncAt ?: data.status.lastRefreshSuccessAt,
                    isRefreshing = refreshing,
                )
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TodayUiState())

        /** Wywoływane przy wejściu na ekran i co minutę, póki ekran jest widoczny (zmiana dnia, świeżość). */
        fun tick() {
            now.value = clock.instant()
        }

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

        private data class Data(
            val activities: List<Activity>,
            val queue: List<QueuedWorkout>,
            val vo2: List<Vo2MaxPoint>,
            val status: SyncStatus,
        )

        companion object {
            const val TICK_INTERVAL_MS = 60_000L
            private const val STOP_TIMEOUT_MS = 5_000L
            private val ACTIVE = setOf(QueueStatus.PENDING, QueueStatus.SENT, QueueStatus.FAILED)

            fun nextWorkout(
                queue: List<QueuedWorkout>,
                today: LocalDate,
            ): QueuedWorkout? =
                queue
                    .filter { !it.deleteRequested && it.status in ACTIVE && !it.date.isBefore(today) }
                    .minWithOrNull(compareBy<QueuedWorkout>({ it.date }, { it.id }))
        }
    }
