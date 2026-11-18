package pl.cgconnector.app.ui.workout

import pl.cgconnector.app.domain.workout.Pace
import pl.cgconnector.app.domain.workout.RepeatBlock
import pl.cgconnector.app.domain.workout.RunItem
import pl.cgconnector.app.domain.workout.RunStep
import pl.cgconnector.app.domain.workout.RunStepKind
import pl.cgconnector.app.domain.workout.RunningWorkout
import pl.cgconnector.app.domain.workout.StepEnd
import pl.cgconnector.app.domain.workout.StepTarget

enum class EndType { TIME, DISTANCE, LAP }

enum class TargetType { NONE, PACE, HR_ZONE, HR_RANGE }

/** Krok w edycji: pola tekstowe tak, jak wpisał je użytkownik; zamiana na model w [toDefinition]. */
data class StepForm(
    val key: Long,
    val kind: RunStepKind,
    val endType: EndType = EndType.TIME,
    /** `mm:ss` albo same minuty. */
    val duration: String = "",
    val distanceKm: String = "",
    val targetType: TargetType = TargetType.NONE,
    val paceFrom: String = "",
    val paceTo: String = "",
    val hrZone: Int = 2,
    val hrFrom: String = "",
    val hrTo: String = "",
)

/** Pojedynczy krok ([repeatTimes] == null) albo blok powtórzeń. */
data class BlockForm(
    val key: Long,
    val steps: List<StepForm>,
    val repeatTimes: String? = null,
)

object RunningForm {
    private val DURATION = Regex("""(\d{1,3})(?::([0-5]\d))?""")

    /** Szablon na start: rozgrzewka, 5× (1 km + 2 min truchtu), schłodzenie. */
    fun template(nextKey: () -> Long): List<BlockForm> =
        listOf(
            BlockForm(nextKey(), listOf(StepForm(nextKey(), RunStepKind.WARMUP, duration = "10:00"))),
            interval(nextKey),
            BlockForm(nextKey(), listOf(StepForm(nextKey(), RunStepKind.COOLDOWN, duration = "10:00"))),
        )

    fun interval(nextKey: () -> Long): BlockForm =
        BlockForm(
            key = nextKey(),
            repeatTimes = "5",
            steps =
                listOf(
                    StepForm(nextKey(), RunStepKind.INTERVAL, endType = EndType.DISTANCE, distanceKm = "1"),
                    StepForm(nextKey(), RunStepKind.RECOVERY, duration = "2:00"),
                ),
        )

    /**
     * Niepoprawny tekst daje wartość spoza zakresu (0), więc błąd zgłasza `WorkoutValidator`
     * z numerem kroku, a nie osobna ścieżka walidacji.
     */
    fun toDefinition(
        name: String,
        blocks: List<BlockForm>,
    ): RunningWorkout =
        RunningWorkout(
            name = name,
            items =
                blocks.map<BlockForm, RunItem> { block ->
                    val times = block.repeatTimes
                    if (times == null) {
                        toStep(block.steps.first())
                    } else {
                        RepeatBlock(times.trim().toIntOrNull() ?: 0, block.steps.map(::toStep))
                    }
                },
        )

    fun parseDuration(text: String): Int? {
        val match = DURATION.matchEntire(text.trim()) ?: return null
        return match.groupValues[1].toInt() * 60 + (match.groupValues[2].toIntOrNull() ?: 0)
    }

    fun parseDecimal(text: String): Double? = text.trim().replace(',', '.').toDoubleOrNull()

    private fun toStep(form: StepForm): RunStep =
        RunStep(
            kind = form.kind,
            end =
                when (form.endType) {
                    EndType.TIME -> StepEnd.Time(parseDuration(form.duration) ?: 0)
                    EndType.DISTANCE -> StepEnd.Distance(parseDecimal(form.distanceKm) ?: 0.0)
                    EndType.LAP -> StepEnd.LapButton
                },
            target =
                when (form.targetType) {
                    TargetType.NONE -> null
                    TargetType.PACE -> StepTarget.Pace(Pace.parse(form.paceFrom) ?: 0, Pace.parse(form.paceTo) ?: 0)
                    TargetType.HR_ZONE -> StepTarget.HrZone(form.hrZone)
                    TargetType.HR_RANGE ->
                        StepTarget.HrRange(form.hrFrom.trim().toIntOrNull() ?: 0, form.hrTo.trim().toIntOrNull() ?: 0)
                },
        )
}
