package pl.cgconnector.app.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import pl.cgconnector.app.data.repository.RefreshResult
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.data.repository.WorkoutQueueTrigger
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * Wysyła kolejkę treningów. Brak serwera albo sieci: `retry()` z rosnącym backoffem (laptop może się
 * włączyć później). 401, odrzucone wpisy (4xx) i zła odpowiedź: bez ponowień, aż do następnego wyzwolenia.
 */
@HiltWorker
class WorkoutQueueWorker
    @AssistedInject
    constructor(
        @Assisted context: Context,
        @Assisted params: WorkerParameters,
        private val repository: WorkoutQueueRepository,
    ) : CoroutineWorker(context, params) {
        override suspend fun doWork(): Result =
            when (repository.sync()) {
                RefreshResult.Unavailable -> Result.retry()
                else -> Result.success()
            }

        companion object {
            const val UNIQUE_NAME = "workout-queue"
        }
    }

/**
 * REPLACE zamiast KEEP/APPEND: nowy szkic albo „Odśwież” ma ruszyć od razu, a nie czekać na backoff
 * poprzedniej próby. Przerwanie trwającej wysyłki jest bezpieczne, bo każda operacja na serwerze jest
 * idempotentna (`client_id`, jeden wpis na trening i dzień, powtórne usunięcie).
 */
class WorkManagerWorkoutQueueTrigger
    @Inject
    constructor(
        private val workManager: WorkManager,
    ) : WorkoutQueueTrigger {
        override fun trigger() {
            val request =
                OneTimeWorkRequestBuilder<WorkoutQueueWorker>()
                    .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
                    .build()
            workManager.enqueueUniqueWork(WorkoutQueueWorker.UNIQUE_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        private companion object {
            const val BACKOFF_SECONDS = 60L
        }
    }
