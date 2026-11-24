package pl.cgconnector.app.work

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.cgconnector.app.alerts.HomeSurfaces
import pl.cgconnector.app.data.repository.ActivityRepository
import pl.cgconnector.app.data.repository.RefreshResult
import pl.cgconnector.app.data.repository.StatsRepository
import pl.cgconnector.app.data.repository.WorkoutQueueTrigger
import java.time.Clock
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/** Odświeżenie danych poza codziennym harmonogramem: po otwarciu aplikacji i z siatki bezpieczeństwa. */
@Singleton
class AppRefresher
    @Inject
    constructor(
        private val repository: ActivityRepository,
        private val stats: StatsRepository,
        private val workoutQueue: WorkoutQueueTrigger,
        private val homeSurfaces: HomeSurfaces,
        private val clock: Clock,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private val running = Mutex()

        /** Aplikacja na pierwszym planie: odśwież, jeśli dane są starsze niż [RefreshSchedule.FOREGROUND_STALE_AFTER]. */
        fun onForeground() {
            scope.launch {
                val lastSuccess = stats.observeSyncStatus().first().lastRefreshSuccessAt
                if (isStale(lastSuccess, RefreshSchedule.FOREGROUND_STALE_AFTER)) refresh()
            }
        }

        /** Jedno odświeżenie naraz; drugie wywołanie w trakcie nic nie robi. */
        suspend fun refresh() {
            if (!running.tryLock()) return
            try {
                val result = repository.refresh()
                if (result is RefreshResult.Success) workoutQueue.trigger()
                homeSurfaces.refreshNow()
            } finally {
                running.unlock()
            }
        }

        private fun isStale(
            lastSuccess: java.time.Instant?,
            maxAge: Duration,
        ): Boolean = lastSuccess == null || Duration.between(lastSuccess, clock.instant()) > maxAge
    }
