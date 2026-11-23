package pl.cgconnector.app.ui.progress

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import pl.cgconnector.app.domain.SetPoint
import java.time.LocalDate

class ProgressChartMathTest {
    @Test
    fun `axis has round ticks covering every value`() {
        val axis = ProgressChartMath.axis(listOf(60.0, 95.0), fromZero = true)!!

        assertThat(axis.low).isEqualTo(0.0)
        assertThat(axis.step).isEqualTo(20.0)
        assertThat(axis.high).isAtLeast(95.0)
        assertThat(axis.ticks.first()).isEqualTo(0.0)
        assertThat(axis.ticks.last()).isEqualTo(axis.high)
    }

    @Test
    fun `axis without zero hugs the data`() {
        val axis = ProgressChartMath.axis(listOf(4.1, 4.6))!!

        assertThat(axis.low).isAtMost(4.1)
        assertThat(axis.low).isGreaterThan(3.0)
        assertThat(axis.high).isAtLeast(4.6)
        assertThat(axis.fraction(axis.low, invert = true)).isEqualTo(1f)
    }

    @Test
    fun `flat series and integer steps`() {
        val flat = ProgressChartMath.axis(listOf(170.0, 170.0))!!
        assertThat(flat.low).isLessThan(170.0)
        assertThat(flat.high).isGreaterThan(170.0)
        val grades = ProgressChartMath.axis(listOf(3.0, 4.0), minStep = 1.0)!!
        assertThat(grades.step).isEqualTo(1.0)
        assertThat(ProgressChartMath.axis(emptyList())).isNull()
        val pace = ProgressChartMath.axis(listOf(240.0, 360.0), steps = listOf(5.0, 10.0, 15.0, 30.0, 60.0))!!
        assertThat(pace.step).isEqualTo(30.0)
    }

    @Test
    fun `tap picks the nearest session`() {
        assertThat(ProgressChartMath.nearest(0.05f, 4)).isEqualTo(0)
        assertThat(ProgressChartMath.nearest(0.6f, 4)).isEqualTo(2)
        assertThat(ProgressChartMath.nearest(1.0f, 4)).isEqualTo(3)
        assertThat(ProgressChartMath.nearest(0.5f, 0)).isNull()
        assertThat(ProgressChartMath.x(0, 4)).isEqualTo(0.125f)
    }

    @Test
    fun `date labels thin out and keep the last one`() {
        assertThat(ProgressChartMath.labelledIndices(4, 6)).containsExactly(0, 1, 2, 3).inOrder()
        val many = ProgressChartMath.labelledIndices(20, 5)
        assertThat(many.first()).isEqualTo(0)
        assertThat(many.last()).isEqualTo(19)
        assertThat(many.size).isAtMost(6)
    }

    @Test
    fun `top set is the heaviest set of each session`() {
        val day = LocalDate.of(2026, 11, 3)
        val sets =
            listOf(
                SetPoint(1, day, 1, 80.0, 5),
                SetPoint(1, day, 2, 85.0, 3),
                SetPoint(1, day, 3, 85.0, 4),
                SetPoint(2, day.plusDays(7), 1, null, 12),
            )

        val top = ProgressChartMath.topSets(sets)

        assertThat(top.map { it.weightKg to it.reps }).containsExactly(85.0 to 4, null to 12).inOrder()
    }
}
