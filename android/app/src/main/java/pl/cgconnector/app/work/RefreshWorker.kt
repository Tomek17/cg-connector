package pl.cgconnector.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import pl.cgconnector.app.alerts.HomeSurfaces
import pl.cgconnector.app.data.repository.ActivityRepository
import pl.cgconnector.app.data.repository.RefreshResult
import pl.cgconnector.app.data.repository.StatsRepository
import pl.cgconnector.app.data.repository.WorkoutQueueTrigger
import java.time.Clock

/**
 * Pobiera nowe dane z serwera i planuje następne uruchomienie wg [RefreshSchedule].
 * Zawsze kończy się sukcesem: ponowienia planujemy sami (stałe co 1 h do 23:00), a nie przez
 * Result.retry(), którego backoff rośnie z każdą próbą.
 */
@HiltWorker
class RefreshWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repository: ActivityRepository,
        private val scheduler: RefreshScheduler,
        private val workoutQueue: WorkoutQueueTrigger,
        private val homeSurfaces: HomeSurfaces,
        private val stats: StatsRepository,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val result = repository.refresh()
            // Serwer odpowiada: wysyłamy kolejkę treningów (MASTER_PROMPT 3.4).
            if (result is RefreshResult.Success) workoutQueue.trigger()
            // Widżet (zmiana dnia) i powiadomienia (np. dane od dawna nieaktualne) także bez otwierania aplikacji.
            homeSurfaces.refreshNow()
            val serverLastSyncAt = stats.observeSyncStatus().first().serverLastSyncAt
            scheduler.scheduleNext(RefreshSchedule.delayAfter(result, clock.instant(), serverLastSyncAt))
            return Result.success()
        }

        companion object {
            const val UNIQUE_NAME = "daily-refresh"
        }
    }
