package pl.cgconnector.app.domain.workout

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class RunProfileTest {
    private val workout =
        RunningWorkout(
            "6×800",
            listOf(
                RunStep(RunStepKind.WARMUP, StepEnd.Time(600)),
                RepeatBlock(
                    3,
                    listOf(
                        RunStep(RunStepKind.INTERVAL, StepEnd.Distance(0.8), StepTarget.Pace(230, 240)),
                        RunStep(RunStepKind.RECOVERY, StepEnd.Time(120), StepTarget.Pace(360, 380)),
                    ),
                ),
                RunStep(RunStepKind.COOLDOWN, StepEnd.LapButton),
            ),
        )

    @Test
    fun `repeats are expanded and distance steps get time from pace`() {
        val segments = RunProfile.segments(workout)

        assertThat(segments).hasSize(1 + 3 * 2 + 1)
        val interval = segments[1]
        assertThat(interval.seconds).isEqualTo(188) // 0.8 km × 235 s/km
        assertThat(interval.estimated).isTrue()
        assertThat(interval.fastSecPerKm).isEqualTo(230)
        assertThat(interval.repeat).isEqualTo(1 to 3)
        assertThat(segments.last().seconds).isEqualTo(RunProfile.LAP_BUTTON_SECONDS)
        assertThat(segments.first().estimated).isFalse()
    }

    @Test
    fun `total km needs a pace or distance for every step`() {
        assertThat(RunProfile.totalKm(RunProfile.segments(workout))).isNull()

        val paced =
            RunningWorkout(
                "Tempo",
                listOf(
                    RunStep(RunStepKind.WARMUP, StepEnd.Distance(2.0)),
                    RunStep(RunStepKind.INTERVAL, StepEnd.Time(1200), StepTarget.Pace(270, 270)),
                ),
            )
        assertThat(RunProfile.totalKm(RunProfile.segments(paced))).isWithin(1e-6).of(2.0 + 1200.0 / 270)
    }
}
