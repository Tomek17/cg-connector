package pl.cgconnector.app.ui.plan

import pl.cgconnector.app.domain.workout.RunSegment

/** Słupek wykresu w układzie 0..1: [x0]..[x1] od lewej, [height] od dołu. */
data class RunBar(
    val x0: Float,
    val x1: Float,
    val height: Float,
    /** Krok bez celu tempa: niski, jaśniejszy słupek. */
    val hasPace: Boolean,
)

/** Geometria wykresu bloków treningu biegowego (bez Compose, testowana na JVM). */
object RunChartMath {
    /** Wysokość słupka bez celu tempa i najwolniejszego tempa; najszybsze sięga [MAX_HEIGHT]. */
    const val NO_PACE_HEIGHT = 0.15f
    const val MIN_HEIGHT = 0.3f
    const val MAX_HEIGHT = 1f

    fun bars(segments: List<RunSegment>): List<RunBar> {
        val total = segments.sumOf { it.seconds }.toFloat()
        if (total <= 0f) return emptyList()
        val paces = segments.mapNotNull { it.midPace() }
        val fastest = paces.minOrNull()
        val slowest = paces.maxOrNull()
        var start = 0
        return segments.map { segment ->
            val x0 = start / total
            start += segment.seconds
            val mid = segment.midPace()
            val height =
                when {
                    mid == null || fastest == null || slowest == null -> NO_PACE_HEIGHT
                    slowest == fastest -> (MIN_HEIGHT + MAX_HEIGHT) / 2
                    // Szybsze tempo (mniej s/km) = wyższy słupek.
                    else -> MIN_HEIGHT + (MAX_HEIGHT - MIN_HEIGHT) * ((slowest - mid) / (slowest - fastest)).toFloat()
                }
            RunBar(x0, start / total, height, hasPace = mid != null)
        }
    }

    private fun RunSegment.midPace(): Double? =
        if (fastSecPerKm != null && slowSecPerKm != null) (fastSecPerKm + slowSecPerKm) / 2.0 else null
}
