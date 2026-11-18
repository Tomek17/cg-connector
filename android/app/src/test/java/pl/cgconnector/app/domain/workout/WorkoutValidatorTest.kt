package pl.cgconnector.app.domain.workout

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import pl.cgconnector.app.domain.workout.WorkoutProblem.Code
import java.time.LocalDate

class WorkoutValidatorTest {
    private val easyRun = RunStep(RunStepKind.INTERVAL, StepEnd.Time(1800))

    private fun codes(definition: WorkoutDefinition) = WorkoutValidator.validate(definition).map { it.code }

    @Test
    fun validRunning_hasNoProblems() {
        assertThat(WorkoutValidator.validate(RunningWorkout("Bieg", listOf(easyRun)))).isEmpty()
    }

    @Test
    fun name_mustBePresentAndShort() {
        assertThat(codes(RunningWorkout("  ", listOf(easyRun)))).containsExactly(Code.NAME_EMPTY)
        assertThat(codes(RunningWorkout("x".repeat(61), listOf(easyRun)))).containsExactly(Code.NAME_TOO_LONG)
    }

    @Test
    fun running_stepLimitsLikeServer() {
        val bad =
            RunningWorkout(
                "Bieg",
                listOf(
                    RunStep(RunStepKind.WARMUP, StepEnd.Time(0)),
                    RunStep(RunStepKind.INTERVAL, StepEnd.Distance(100.5), StepTarget.Pace(100, 270)),
                    RepeatBlock(51, listOf(RunStep(RunStepKind.INTERVAL, StepEnd.LapButton, StepTarget.HrZone(6)))),
                    RepeatBlock(2, emptyList()),
                    RunStep(RunStepKind.COOLDOWN, StepEnd.LapButton, StepTarget.HrRange(150, 140)),
                ),
            )

        assertThat(WorkoutValidator.validate(bad))
            .containsExactly(
                WorkoutProblem(Code.DURATION_OUT_OF_RANGE, 1),
                WorkoutProblem(Code.DISTANCE_OUT_OF_RANGE, 2),
                WorkoutProblem(Code.PACE_OUT_OF_RANGE, 2),
                WorkoutProblem(Code.REPEAT_TIMES_OUT_OF_RANGE, 3),
                WorkoutProblem(Code.HR_ZONE_OUT_OF_RANGE, 3),
                WorkoutProblem(Code.REPEAT_EMPTY, 4),
                WorkoutProblem(Code.HR_RANGE_INVALID, 5),
            )
    }

    @Test
    fun running_countsRepeatAndInnerSteps() {
        val repeat = RepeatBlock(10, List(24) { easyRun })
        assertThat(WorkoutValidator.countSteps(listOf(repeat, easyRun))).isEqualTo(26)
        assertThat(codes(RunningWorkout("Bieg", listOf(repeat, repeat, easyRun)))).containsExactly(Code.TOO_MANY_STEPS)
        assertThat(codes(RunningWorkout("Bieg", emptyList()))).containsExactly(Code.NO_STEPS)
    }

    @Test
    fun strength_exerciseLimits() {
        val bad =
            StrengthWorkout(
                "Siła",
                listOf(
                    StrengthExercise("Plank", sets = 0, reps = 5, seconds = 30),
                    StrengthExercise(" ", sets = 3, reps = 101, weightKg = 501.0, restSeconds = 901),
                    StrengthExercise("Plank", sets = 3, seconds = 3601),
                ),
            )

        assertThat(WorkoutValidator.validate(bad))
            .containsExactly(
                WorkoutProblem(Code.SETS_OUT_OF_RANGE, 1),
                WorkoutProblem(Code.REPS_OR_SECONDS, 1),
                WorkoutProblem(Code.EXERCISE_NAME_INVALID, 2),
                WorkoutProblem(Code.REPS_OUT_OF_RANGE, 2),
                WorkoutProblem(Code.WEIGHT_OUT_OF_RANGE, 2),
                WorkoutProblem(Code.REST_OUT_OF_RANGE, 2),
                WorkoutProblem(Code.SECONDS_OUT_OF_RANGE, 3),
            )
        assertThat(codes(StrengthWorkout("Siła", emptyList()))).containsExactly(Code.NO_EXERCISES)
    }

    @Test
    fun date_fromTodayUpTo365DaysAhead() {
        val today = LocalDate.of(2030, 1, 10)
        assertThat(WorkoutValidator.validateDate(today, today)).isNull()
        assertThat(WorkoutValidator.validateDate(today.plusDays(365), today)).isNull()
        assertThat(WorkoutValidator.validateDate(today.minusDays(1), today)?.code).isEqualTo(Code.DATE_IN_PAST)
        assertThat(WorkoutValidator.validateDate(today.plusDays(366), today)?.code).isEqualTo(Code.DATE_TOO_FAR)
    }
}
