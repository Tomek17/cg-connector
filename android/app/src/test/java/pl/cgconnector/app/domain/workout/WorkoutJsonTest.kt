package pl.cgconnector.app.domain.workout

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Test

/** Oczekiwany JSON to przykłady z docstringu `server/src/cg_server/workout_definition.py`. */
class WorkoutJsonTest {
    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun running_matchesServerFormat() {
        val workout =
            RunningWorkout(
                name = " 5x1km ",
                items =
                    listOf(
                        RunStep(RunStepKind.WARMUP, StepEnd.Time(600)),
                        RepeatBlock(
                            times = 5,
                            steps =
                                listOf(
                                    RunStep(RunStepKind.INTERVAL, StepEnd.Distance(1.0), StepTarget.Pace(270, 255)),
                                    RunStep(RunStepKind.RECOVERY, StepEnd.Time(90), StepTarget.HrZone(2)),
                                ),
                        ),
                        RunStep(RunStepKind.COOLDOWN, StepEnd.LapButton, StepTarget.HrRange(120, 140)),
                    ),
            )

        assertThat(WorkoutJson.encode(workout)).isEqualTo(
            parse(
                """
                {"version": 1, "sport": "running", "name": "5x1km", "steps": [
                  {"type": "warmup", "seconds": 600},
                  {"type": "repeat", "times": 5, "steps": [
                    {"type": "interval", "distance_km": 1.0, "target": {"type": "pace", "from": "4:30", "to": "4:15"}},
                    {"type": "recovery", "seconds": 90, "target": {"type": "hr_zone", "zone": 2}}]},
                  {"type": "cooldown", "lap_button": true, "target": {"type": "hr_range", "from": 120, "to": 140}}]}
                """,
            ),
        )
    }

    @Test
    fun strength_matchesServerFormat() {
        val workout =
            StrengthWorkout(
                name = "Siła A",
                exercises =
                    listOf(
                        StrengthExercise("Barbell Back Squat", sets = 3, reps = 5, weightKg = 80.0, restSeconds = 120),
                        StrengthExercise("Plank", sets = 3, seconds = 45),
                    ),
            )

        assertThat(WorkoutJson.encode(workout)).isEqualTo(
            parse(
                """
                {"version": 1, "sport": "strength", "name": "Siła A", "exercises": [
                  {"exercise": "Barbell Back Squat", "sets": 3, "reps": 5, "weight_kg": 80.0, "rest_seconds": 120},
                  {"exercise": "Plank", "sets": 3, "seconds": 45, "rest_seconds": 60}]}
                """,
            ),
        )
    }

    @Test
    fun pace_parseAndFormat() {
        assertThat(Pace.parse("4:30")).isEqualTo(270)
        assertThat(Pace.parse(" 12:05 ")).isEqualTo(725)
        assertThat(Pace.parse("4:60")).isNull()
        assertThat(Pace.parse("430")).isNull()
        assertThat(Pace.format(305)).isEqualTo("5:05")
    }
}
