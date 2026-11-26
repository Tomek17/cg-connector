package pl.cgconnector.app.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import pl.cgconnector.app.data.repository.RefreshResult
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

class RefreshScheduleTest {
    private fun local(text: String): Instant = ZonedDateTime.parse(text).toInstant()

    private fun waw(text: String): ZonedDateTime = ZonedDateTime.parse("$text[Europe/Warsaw]")

    @Test
    fun runsTwentyMinutesAfterEachServerSync() {
        assertThat(RefreshSchedule.nextRunAt(local("2026-11-18T07:00+01:00[Europe/Warsaw]"))).isEqualTo(waw("2026-11-18T08:20+01:00"))
        assertThat(RefreshSchedule.nextRunAt(local("2026-11-18T08:20+01:00[Europe/Warsaw]"))).isEqualTo(waw("2026-11-18T17:20+01:00"))
        assertThat(RefreshSchedule.nextRunAt(local("2026-11-18T18:00+01:00[Europe/Warsaw]"))).isEqualTo(waw("2026-11-18T21:20+01:00"))
        assertThat(RefreshSchedule.nextRunAt(local("2026-11-18T21:30+01:00[Europe/Warsaw]"))).isEqualTo(waw("2026-11-19T08:20+01:00"))
        assertThat(RefreshSchedule.delayUntilNextRun(local("2026-11-18T21:20+01:00[Europe/Warsaw]")))
            .isEqualTo(Duration.ofHours(11))
    }

    @Test
    fun usesPolishLocalDateNotUtcDate() {
        // 23:30 UTC 18.11 to już 00:30 19.11 w Polsce: najbliższa pora to 19.11 8:20.
        val now = Instant.parse("2026-11-18T23:30:00Z")

        assertThat(RefreshSchedule.nextRunAt(now)).isEqualTo(waw("2026-11-19T08:20+01:00"))
        assertThat(RefreshSchedule.delayUntilNextRun(now)).isEqualTo(Duration.ofHours(7).plusMinutes(50))
    }

    @Test
    fun dstChangeKeepsLocalTimes() {
        // 29.03.2026: 02:00 CET -> 03:00 CEST; noc jest o godzinę krótsza.
        val now = local("2026-03-28T21:20+01:00[Europe/Warsaw]")

        assertThat(RefreshSchedule.nextRunAt(now)).isEqualTo(waw("2026-03-29T08:20+02:00"))
        assertThat(RefreshSchedule.delayUntilNextRun(now)).isEqualTo(Duration.ofHours(10))
    }

    @Test
    fun retryWindowIsFromFirstRunUntil23() {
        assertThat(RefreshSchedule.isInRetryWindow(local("2026-11-18T08:20+01:00[Europe/Warsaw]"))).isTrue()
        assertThat(RefreshSchedule.isInRetryWindow(local("2026-11-18T22:59+01:00[Europe/Warsaw]"))).isTrue()
        assertThat(RefreshSchedule.isInRetryWindow(local("2026-11-18T23:00+01:00[Europe/Warsaw]"))).isFalse()
        assertThat(RefreshSchedule.isInRetryWindow(local("2026-11-18T07:00+01:00[Europe/Warsaw]"))).isFalse()
    }

    @Test
    fun unavailableRetriesInOneHourButNotPastNextRun() {
        assertThat(RefreshSchedule.delayAfter(RefreshResult.Unavailable, local("2026-11-18T08:21+01:00[Europe/Warsaw]")))
            .isEqualTo(Duration.ofHours(1))
        assertThat(RefreshSchedule.delayAfter(RefreshResult.Unavailable, local("2026-11-18T16:50+01:00[Europe/Warsaw]")))
            .isEqualTo(Duration.ofMinutes(30))
        // Po 23:00: następna pora jutro rano.
        assertThat(RefreshSchedule.delayAfter(RefreshResult.Unavailable, local("2026-11-18T23:20+01:00[Europe/Warsaw]")))
            .isEqualTo(Duration.ofHours(9))
    }

    @Test
    fun nonRetryableResultsWaitForNextRun() {
        val now = local("2026-11-18T08:21+01:00[Europe/Warsaw]")
        val serverSynced = local("2026-11-18T08:05+01:00[Europe/Warsaw]")
        val nextRun = Duration.ofHours(8).plusMinutes(59)

        listOf(
            RefreshResult.Success(3),
            RefreshResult.NotConfigured,
            RefreshResult.Unauthorized,
            RefreshResult.InvalidResponse,
        ).forEach { result ->
            assertThat(RefreshSchedule.delayAfter(result, now, serverSynced)).isEqualTo(nextRun)
        }
    }

    @Test
    fun successBeforeServerSyncedRetriesInOneHour() {
        val now = local("2026-11-18T17:21+01:00[Europe/Warsaw]")
        val morning = local("2026-11-18T08:03+01:00[Europe/Warsaw]")

        assertThat(RefreshSchedule.delayAfter(RefreshResult.Success(0), now, morning)).isEqualTo(Duration.ofHours(1))
        // Po 23:00 już nie ponawiamy.
        val late = local("2026-11-18T23:10+01:00[Europe/Warsaw]")
        assertThat(RefreshSchedule.delayAfter(RefreshResult.Success(0), late, morning)).isEqualTo(Duration.ofHours(9).plusMinutes(10))
    }

    @Test
    fun missedRefreshIsDetected() {
        val now = local("2026-11-24T17:40+01:00[Europe/Warsaw]")

        assertThat(RefreshSchedule.isMissed(local("2026-11-24T08:22+01:00[Europe/Warsaw]"), now)).isTrue()
        assertThat(RefreshSchedule.isMissed(local("2026-11-24T17:25+01:00[Europe/Warsaw]"), now)).isFalse()
        assertThat(RefreshSchedule.isMissed(null, now)).isTrue()
        // Przed 8:20 wystarczy wczorajsze odświeżenie po 21:20.
        val morning = local("2026-11-24T07:00+01:00[Europe/Warsaw]")
        assertThat(RefreshSchedule.isMissed(local("2026-11-23T21:22+01:00[Europe/Warsaw]"), morning)).isFalse()
        assertThat(RefreshSchedule.isMissed(local("2026-11-23T18:00+01:00[Europe/Warsaw]"), morning)).isTrue()
    }

    @Test
    fun serverSyncCountsOnlyAfterLastSyncSlot() {
        val now = local("2026-11-24T17:30+01:00[Europe/Warsaw]")

        assertThat(RefreshSchedule.serverSyncedSinceLastSlot(local("2026-11-24T17:02+01:00[Europe/Warsaw]"), now)).isTrue()
        assertThat(RefreshSchedule.serverSyncedSinceLastSlot(local("2026-11-24T08:40+01:00[Europe/Warsaw]"), now)).isFalse()
        assertThat(RefreshSchedule.serverSyncedSinceLastSlot(null, now)).isFalse()
    }
}
