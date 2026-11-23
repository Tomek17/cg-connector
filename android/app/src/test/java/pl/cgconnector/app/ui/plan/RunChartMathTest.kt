package pl.cgconnector.app.ui.plan

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import pl.cgconnector.app.domain.workout.RunProfile
import pl.cgconnector.app.domain.workout.RunStep
import pl.cgconnector.app.domain.workout.RunStepKind
import pl.cgconnector.app.domain.workout.RunningWorkout
import pl.cgconnector.app.domain.workout.StepEnd
import pl.cgconnector.app.domain.workout.StepTarget

class RunChartMathTest {
    @Test
    fun `bar width follows time and faster pace is taller`() {
        val workout =
            RunningWorkout(
                "Test",
                listOf(
                    RunStep(RunStepKind.WARMUP, StepEnd.Time(600)),
                    RunStep(RunStepKind.INTERVAL, StepEnd.Time(300), StepTarget.Pace(240, 240)),
                    RunStep(RunStepKind.RECOVERY, StepEnd.Time(300), StepTarget.Pace(360, 360)),
                ),
            )

        val bars = RunChartMath.bars(RunProfile.segments(workout))

        assertThat(bars.map { it.x1 - it.x0 }).containsExactly(0.5f, 0.25f, 0.25f).inOrder()
        assertThat(bars[0].hasPace).isFalse()
        assertThat(bars[0].height).isEqualTo(RunChartMath.NO_PACE_HEIGHT)
        assertThat(bars[1].height).isWithin(1e-6f).of(RunChartMath.MAX_HEIGHT)
        assertThat(bars[2].height).isEqualTo(RunChartMath.MIN_HEIGHT)
        assertThat(bars.last().x1).isEqualTo(1f)
    }

    @Test
    fun `empty workout has no bars`() {
        assertThat(RunChartMath.bars(emptyList())).isEmpty()
    }
}
