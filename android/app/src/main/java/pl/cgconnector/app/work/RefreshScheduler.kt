package pl.cgconnector.app.work

import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Clock
import java.time.Duration
import javax.inject.Inject

/**
 * Łańcuch jednorazowych zadań pod jedną unikalną nazwą: każde uruchomienie [RefreshWorker] planuje
 * następne (za 1 h przy braku serwera w oknie ponowień, inaczej jutro o 14:20). OneTimeWork zamiast
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
    }
