package pl.cgconnector.app.domain.workout

import java.time.LocalDate

/** Problem w definicji; [position] to numer kroku/ćwiczenia od 1 (dla kroków w powtórzeniu: numer bloku). */
data class WorkoutProblem(
    val code: Code,
    val position: Int? = null,
) {
    enum class Code {
        NAME_EMPTY,
        NAME_TOO_LONG,
        NO_STEPS,
        TOO_MANY_STEPS,
        DURATION_OUT_OF_RANGE,
        DISTANCE_OUT_OF_RANGE,
        PACE_OUT_OF_RANGE,
        HR_ZONE_OUT_OF_RANGE,
        HR_RANGE_INVALID,
        REPEAT_TIMES_OUT_OF_RANGE,
        REPEAT_EMPTY,
        NO_EXERCISES,
        TOO_MANY_EXERCISES,
        EXERCISE_NAME_INVALID,
        SETS_OUT_OF_RANGE,
        REPS_OR_SECONDS,
        REPS_OUT_OF_RANGE,
        SECONDS_OUT_OF_RANGE,
        WEIGHT_OUT_OF_RANGE,
        REST_OUT_OF_RANGE,
        DATE_IN_PAST,
        DATE_TOO_FAR,
    }
}

/**
 * Te same limity co walidacja serwera (`workout_definition.py`, `workouts.py`), żeby błąd było widać
 * w kreatorze, a nie dopiero po wysyłce. Jedyne, czego aplikacja nie sprawdza, to obecność nazwy
 * ćwiczenia w katalogu Garmina: to robi serwer (błąd 422 trafia do kolejki jako „błąd”).
 */
object WorkoutValidator {
    const val MAX_NAME_LENGTH = 60
    const val MAX_STEPS = 50
    const val MAX_EXERCISES = 30
    const val MAX_DAYS_AHEAD = 365L
    private const val MAX_STEP_SECONDS = 6 * 3600
    private const val MAX_DISTANCE_KM = 100.0
    private val PACE_RANGE = 2 * 60..20 * 60
    private val HR_ZONES = 1..5
    private val BPM_RANGE = 30..250
    private val REPEAT_TIMES = 1..50
    private val SETS = 1..20
    private val REPS = 1..100
    private val EXERCISE_SECONDS = 1..3600
    private const val MAX_WEIGHT_KG = 500.0
    private val REST_SECONDS = 0..900
    private const val MAX_EXERCISE_NAME = 100

    fun validate(definition: WorkoutDefinition): List<WorkoutProblem> {
        val problems = mutableListOf<WorkoutProblem>()
        val name = definition.name.trim()
        if (name.isEmpty()) problems += WorkoutProblem(WorkoutProblem.Code.NAME_EMPTY)
        if (name.length > MAX_NAME_LENGTH) problems += WorkoutProblem(WorkoutProblem.Code.NAME_TOO_LONG)
        when (definition) {
            is RunningWorkout -> validateRunning(definition, problems)
            is StrengthWorkout -> validateStrength(definition, problems)
        }
        return problems
    }

    /** Serwer przyjmuje daty od dziś do [MAX_DAYS_AHEAD] dni naprzód. */
    fun validateDate(
        date: LocalDate,
        today: LocalDate,
    ): WorkoutProblem? =
        when {
            date.isBefore(today) -> WorkoutProblem(WorkoutProblem.Code.DATE_IN_PAST)
            date.isAfter(today.plusDays(MAX_DAYS_AHEAD)) -> WorkoutProblem(WorkoutProblem.Code.DATE_TOO_FAR)
            else -> null
        }

    /** Kroki wykonywalne i bloki powtórzeń razem, jak `count_steps` na serwerze. */
    fun countSteps(items: List<RunItem>): Int = items.sumOf { if (it is RepeatBlock) 1 + it.steps.size else 1 }

    private fun validateRunning(
        workout: RunningWorkout,
        problems: MutableList<WorkoutProblem>,
    ) {
        if (workout.items.isEmpty()) problems += WorkoutProblem(WorkoutProblem.Code.NO_STEPS)
        if (countSteps(workout.items) > MAX_STEPS) problems += WorkoutProblem(WorkoutProblem.Code.TOO_MANY_STEPS)
        workout.items.forEachIndexed { index, item ->
            val position = index + 1
            when (item) {
                is RunStep -> validateStep(item, position, problems)
                is RepeatBlock -> {
                    if (item.times !in REPEAT_TIMES) {
                        problems += WorkoutProblem(WorkoutProblem.Code.REPEAT_TIMES_OUT_OF_RANGE, position)
                    }
                    if (item.steps.isEmpty()) problems += WorkoutProblem(WorkoutProblem.Code.REPEAT_EMPTY, position)
                    item.steps.forEach { validateStep(it, position, problems) }
                }
            }
        }
    }

    private fun validateStep(
        step: RunStep,
        position: Int,
        problems: MutableList<WorkoutProblem>,
    ) {
        val endProblem =
            when (val end = step.end) {
                is StepEnd.Time -> WorkoutProblem.Code.DURATION_OUT_OF_RANGE.takeIf { end.seconds !in 1..MAX_STEP_SECONDS }
                is StepEnd.Distance ->
                    WorkoutProblem.Code.DISTANCE_OUT_OF_RANGE.takeIf { !(end.km > 0.0 && end.km <= MAX_DISTANCE_KM) }
                StepEnd.LapButton -> null
            }
        val targetProblem =
            when (val target = step.target) {
                null -> null
                is StepTarget.Pace ->
                    WorkoutProblem.Code.PACE_OUT_OF_RANGE.takeIf {
                        target.fromSecPerKm !in PACE_RANGE || target.toSecPerKm !in PACE_RANGE
                    }
                is StepTarget.HrZone -> WorkoutProblem.Code.HR_ZONE_OUT_OF_RANGE.takeIf { target.zone !in HR_ZONES }
                is StepTarget.HrRange ->
                    WorkoutProblem.Code.HR_RANGE_INVALID.takeIf {
                        target.fromBpm !in BPM_RANGE || target.toBpm !in BPM_RANGE || target.fromBpm >= target.toBpm
                    }
            }
        listOfNotNull(endProblem, targetProblem).forEach { problems += WorkoutProblem(it, position) }
    }

    private fun validateStrength(
        workout: StrengthWorkout,
        problems: MutableList<WorkoutProblem>,
    ) {
        if (workout.exercises.isEmpty()) problems += WorkoutProblem(WorkoutProblem.Code.NO_EXERCISES)
        if (workout.exercises.size > MAX_EXERCISES) problems += WorkoutProblem(WorkoutProblem.Code.TOO_MANY_EXERCISES)
        workout.exercises.forEachIndexed { index, exercise ->
            val codes = mutableListOf<WorkoutProblem.Code>()
            if (exercise.name.isBlank() || exercise.name.length > MAX_EXERCISE_NAME) {
                codes += WorkoutProblem.Code.EXERCISE_NAME_INVALID
            }
            if (exercise.sets !in SETS) codes += WorkoutProblem.Code.SETS_OUT_OF_RANGE
            if ((exercise.reps == null) == (exercise.seconds == null)) codes += WorkoutProblem.Code.REPS_OR_SECONDS
            if (exercise.reps != null && exercise.reps !in REPS) codes += WorkoutProblem.Code.REPS_OUT_OF_RANGE
            if (exercise.seconds != null && exercise.seconds !in EXERCISE_SECONDS) {
                codes += WorkoutProblem.Code.SECONDS_OUT_OF_RANGE
            }
            if (exercise.weightKg != null && !(exercise.weightKg >= 0.0 && exercise.weightKg <= MAX_WEIGHT_KG)) {
                codes += WorkoutProblem.Code.WEIGHT_OUT_OF_RANGE
            }
            if (exercise.restSeconds !in REST_SECONDS) codes += WorkoutProblem.Code.REST_OUT_OF_RANGE
            codes.forEach { problems += WorkoutProblem(it, index + 1) }
        }
    }
}
