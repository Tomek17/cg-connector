package pl.cgconnector.app.work

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import pl.cgconnector.app.data.repository.RefreshResult
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime

class RefreshScheduleTest {
    private fun local(text: String): Instant = ZonedDateTime.parse(text).toInstant()

    @Test
    fun beforeRunTimeSchedulesToday() {
        val now = local("2026-11-18T09:00+01:00[Europe/Warsaw]")

        assertThat(RefreshSchedule.nextRunAt(now)).isEqualTo(ZonedDateTime.parse("2026-11-18T14:20+01:00[Europe/Warsaw]"))
        assertThat(RefreshSchedule.delayUntilNextRun(now)).isEqualTo(Duration.ofHours(5).plusMinutes(20))
    }

    @Test
    fun atOrAfterRunTimeSchedulesTomorrow() {
        assertThat(RefreshSchedule.delayUntilNextRun(local("2026-11-18T14:20+01:00[Europe/Warsaw]")))
            .isEqualTo(Duration.ofHours(24))
        assertThat(RefreshSchedule.delayUntilNextRun(local("2026-11-18T15:00+01:00[Europe/Warsaw]")))
            .isEqualTo(Duration.ofHours(23).plusMinutes(20))
    }

    @Test
    fun usesPolishLocalDateNotUtcDate() {
        // 23:30 UTC 18.11 to już 00:30 19.11 w Polsce: najbliższe 14:20 to 19.11, a nie 20.11.
        val now = Instant.parse("2026-11-18T23:30:00Z")

        assertThat(RefreshSchedule.nextRunAt(now)).isEqualTo(ZonedDateTime.parse("2026-11-19T14:20+01:00[Europe/Warsaw]"))
        assertThat(RefreshSchedule.delayUntilNextRun(now)).isEqualTo(Duration.ofHours(13).plusMinutes(50))
    }

    @Test
    fun springForwardDayIsOneHourShorter() {
        // 29.03.2026: 02:00 CET -> 03:00 CEST.
        val now = local("2026-03-28T15:00+01:00[Europe/Warsaw]")

        val next = RefreshSchedule.nextRunAt(now)

        assertThat(next).isEqualTo(ZonedDateTime.parse("2026-03-29T14:20+02:00[Europe/Warsaw]"))
        assertThat(next.toLocalTime()).isEqualTo(RefreshSchedule.RUN_AT)
        assertThat(RefreshSchedule.delayUntilNextRun(now)).isEqualTo(Duration.ofHours(22).plusMinutes(20))
    }

    @Test
    fun fallBackDayIsOneHourLonger() {
        // 25.10.2026: 03:00 CEST -> 02:00 CET.
        val now = local("2026-10-24T15:00+02:00[Europe/Warsaw]")

        val next = RefreshSchedule.nextRunAt(now)

        assertThat(next).isEqualTo(ZonedDateTime.parse("2026-10-25T14:20+01:00[Europe/Warsaw]"))
        assertThat(RefreshSchedule.delayUntilNextRun(now)).isEqualTo(Duration.ofHours(24).plusMinutes(20))
    }

    @Test
    fun duringDstNightStillSchedulesSameDayAfternoon() {
        val now = local("2026-03-29T01:30+01:00[Europe/Warsaw]")

        assertThat(RefreshSchedule.nextRunAt(now)).isEqualTo(ZonedDateTime.parse("2026-03-29T14:20+02:00[Europe/Warsaw]"))
        assertThat(RefreshSchedule.delayUntilNextRun(now)).isEqualTo(Duration.ofHours(11).plusMinutes(50))
    }

    @Test
    fun retryWindowIsFromRunTimeUntil23() {
        assertThat(RefreshSchedule.isInRetryWindow(local("2026-11-18T14:20+01:00[Europe/Warsaw]"))).isTrue()
        assertThat(RefreshSchedule.isInRetryWindow(local("2026-11-18T22:59+01:00[Europe/Warsaw]"))).isTrue()
        assertThat(RefreshSchedule.isInRetryWindow(local("2026-11-18T23:00+01:00[Europe/Warsaw]"))).isFalse()
        assertThat(RefreshSchedule.isInRetryWindow(local("2026-11-19T00:30+01:00[Europe/Warsaw]"))).isFalse()
        assertThat(RefreshSchedule.isInRetryWindow(local("2026-11-18T10:00+01:00[Europe/Warsaw]"))).isFalse()
    }

    @Test
    fun unavailableInWindowRetriesInOneHour() {
        val now = local("2026-11-18T14:21+01:00[Europe/Warsaw]")

        assertThat(RefreshSchedule.delayAfter(RefreshResult.Unavailable, now)).isEqualTo(Duration.ofHours(1))
    }

    @Test
    fun unavailableAfter23WaitsForTomorrow() {
        val now = local("2026-11-18T23:20+01:00[Europe/Warsaw]")

        assertThat(RefreshSchedule.delayAfter(RefreshResult.Unavailable, now)).isEqualTo(Duration.ofHours(15))
    }

    @Test
    fun nonRetryableResultsWaitForTomorrow() {
        val now = local("2026-11-18T14:21+01:00[Europe/Warsaw]")
        val tomorrow = Duration.ofHours(23).plusMinutes(59)

        listOf(
            RefreshResult.Success(3),
            RefreshResult.NotConfigured,
            RefreshResult.Unauthorized,
            RefreshResult.InvalidResponse,
        ).forEach { result ->
            assertThat(RefreshSchedule.delayAfter(result, now)).isEqualTo(tomorrow)
        }
    }
}
