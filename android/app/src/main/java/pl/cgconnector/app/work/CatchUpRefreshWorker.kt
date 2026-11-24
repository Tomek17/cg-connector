package pl.cgconnector.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.flow.first
import pl.cgconnector.app.data.repository.StatsRepository
import java.time.Clock

/**
 * Siatka bezpieczeństwa co kilka godzin: jeśli codzienne odświeżenie o 14:20 nie doszło do skutku
 * (Android/Samsung odłożył zadanie, telefon był bez sieci), odświeża od razu. Inaczej nic nie robi.
 */
@HiltWorker
class CatchUpRefreshWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val stats: StatsRepository,
        private val refresher: AppRefresher,
        private val clock: Clock,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result {
            val lastSuccess = stats.observeSyncStatus().first().lastRefreshSuccessAt
            if (RefreshSchedule.isMissed(lastSuccess, clock.instant())) refresher.refresh()
            return Result.success()
        }

        companion object {
            const val UNIQUE_NAME = "catch-up-refresh"
        }
    }
