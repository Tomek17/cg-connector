package pl.cgconnector.app.domain.workout

/**
 * Gotowe szablony na start w kreatorach. Nazwy ćwiczeń z [ExerciseCatalog] (katalog Garmina),
 * tempo bez celu: wpisuje się je po wyborze szablonu albo zostawia „bez celu”.
 */
object BuiltInTemplates {
    private fun time(
        kind: RunStepKind,
        minutes: Int,
        seconds: Int = 0,
        target: StepTarget? = null,
    ) = RunStep(kind, StepEnd.Time(minutes * 60 + seconds), target)

    private fun km(
        kind: RunStepKind,
        km: Double,
        target: StepTarget? = null,
    ) = RunStep(kind, StepEnd.Distance(km), target)

    private val WARMUP = time(RunStepKind.WARMUP, 15)
    private val COOLDOWN = time(RunStepKind.COOLDOWN, 10)
    private val EASY = StepTarget.HrZone(2)

    val RUNNING: List<RunningWorkout> =
        listOf(
            RunningWorkout(
                "Interwały 6×1 km",
                listOf(WARMUP, RepeatBlock(6, listOf(km(RunStepKind.INTERVAL, 1.0), time(RunStepKind.RECOVERY, 2))), COOLDOWN),
            ),
            RunningWorkout(
                "Interwały 10×400 m",
                listOf(
                    WARMUP,
                    RepeatBlock(10, listOf(km(RunStepKind.INTERVAL, 0.4), time(RunStepKind.RECOVERY, 1, 30))),
                    COOLDOWN,
                ),
            ),
            RunningWorkout(
                "Interwały 3×10 min",
                listOf(WARMUP, RepeatBlock(3, listOf(time(RunStepKind.INTERVAL, 10), time(RunStepKind.RECOVERY, 2))), COOLDOWN),
            ),
            RunningWorkout("Tempo 20 min", listOf(WARMUP, time(RunStepKind.INTERVAL, 20), COOLDOWN)),
            RunningWorkout(
                "Podbiegi 8×60 s",
                listOf(WARMUP, RepeatBlock(8, listOf(time(RunStepKind.INTERVAL, 1), time(RunStepKind.RECOVERY, 2))), COOLDOWN),
            ),
            RunningWorkout(
                "Fartlek 10×1 min",
                listOf(WARMUP, RepeatBlock(10, listOf(time(RunStepKind.INTERVAL, 1), time(RunStepKind.RECOVERY, 1))), COOLDOWN),
            ),
            RunningWorkout(
                "Rozbieganie 45 min",
                listOf(time(RunStepKind.WARMUP, 5), time(RunStepKind.INTERVAL, 35, target = EASY), time(RunStepKind.COOLDOWN, 5)),
            ),
            RunningWorkout("Długie wybieganie 15 km", listOf(km(RunStepKind.INTERVAL, 15.0, EASY))),
        )

    private fun reps(
        name: String,
        sets: Int,
        reps: Int,
        rest: Int,
    ) = StrengthExercise(name, sets, reps = reps, restSeconds = rest)

    private fun hold(
        name: String,
        sets: Int,
        seconds: Int,
        rest: Int,
    ) = StrengthExercise(name, sets, seconds = seconds, restSeconds = rest)

    val STRENGTH: List<StrengthWorkout> =
        listOf(
            StrengthWorkout(
                "Siła – nogi",
                listOf(
                    reps("Barbell Back Squat", 4, 6, 150),
                    reps("Romanian Deadlift", 3, 8, 120),
                    reps("Dumbbell Bulgarian Split Squat", 3, 8, 90),
                    reps("Standing Calf Raise", 3, 15, 60),
                    hold("Plank", 3, 45, 60),
                ),
            ),
            StrengthWorkout(
                "Siła – góra",
                listOf(
                    reps("Barbell Bench Press", 4, 6, 150),
                    reps("Pull-up", 4, 6, 150),
                    reps("Barbell Overhead Press", 3, 8, 120),
                    reps("Barbell Row", 3, 8, 120),
                    reps("Hanging Knee Raise", 3, 12, 60),
                ),
            ),
            StrengthWorkout(
                "Siła biegacza",
                listOf(
                    reps("Dumbbell Bulgarian Split Squat", 3, 10, 75),
                    reps("Single-leg Romanian Deadlift with Dumbbell", 3, 10, 75),
                    reps("Single-leg Hip Raise", 3, 12, 60),
                    reps("Single-leg Standing Calf Raise", 3, 15, 45),
                    hold("Side Plank", 3, 30, 45),
                    reps("Dead Bug", 3, 10, 45),
                ),
            ),
            StrengthWorkout(
                "Wspinacz – ciąganie i core",
                listOf(
                    reps("Pull-up", 5, 5, 180),
                    reps("Inverted Row", 3, 10, 90),
                    reps("Push-up", 3, 15, 60),
                    reps("Face Pull", 3, 15, 60),
                    reps("Hanging Leg Raise", 3, 10, 60),
                    reps("Hollow Rock", 3, 15, 45),
                ),
            ),
            StrengthWorkout(
                "Całe ciało z kettlebell",
                listOf(
                    reps("Kettlebell Swing", 4, 15, 60),
                    reps("Goblet Squat", 3, 10, 75),
                    reps("Push-up", 3, 12, 60),
                    reps("Dumbbell Row", 3, 10, 60),
                    reps("Turkish Get-up", 3, 3, 90),
                    hold("Farmer's Walk", 3, 40, 60),
                ),
            ),
        )
}
