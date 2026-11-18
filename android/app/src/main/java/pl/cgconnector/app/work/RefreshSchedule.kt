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
 * - gdy serwer jest niedostępny: ponowienie co [RETRY_INTERVAL], ale tylko w oknie [RUN_AT, [RETRY_UNTIL]),
 *   bo laptop-serwer może włączyć się później; po [RETRY_UNTIL] następna próba jest jutro o [RUN_AT].
 * Godziny liczone w strefie Europe/Warsaw, więc zmiana czasu (DST) nie przesuwa pory odświeżania.
 */
object RefreshSchedule {
    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
    val RUN_AT: LocalTime = LocalTime.of(14, 20)
    val RETRY_UNTIL: LocalTime = LocalTime.of(23, 0)
    val RETRY_INTERVAL: Duration = Duration.ofHours(1)

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
    ): Duration =
        if (result == RefreshResult.Unavailable && isInRetryWindow(now)) {
            RETRY_INTERVAL
        } else {
            // Sukces, brak konfiguracji, 401, zła odpowiedź albo koniec okna ponowień: jutro.
            delayUntilNextRun(now)
        }
}
