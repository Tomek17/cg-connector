package pl.cgconnector.app.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class RaceGoalTest {
    @Test
    fun `parses and formats times`() {
        assertThat(RaceGoal.parseTime("1:45:00")).isEqualTo(6300)
        assertThat(RaceGoal.parseTime(" 45:30 ")).isEqualTo(2730)
        assertThat(RaceGoal.parseTime("1:75:00")).isNull()
        assertThat(RaceGoal.parseTime("abc")).isNull()
        assertThat(RaceGoal.formatTime(6300)).isEqualTo("1:45:00")
        assertThat(RaceGoal.formatTime(1500)).isEqualTo("25:00")
    }

    @Test
    fun `pace and days left`() {
        val goal = RaceGoal("Półmaraton Warszawski", LocalDate.of(2027, 3, 28), RaceGoal.HALF_MARATHON_KM, 6300)
        assertThat(goal.targetPaceSecPerKm!!).isWithin(0.5).of(298.6)
        assertThat(goal.daysLeft(LocalDate.of(2027, 3, 1))).isEqualTo(27)
        assertThat(goal.daysLeft(LocalDate.of(2027, 3, 29))).isEqualTo(-1)
        assertThat(goal.copy(targetSeconds = null).targetPaceSecPerKm).isNull()
    }
}
