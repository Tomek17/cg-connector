package pl.cgconnector.app.ui.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import pl.cgconnector.app.domain.Lap
import java.time.Instant
import java.time.ZoneId

class FormattersTest {
    @Test
    fun `duration below and above one hour`() {
        assertThat(Formatters.duration(0L)).isEqualTo("0:00")
        assertThat(Formatters.duration(65L)).isEqualTo("1:05")
        assertThat(Formatters.duration(2_707L)).isEqualTo("45:07")
        assertThat(Formatters.duration(3_600L)).isEqualTo("1:00:00")
        assertThat(Formatters.duration(3_723L)).isEqualTo("1:02:03")
        assertThat(Formatters.duration(-5L)).isEqualTo("0:00")
        assertThat(Formatters.duration(329.6)).isEqualTo("5:30")
    }

    @Test
    fun `pace from decimal minutes`() {
        assertThat(Formatters.pace(5.5)).isEqualTo("5:30")
        assertThat(Formatters.pace(4.25)).isEqualTo("4:15")
        assertThat(Formatters.pace(5.999)).isEqualTo("6:00")
        assertThat(Formatters.pace(5.62)).isEqualTo("5:37")
    }

    @Test
    fun `invalid pace is hidden`() {
        assertThat(Formatters.pace(null)).isNull()
        assertThat(Formatters.pace(0.0)).isNull()
        assertThat(Formatters.pace(-1.0)).isNull()
        assertThat(Formatters.pace(Double.POSITIVE_INFINITY)).isNull()
        assertThat(Formatters.pace(Double.NaN)).isNull()
        assertThat(Formatters.pace(75.0)).isNull()
    }

    @Test
    fun `pace from speed and from distance`() {
        // 1000 m / (10/3 m/s) = 300 s = 5:00 min/km
        assertThat(Formatters.pace(Formatters.paceFromSpeed(10.0 / 3))).isEqualTo("5:00")
        assertThat(Formatters.paceFromSpeed(0.0)).isNull()
        assertThat(Formatters.paceFromSpeed(null)).isNull()
        assertThat(Formatters.pace(Formatters.paceFromDistance(1_000.0, 330.0))).isEqualTo("5:30")
        assertThat(Formatters.paceFromDistance(0.0, 330.0)).isNull()
        assertThat(Formatters.paceFromDistance(1_000.0, null)).isNull()
    }

    @Test
    fun `lap pace prefers server value then speed then distance`() {
        val lap = Lap(1, distanceM = 1_000.0, durationSec = 360.0, avgHr = null, maxHr = null, avgSpeedMps = null, paceMinPerKm = null, elevationGainM = null)

        assertThat(Formatters.pace(Formatters.lapPace(lap))).isEqualTo("6:00")
        assertThat(Formatters.pace(Formatters.lapPace(lap.copy(avgSpeedMps = 4.0)))).isEqualTo("4:10")
        assertThat(Formatters.pace(Formatters.lapPace(lap.copy(avgSpeedMps = 4.0, paceMinPerKm = 5.0)))).isEqualTo("5:00")
    }

    @Test
    fun `km, weight and exercise names`() {
        assertThat(Formatters.km(8_012.5)).isEqualTo("8,01")
        assertThat(Formatters.weight(60.0)).isEqualTo("60")
        assertThat(Formatters.weight(22.5)).isEqualTo("22,5")
        assertThat(Formatters.exerciseName("BARBELL_BENCH_PRESS")).isEqualTo("Barbell bench press")
    }

    @Test
    fun `date time in Polish`() {
        val text = Formatters.dateTime(Instant.parse("2026-11-18T13:03:00Z"), ZoneId.of("Europe/Warsaw"))

        assertThat(text).isEqualTo("18 lis 2026, 14:03")
    }
}
