package pl.cgconnector.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import pl.cgconnector.app.testing.testActivity
import java.time.Instant
import java.time.LocalDate

class WeekGroupingTest {
    @Test
    fun `week starts on monday in warsaw time`() {
        // Niedziela 23:30 UTC = poniedziałek 00:30 w Warszawie: to już nowy tydzień.
        assertThat(WeekGrouping.weekStart(Instant.parse("2026-11-22T23:30:00Z")))
            .isEqualTo(LocalDate.of(2026, 11, 23))
        assertThat(WeekGrouping.weekStart(Instant.parse("2026-11-22T10:00:00Z")))
            .isEqualTo(LocalDate.of(2026, 11, 16))
    }

    @Test
    fun `groups newest first and keeps order inside week`() {
        val activities =
            listOf(
                testActivity(3, start = Instant.parse("2026-11-24T07:00:00Z")),
                testActivity(2, start = Instant.parse("2026-11-20T07:00:00Z")),
                testActivity(1, start = Instant.parse("2026-11-17T07:00:00Z")),
            )

        val weeks = WeekGrouping.group(activities)

        assertThat(weeks.map { it.weekStart })
            .containsExactly(LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 16))
            .inOrder()
        assertThat(weeks[1].activities.map { it.id }).containsExactly(2L, 1L).inOrder()
    }

    @Test
    fun `totals count only running distance and split by type`() {
        val week =
            WeekSummary(
                LocalDate.of(2026, 11, 16),
                listOf(
                    testActivity(1, ActivityType.RUNNING).copy(distanceM = 10_000.0, durationSec = 3_000),
                    testActivity(2, ActivityType.STRENGTH).copy(durationSec = 3_600),
                    testActivity(3, ActivityType.RUNNING).copy(distanceM = 5_000.0, durationSec = 1_500),
                    testActivity(4, ActivityType.OTHER).copy(distanceM = 20_000.0, durationSec = 3_600),
                ),
            )

        assertThat(week.count).isEqualTo(4)
        assertThat(week.durationSec).isEqualTo(11_700)
        assertThat(week.runningDistanceM).isEqualTo(15_000.0)
        assertThat(week.byType.map { it.type })
            .containsExactly(ActivityType.RUNNING, ActivityType.STRENGTH, ActivityType.OTHER)
            .inOrder()
        val running = week.byType.first()
        assertThat(running.count).isEqualTo(2)
        assertThat(running.distanceM).isEqualTo(15_000.0)
        assertThat(week.byType[1].distanceM).isNull()
    }

    @Test
    fun `current week is empty when nothing happened yet`() {
        val activities = listOf(testActivity(1, start = Instant.parse("2026-11-17T07:00:00Z")))

        val week = WeekGrouping.currentWeek(activities, today = LocalDate.of(2026, 11, 25))

        assertThat(week.weekStart).isEqualTo(LocalDate.of(2026, 11, 23))
        assertThat(week.count).isEqualTo(0)
    }
}
