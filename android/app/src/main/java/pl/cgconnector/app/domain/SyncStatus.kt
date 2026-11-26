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

enum class Freshness {
    FRESH,

    /** Aplikacja nigdy nie pobrała danych z serwera (świeża instalacja). */
    NEVER_FETCHED,

    STALE,
}

/**
 * Progi „dane stare”. Serwer synchronizuje się z Garminem o 8:00, 17:00 i 21:00, a aplikacja odświeża
 * 20 minut później. Progi są z zapasem na wyłączony serwer albo telefon.
 * Progi mają zapas: [SERVER_SYNC_MAX_AGE] na opóźniony sync, [APP_REFRESH_MAX_AGE] na ponowienia
 * odświeżania do 23:00 i pominięty dzień przy wyłączonym telefonie.
 */
object DataFreshness {
    val SERVER_SYNC_MAX_AGE: Duration = Duration.ofHours(26)
    val APP_REFRESH_MAX_AGE: Duration = Duration.ofHours(30)

    fun evaluate(
        now: Instant,
        lastRefreshSuccessAt: Instant?,
        serverLastSyncAt: Instant?,
    ): Freshness =
        when {
            lastRefreshSuccessAt == null -> Freshness.NEVER_FETCHED
            Duration.between(lastRefreshSuccessAt, now) > APP_REFRESH_MAX_AGE -> Freshness.STALE
            // Serwer nigdy nie zsynchronizował się z Garminem: dane z serwera nic nie znaczą.
            serverLastSyncAt == null -> Freshness.STALE
            Duration.between(serverLastSyncAt, now) > SERVER_SYNC_MAX_AGE -> Freshness.STALE
            else -> Freshness.FRESH
        }
}
