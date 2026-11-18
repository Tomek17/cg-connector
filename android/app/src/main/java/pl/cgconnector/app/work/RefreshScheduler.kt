package pl.cgconnector.app.work

import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.time.Clock
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/** Planuje codzienne odświeżenie ok. 14:20 czasu polskiego (po syncu serwera o 14:00). */
class RefreshScheduler
    @Inject
    constructor(
        private val workManager: WorkManager,
        private val clock: Clock,
    ) {
        fun scheduleDaily() {
            val request =
                PeriodicWorkRequestBuilder<RefreshWorker>(1, TimeUnit.DAYS)
                    .setInitialDelay(delayUntilNextRun())
                    .setConstraints(Constraints(requiredNetworkType = NetworkType.CONNECTED))
                    .setBackoffCriteria(BackoffPolicy.LINEAR, Duration.ofHours(1))
                    .build()
            workManager.enqueueUniquePeriodicWork(RefreshWorker.UNIQUE_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        private fun delayUntilNextRun(): Duration {
            val now = ZonedDateTime.now(clock.withZone(ZONE))
            var next = now.with(RUN_AT)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next)
        }

        private companion object {
            val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
            val RUN_AT: LocalTime = LocalTime.of(14, 20)
        }
    }
