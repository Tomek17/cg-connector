package pl.cgconnector.app.domain.alerts

import pl.cgconnector.app.domain.DataFreshness
import pl.cgconnector.app.domain.Freshness
import pl.cgconnector.app.domain.SyncStatus
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.domain.workout.QueuedWorkout
import java.time.Instant
import java.time.LocalDate

/** Powiadomienie do pokazania. */
sealed interface Alert {
    /** Trening z Planu nie dotarł do kalendarza Garmin (serwer zgłosił błąd). */
    data class WorkoutFailed(
        val id: Long,
        val name: String,
        val date: LocalDate,
    ) : Alert

    /** Dane w telefonie są stare (serwer albo aplikacja dawno się nie synchronizowały). */
    data object DataStale : Alert

    /** Serwer stracił logowanie do Garmina. */
    data object GarminLogin : Alert
}

/** Co już pokazaliśmy, żeby każdy problem dać znać raz, a nie przy każdym odświeżeniu. */
data class AlertState(
    val notifiedFailedIds: Set<Long> = emptySet(),
    val staleNotified: Boolean = false,
    val loginNotified: Boolean = false,
)

object AlertRules {
    /** Nowe powiadomienia i stan po nich. Problem, który minął, „uzbraja” powiadomienie na następny raz. */
    fun evaluate(
        queue: List<QueuedWorkout>,
        status: SyncStatus,
        now: Instant,
        state: AlertState,
    ): Pair<List<Alert>, AlertState> {
        val failed = queue.filter { it.status == QueueStatus.FAILED && !it.deleteRequested }
        val stale = DataFreshness.evaluate(now, status.lastRefreshSuccessAt, status.serverLastSyncAt) == Freshness.STALE
        val needsLogin = status.garminStatus == NEEDS_LOGIN
        val alerts =
            buildList {
                failed.filter { it.id !in state.notifiedFailedIds }.forEach { add(Alert.WorkoutFailed(it.id, it.name, it.date)) }
                if (stale && !state.staleNotified) add(Alert.DataStale)
                if (needsLogin && !state.loginNotified) add(Alert.GarminLogin)
            }
        return alerts to AlertState(failed.map { it.id }.toSet(), stale, needsLogin)
    }

    private const val NEEDS_LOGIN = "needs_login"
}
