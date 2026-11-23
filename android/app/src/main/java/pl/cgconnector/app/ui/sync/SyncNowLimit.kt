package pl.cgconnector.app.ui.sync

import java.time.Duration
import java.time.Instant

/**
 * Ręczne pobranie z Garmina najwyżej raz na godzinę (serwer pilnuje tego samego, `MIN_INTERVAL`).
 * Aplikacja wyłącza przycisk wcześniej, żeby nie trzeba było czekać na odmowę serwera.
 */
object SyncNowLimit {
    val INTERVAL: Duration = Duration.ofHours(1)

    /**
     * Od kiedy przycisk znów działa; `null` = działa już teraz.
     * [serverLastSyncAt] = ostatnia udana synchronizacja serwera, [blockedUntil] = znane z tej sesji
     * (start własnego pobrania + godzina albo `retry_at` z odmowy serwera).
     */
    fun nextAllowedAt(
        serverLastSyncAt: Instant?,
        blockedUntil: Instant?,
        now: Instant,
    ): Instant? = listOfNotNull(serverLastSyncAt?.plus(INTERVAL), blockedUntil).maxOrNull()?.takeIf { it.isAfter(now) }
}
