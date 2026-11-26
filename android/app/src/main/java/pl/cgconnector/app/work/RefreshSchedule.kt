package pl.cgconnector.app.work

import pl.cgconnector.app.data.repository.RefreshResult
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Harmonogram odświeżania (czysta logika, bez WorkManagera):
 * - serwer pobiera dane z Garmina o [SERVER_SYNC_TIMES] (CG_SYNC_HOURS), aplikacja odświeża się
 *   [AFTER_SERVER_SYNC] później, czyli o [RUN_TIMES] czasu polskiego,
 * - gdy serwer jest niedostępny albo jeszcze nie pobrał danych z ostatniej pory: ponowienie co
 *   [RETRY_INTERVAL] (nie później niż następna pora), ale tylko od pierwszej pory do [RETRY_UNTIL].
 * Godziny liczone w strefie Europe/Warsaw, więc zmiana czasu (DST) nie przesuwa pór odświeżania.
 */
object RefreshSchedule {
    val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")

    /** Pory synchronizacji serwera z Garminem (domyślne CG_SYNC_HOURS). */
    val SERVER_SYNC_TIMES: List<LocalTime> = listOf(8, 17, 21).map { LocalTime.of(it, 0) }
    val AFTER_SERVER_SYNC: Duration = Duration.ofMinutes(20)
    val RUN_TIMES: List<LocalTime> = SERVER_SYNC_TIMES.map { it.plus(AFTER_SERVER_SYNC) }
    val RETRY_UNTIL: LocalTime = LocalTime.of(23, 0)
    val RETRY_INTERVAL: Duration = Duration.ofHours(1)

    /** Po otwarciu aplikacji odświeżamy, jeśli ostatnie udane odświeżenie jest starsze niż to. */
    val FOREGROUND_STALE_AFTER: Duration = Duration.ofMinutes(15)

    /** Pory z [times] z wczoraj, dziś i jutro, rosnąco. */
    private fun slotsAround(
        now: Instant,
        times: List<LocalTime>,
    ): List<ZonedDateTime> {
        val today = now.atZone(ZONE).toLocalDate()
        return (-1L..1L).flatMap { day -> times.map { ZonedDateTime.of(today.plusDays(day), it, ZONE) } }.sorted()
    }

    /** Ostatnia pora odświeżenia nie później niż [now]. */
    fun lastRunAt(now: Instant): ZonedDateTime = slotsAround(now, RUN_TIMES).last { !it.toInstant().isAfter(now) }

    /** Najbliższa pora odświeżenia ściśle po [now]. */
    fun nextRunAt(now: Instant): ZonedDateTime = slotsAround(now, RUN_TIMES).first { it.toInstant().isAfter(now) }

    /** Ostatnia pora synchronizacji serwera nie później niż [now]. */
    fun lastServerSyncSlot(now: Instant): ZonedDateTime = slotsAround(now, SERVER_SYNC_TIMES).last { !it.toInstant().isAfter(now) }

    /**
     * Czy zaplanowane odświeżenie zostało pominięte: ostatnie udane jest sprzed ostatniej pory.
     * Android (zwłaszcza Samsung) potrafi odłożyć zadanie z opóźnieniem o wiele godzin.
     */
    fun isMissed(
        lastSuccess: Instant?,
        now: Instant,
    ): Boolean = lastSuccess == null || lastSuccess.isBefore(lastRunAt(now).toInstant())

    /** Czy serwer pobrał dane z Garmina po ostatniej porze synchronizacji. */
    fun serverSyncedSinceLastSlot(
        serverLastSyncAt: Instant?,
        now: Instant,
    ): Boolean = serverLastSyncAt != null && !serverLastSyncAt.isBefore(lastServerSyncSlot(now).toInstant())

    fun delayUntilNextRun(now: Instant): Duration = Duration.between(now, nextRunAt(now).toInstant())

    /** Czy o [now] wolno jeszcze ponawiać (od pierwszej pory dnia do [RETRY_UNTIL] czasu polskiego). */
    fun isInRetryWindow(now: Instant): Boolean {
        val time = now.atZone(ZONE).toLocalTime()
        return !time.isBefore(RUN_TIMES.first()) && time.isBefore(RETRY_UNTIL)
    }

    /** Opóźnienie następnego uruchomienia po zakończeniu odświeżania z wynikiem [result]. */
    fun delayAfter(
        result: RefreshResult,
        now: Instant,
        serverLastSyncAt: Instant? = null,
    ): Duration {
        val retry =
            isInRetryWindow(now) &&
                (
                    result == RefreshResult.Unavailable ||
                        // Serwer odpowiada, ale jeszcze nie pobrał z Garmina z ostatniej pory: za godzinę jeszcze raz.
                        (result is RefreshResult.Success && !serverSyncedSinceLastSlot(serverLastSyncAt, now))
                )
        val untilNext = delayUntilNextRun(now)
        // Sukces, brak konfiguracji, 401, zła odpowiedź albo koniec okna ponowień: następna pora.
        return if (retry) minOf(RETRY_INTERVAL, untilNext) else untilNext
    }
}
