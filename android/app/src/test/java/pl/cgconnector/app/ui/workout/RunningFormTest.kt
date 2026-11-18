package pl.cgconnector.app.ui.workout

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import pl.cgconnector.app.domain.workout.RepeatBlock
import pl.cgconnector.app.domain.workout.RunStep
import pl.cgconnector.app.domain.workout.RunStepKind
import pl.cgconnector.app.domain.workout.StepEnd
import pl.cgconnector.app.domain.workout.StepTarget
import pl.cgconnector.app.domain.workout.WorkoutProblem
import pl.cgconnector.app.domain.workout.WorkoutValidator

class RunningFormTest {
    private var key = 0L
    private val nextKey = { ++key }

    @Test
    fun template_isValidWorkout() {
        val definition = RunningForm.toDefinition("Interwały", RunningForm.template(nextKey))

        assertThat(WorkoutValidator.validate(definition)).isEmpty()
        assertThat(definition.items).hasSize(3)
        val repeat = definition.items[1] as RepeatBlock
        assertThat(repeat.times).isEqualTo(5)
        assertThat(repeat.steps[0].end).isEqualTo(StepEnd.Distance(1.0))
        assertThat(repeat.steps[1].end).isEqualTo(StepEnd.Time(120))
    }

    @Test
    fun fieldsAreParsedIncludingPolishDecimalComma() {
        val step =
            StepForm(
                key = 1,
                kind = RunStepKind.INTERVAL,
                endType = EndType.DISTANCE,
                distanceKm = "1,5",
                targetType = TargetType.PACE,
                paceFrom = "4:30",
                paceTo = "4:15",
            )
        val definition = RunningForm.toDefinition("Bieg", listOf(BlockForm(2, listOf(step))))

        assertThat(definition.items.single())
            .isEqualTo(RunStep(RunStepKind.INTERVAL, StepEnd.Distance(1.5), StepTarget.Pace(270, 255)))
        assertThat(RunningForm.parseDuration("45")).isEqualTo(2700)
        assertThat(RunningForm.parseDuration("1:30")).isEqualTo(90)
        assertThat(RunningForm.parseDuration("1:3")).isNull()
    }

    @Test
    fun invalidTextBecomesValidatorProblemWithStepNumber() {
        val blocks =
            listOf(
                BlockForm(1, listOf(StepForm(2, RunStepKind.WARMUP, duration = "10:00"))),
                BlockForm(3, listOf(StepForm(4, RunStepKind.INTERVAL, duration = "abc", targetType = TargetType.HR_RANGE))),
            )

        val problems = WorkoutValidator.validate(RunningForm.toDefinition("Bieg", blocks))

        assertThat(problems)
            .containsExactly(
                WorkoutProblem(WorkoutProblem.Code.DURATION_OUT_OF_RANGE, 2),
                WorkoutProblem(WorkoutProblem.Code.HR_RANGE_INVALID, 2),
            )
    }
}
