package pl.cgconnector.app.work

import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Clock
import java.time.Duration
import javax.inject.Inject

/**
 * Łańcuch jednorazowych zadań pod jedną unikalną nazwą: każde uruchomienie [RefreshWorker] planuje
 * następne (za 1 h przy braku serwera w oknie ponowień, inaczej o następnej porze: 8:20, 17:20 albo 21:20). OneTimeWork zamiast
 * PeriodicWork, bo okresowe zadanie dryfuje i nie pozwala na stałe ponowienia co godzinę.
 */
class RefreshScheduler
    @Inject
    constructor(
        private val workManager: WorkManager,
        private val clock: Clock,
    ) {
        /** Przy starcie aplikacji: nie rusza już zaplanowanego łańcucha (KEEP). */
        fun ensureScheduled() {
            enqueue(RefreshSchedule.delayUntilNextRun(clock.instant()), ExistingWorkPolicy.KEEP)
            // Siatka bezpieczeństwa, gdy system odłoży zaplanowane zadanie (oszczędzanie baterii).
            val catchUp =
                PeriodicWorkRequestBuilder<CatchUpRefreshWorker>(CATCH_UP_EVERY)
                    .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                    .build()
            workManager.enqueueUniquePeriodicWork(CatchUpRefreshWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.KEEP, catchUp)
        }

        /**
         * Z wnętrza działającego workera. KEEP nic by nie zrobił (bieżące zadanie jest jeszcze aktywne),
         * więc dopinamy następne do łańcucha; startuje po zakończeniu bieżącego, z podanym opóźnieniem.
         */
        fun scheduleNext(delay: Duration) {
            enqueue(delay, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }

        private fun enqueue(
            delay: Duration,
            policy: ExistingWorkPolicy,
        ) {
            val request =
                OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setInitialDelay(delay)
                    .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                    .build()
            workManager.enqueueUniqueWork(RefreshWorker.UNIQUE_NAME, policy, request)
        }

        private companion object {
            val CATCH_UP_EVERY: Duration = Duration.ofHours(2)
        }
    }
