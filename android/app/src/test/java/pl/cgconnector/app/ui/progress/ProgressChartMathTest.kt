package pl.cgconnector.app.ui.progress

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProgressChartMathTest {
    @Test
    fun `sessions are evenly spaced and nulls skipped`() {
        val data = ProgressChartMath.sessions(listOf(10.0, null, 20.0, 30.0))!!

        val xs = data.points.map { it.x }
        assertThat(xs[0]).isWithin(1e-6f).of(1f / 6)
        assertThat(xs[1]).isWithin(1e-6f).of(0.5f)
        assertThat(xs[2]).isWithin(1e-6f).of(5f / 6)
        assertThat(data.minValue).isEqualTo(10.0)
        assertThat(data.maxValue).isEqualTo(30.0)
        assertThat(data.points.first().y).isLessThan(data.points.last().y)
    }

    @Test
    fun `inverted axis puts the smallest value on top`() {
        val data = ProgressChartMath.sessions(listOf(5.0, 4.5), invert = true)!!

        assertThat(data.points[1].y).isGreaterThan(data.points[0].y)
        assertThat(data.minY).isGreaterThan(data.maxY)
    }

    @Test
    fun `sets of one session sit side by side around the session`() {
        val data = ProgressChartMath.sets(listOf(listOf(80.0, 80.0, 82.5), listOf(85.0)))!!

        val first = data.points.take(3).map { it.x }
        assertThat(first).isInOrder()
        assertThat(first.distinct()).hasSize(3)
        assertThat(data.points.last().x).isWithin(1e-6f).of(0.75f)
        assertThat(first.last()).isLessThan(0.5f)
        assertThat(data.points.all { it.x in 0f..1f && it.y in 0f..1f }).isTrue()
    }

    @Test
    fun `single value and no values`() {
        val single = ProgressChartMath.sessions(listOf(170.0))!!
        assertThat(single.points.single().x).isEqualTo(0.5f)
        assertThat(single.points.single().y).isEqualTo(0.5f)
        assertThat(ProgressChartMath.sessions(listOf(null, null))).isNull()
        assertThat(ProgressChartMath.sets(emptyList())).isNull()
    }
}
