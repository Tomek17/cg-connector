package pl.cgconnector.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import pl.cgconnector.app.data.repository.ActivityRepository
import pl.cgconnector.app.data.repository.RefreshResult

/** Pobiera nowe dane z serwera. Niedostępny serwer = ponowienie z backoffem. */
@HiltWorker
class RefreshWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repository: ActivityRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            when (repository.refresh()) {
                is RefreshResult.Success -> Result.success()
                RefreshResult.NotConfigured -> Result.success()
                RefreshResult.Unavailable -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
            }

        companion object {
            const val UNIQUE_NAME = "daily-refresh"

            /** 1 h backoff liniowo: od ~14:20 do ~23:00 (laptop-serwer może włączyć się później). */
            const val MAX_ATTEMPTS = 9
        }
    }
