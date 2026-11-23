package pl.cgconnector.app.domain.workout

import kotlin.math.roundToInt

/**
 * Jeden odcinek wykresu treningu biegowego (jak w Garmin Connect): szerokość = czas, wysokość = tempo.
 * Bloki powtórzeń są rozwinięte w kolejne odcinki.
 */
data class RunSegment(
    val kind: RunStepKind,
    /** Czas trwania w sekundach; przy dystansie albo przycisku okrążenia to oszacowanie ([estimated]). */
    val seconds: Int,
    val estimated: Boolean,
    /** Zakres tempa (s/km) od szybszego do wolniejszego; `null` = krok bez celu tempa. */
    val fastSecPerKm: Int?,
    val slowSecPerKm: Int?,
    val step: RunStep,
    /** Numer powtórzenia (od 1) i liczba powtórzeń, gdy odcinek pochodzi z bloku. */
    val repeat: Pair<Int, Int>? = null,
)

object RunProfile {
    /** Tempo przyjmowane do oszacowania czasu kroku na dystans bez celu tempa. */
    const val ASSUMED_PACE_SEC_PER_KM = 360

    /** Czas przyjmowany dla kroku „do przycisku okrążenia”, którego długości nie znamy. */
    const val LAP_BUTTON_SECONDS = 300

    fun segments(workout: RunningWorkout): List<RunSegment> =
        workout.items.flatMap { item ->
            when (item) {
                is RunStep -> listOf(segment(item, null))
                is RepeatBlock ->
                    (1..item.times).flatMap { round -> item.steps.map { segment(it, round to item.times) } }
            }
        }

    /** Łączny czas (z oszacowaniami) w sekundach. */
    fun totalSeconds(segments: List<RunSegment>): Int = segments.sumOf { it.seconds }

    /** Łączny dystans w km, jeśli każdy krok na dystans albo z tempem pozwala go policzyć; inaczej `null`. */
    fun totalKm(segments: List<RunSegment>): Double? =
        segments.sumOf { segment ->
            when (val end = segment.step.end) {
                is StepEnd.Distance -> end.km
                is StepEnd.Time -> {
                    val pace = segment.midPace() ?: return null
                    end.seconds.toDouble() / pace
                }
                StepEnd.LapButton -> return null
            }
        }

    private fun segment(
        step: RunStep,
        repeat: Pair<Int, Int>?,
    ): RunSegment {
        val pace = step.target as? StepTarget.Pace
        val fast = pace?.let { minOf(it.fromSecPerKm, it.toSecPerKm) }
        val slow = pace?.let { maxOf(it.fromSecPerKm, it.toSecPerKm) }
        val (seconds, estimated) =
            when (val end = step.end) {
                is StepEnd.Time -> end.seconds to false
                is StepEnd.Distance -> {
                    val secPerKm = if (fast != null && slow != null) (fast + slow) / 2.0 else ASSUMED_PACE_SEC_PER_KM.toDouble()
                    (end.km * secPerKm).roundToInt() to true
                }
                StepEnd.LapButton -> LAP_BUTTON_SECONDS to true
            }
        return RunSegment(step.kind, seconds.coerceAtLeast(1), estimated, fast, slow, step, repeat)
    }

    private fun RunSegment.midPace(): Double? =
        if (fastSecPerKm != null && slowSecPerKm != null) (fastSecPerKm + slowSecPerKm) / 2.0 else null
}
