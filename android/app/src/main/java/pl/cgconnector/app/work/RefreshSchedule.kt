package pl.cgconnector.app.work

import pl.cgconnector.app.data.repository.RefreshResult
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Harmonogram odświeżania (czysta logika, bez WorkManagera):
 * - codziennie o [RUN_AT] Europe/Warsaw (serwer synchronizuje się o 14:00),
 * - gdy serwer jest niedostępny albo jeszcze nie pobrał dziś danych z Garmina: ponowienie co [RETRY_INTERVAL], ale tylko w oknie [RUN_AT, [RETRY_UNTIL]),
 *   bo laptop-serwer może włączyć się później; po [RETRY_UNTIL] następna próba jest jutro o [RUN_AT].
 * Godziny liczone w strefie Europe/Warsaw, więc zmiana czasu (DST) nie przesuwa pory odświeżania.
 */
object RefreshSchedule {
    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
    val RUN_AT: LocalTime = LocalTime.of(14, 20)
    val RETRY_UNTIL: LocalTime = LocalTime.of(23, 0)
    val RETRY_INTERVAL: Duration = Duration.ofHours(1)

    /** Godzina codziennej synchronizacji serwera z Garminem (CG_SYNC_HOUR). */
    val SERVER_SYNC_AT: LocalTime = LocalTime.of(14, 0)

    /** Po otwarciu aplikacji odświeżamy, jeśli ostatnie udane odświeżenie jest starsze niż to. */
    val FOREGROUND_STALE_AFTER: Duration = Duration.ofMinutes(15)

    /** Ostatnie [RUN_AT] nie później niż [now] (dziś albo wczoraj). */
    fun lastRunAt(now: Instant): ZonedDateTime = nextRunAt(now).minusDays(1)

    /**
     * Czy zaplanowane odświeżenie zostało pominięte: ostatnie udane jest sprzed ostatniego [RUN_AT].
     * Android (zwłaszcza Samsung) potrafi odłożyć zadanie z opóźnieniem o wiele godzin.
     */
    fun isMissed(
        lastSuccess: Instant?,
        now: Instant,
    ): Boolean = lastSuccess == null || lastSuccess.isBefore(lastRunAt(now).toInstant())

    /** Czy serwer zsynchronizował się z Garminem po dzisiejszej [SERVER_SYNC_AT] (jeśli już minęła). */
    fun serverSyncedToday(
        serverLastSyncAt: Instant?,
        now: Instant,
    ): Boolean {
        val local = now.atZone(ZONE)
        val syncToday = ZonedDateTime.of(local.toLocalDate(), SERVER_SYNC_AT, ZONE)
        if (local.isBefore(syncToday)) return true
        return serverLastSyncAt != null && !serverLastSyncAt.isBefore(syncToday.toInstant())
    }

    /** Najbliższe [RUN_AT] ściśle po [now]. */
    fun nextRunAt(now: Instant): ZonedDateTime {
        val today = now.atZone(ZONE).toLocalDate()
        val candidate = ZonedDateTime.of(today, RUN_AT, ZONE)
        return if (candidate.toInstant().isAfter(now)) candidate else ZonedDateTime.of(today.plusDays(1), RUN_AT, ZONE)
    }

    fun delayUntilNextRun(now: Instant): Duration = Duration.between(now, nextRunAt(now).toInstant())

    /** Czy o [now] wolno jeszcze ponawiać (okno od [RUN_AT] do [RETRY_UNTIL] czasu polskiego). */
    fun isInRetryWindow(now: Instant): Boolean {
        val time = now.atZone(ZONE).toLocalTime()
        return !time.isBefore(RUN_AT) && time.isBefore(RETRY_UNTIL)
    }

    /** Opóźnienie następnego uruchomienia po zakończeniu odświeżania z wynikiem [result]. */
    fun delayAfter(
        result: RefreshResult,
        now: Instant,
        serverLastSyncAt: Instant? = null,
    ): Duration =
        if (result == RefreshResult.Unavailable && isInRetryWindow(now)) {
            RETRY_INTERVAL
        } else if (result is RefreshResult.Success && !serverSyncedToday(serverLastSyncAt, now) && isInRetryWindow(now)) {
            // Serwer odpowiada, ale dziś jeszcze nie pobrał z Garmina (laptop włączony później): za godzinę jeszcze raz.
            RETRY_INTERVAL
        } else {
            // Sukces, brak konfiguracji, 401, zła odpowiedź albo koniec okna ponowień: jutro.
            delayUntilNextRun(now)
        }
}
