package pl.cgconnector.app.domain

import java.time.Duration
import java.time.Instant

/** Wynik ostatniej próby odświeżenia aplikacji (zapisywany w Room). */
enum class RefreshOutcome {
    SUCCESS,
    UNAVAILABLE,
    UNAUTHORIZED,
    INVALID_RESPONSE,
    NOT_CONFIGURED,
}

/** Stan synchronizacji: serwer ↔ Garmin (z `/me`) i aplikacja ↔ serwer. */
data class SyncStatus(
    /** Ostatnia udana synchronizacja serwera z Garmin Connect. */
    val serverLastSyncAt: Instant? = null,
    val serverLastSyncStatus: String? = null,
    val garminStatus: String? = null,
    val lastRefreshAttemptAt: Instant? = null,
    val lastRefreshOutcome: RefreshOutcome? = null,
    /** Ostatnie udane odświeżenie danych w aplikacji. */
    val lastRefreshSuccessAt: Instant? = null,
)

enum class ServerState { ONLINE, OFFLINE, UNKNOWN }

/** Czy serwer odpowiedział przy ostatniej próbie. 401 i zła odpowiedź też oznaczają, że serwer działa. */
fun SyncStatus.serverState(): ServerState =
    when (lastRefreshOutcome) {
        RefreshOutcome.SUCCESS, RefreshOutcome.UNAUTHORIZED, RefreshOutcome.INVALID_RESPONSE -> ServerState.ONLINE
        RefreshOutcome.UNAVAILABLE -> ServerState.OFFLINE
        RefreshOutcome.NOT_CONFIGURED, null -> ServerState.UNKNOWN
    }

object DataFreshness {
    val MAX_AGE: Duration = Duration.ofHours(24)

    /**
     * Dane są stare, gdy aplikacja nie odświeżyła ich od ponad [MAX_AGE] albo serwer od ponad [MAX_AGE]
     * nie zsynchronizował się z Garminem. Brak którejkolwiek daty (nigdy) też oznacza dane stare.
     */
    fun isStale(
        now: Instant,
        lastRefreshSuccessAt: Instant?,
        serverLastSyncAt: Instant?,
    ): Boolean {
        if (lastRefreshSuccessAt == null || serverLastSyncAt == null) return true
        val oldest = minOf(lastRefreshSuccessAt, serverLastSyncAt)
        return Duration.between(oldest, now) > MAX_AGE
    }
}
