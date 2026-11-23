package pl.cgconnector.app.alerts

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import pl.cgconnector.app.data.repository.GoalRepository
import pl.cgconnector.app.data.repository.StatsRepository
import pl.cgconnector.app.data.repository.WorkoutQueueRepository
import pl.cgconnector.app.widget.TodayWidgetProvider
import java.time.Clock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wszystko, co aplikacja pokazuje poza swoim oknem: widżet i powiadomienia.
 * Póki proces żyje, reaguje na każdą zmianę Planu, celu i stanu synchronizacji;
 * [RefreshWorker][pl.cgconnector.app.work.RefreshWorker] woła [refreshNow] także w tle.
 */
@Singleton
class HomeSurfaces
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val queue: WorkoutQueueRepository,
        private val stats: StatsRepository,
        private val goals: GoalRepository,
        private val alerts: AlertNotifier,
        private val clock: Clock,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        fun start() {
            scope.launch {
                combine(queue.observeQueue(), stats.observeSyncStatus(), goals.observeGoal()) { q, s, g -> Triple(q, s, g) }
                    .distinctUntilChanged()
                    .collect { (q, s, _) ->
                        TodayWidgetProvider.requestUpdate(context)
                        alerts.check(q, s, clock.instant())
                    }
            }
        }

        suspend fun refreshNow() {
            TodayWidgetProvider.requestUpdate(context)
            alerts.check(queue.observeQueue().first(), stats.observeSyncStatus().first(), clock.instant())
        }
    }
