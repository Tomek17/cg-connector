package pl.cgconnector.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import pl.cgconnector.app.data.repository.ActivityRepository
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
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val result = repository.refresh()
            scheduler.scheduleNext(RefreshSchedule.delayAfter(result, clock.instant()))
            return Result.success()
        }

        companion object {
            const val UNIQUE_NAME = "daily-refresh"
        }
    }
