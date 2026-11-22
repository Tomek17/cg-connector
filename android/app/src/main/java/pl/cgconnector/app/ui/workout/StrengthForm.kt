package pl.cgconnector.app.ui.workout

import pl.cgconnector.app.domain.workout.ExerciseCatalog
import pl.cgconnector.app.domain.workout.StrengthExercise
import pl.cgconnector.app.domain.workout.StrengthWorkout
import pl.cgconnector.app.ui.format.Formatters

/** Ćwiczenie w edycji; [name] to nazwa z katalogu Garmina, [byTime] = na czas (s) zamiast powtórzeń. */
data class ExerciseForm(
    val key: Long,
    val name: String = "",
    val sets: String = "3",
    val byTime: Boolean = false,
    val reps: String = "10",
    val seconds: String = "30",
    val weightKg: String = "",
    val restSeconds: String = StrengthExercise.DEFAULT_REST_SECONDS.toString(),
)

object StrengthForm {
    /** Nowe ćwiczenie wybrane z katalogu: wartości startowe zależne od tego, czy to ćwiczenie „na czas”. */
    fun newExercise(
        key: Long,
        garminName: String,
    ): ExerciseForm {
        val timed = TIMED.any { garminName.contains(it, ignoreCase = true) }
        return ExerciseForm(key = key, name = garminName, byTime = timed)
    }

    /** Ćwiczenia zwykle robione na czas (deski, zwisy, noszenie). */
    private val TIMED = listOf("Plank", "L Sit", "Farmer", "Wall Squat", "Hollow Hold")

    /** Definicja (szablon albo kopia z Planu) → formularz do dalszej edycji. */
    fun fromDefinition(
        workout: StrengthWorkout,
        nextKey: () -> Long,
    ): List<ExerciseForm> =
        workout.exercises.map { exercise ->
            ExerciseForm(
                key = nextKey(),
                name = exercise.name,
                sets = exercise.sets.toString(),
                byTime = exercise.seconds != null,
                reps = (exercise.reps ?: 10).toString(),
                seconds = (exercise.seconds ?: 30).toString(),
                weightKg = exercise.weightKg?.let { Formatters.weight(it) } ?: "",
                restSeconds = exercise.restSeconds.toString(),
            )
        }

    /** Nazwa do pokazania: polska z katalogu, a dla nazw spoza listy oryginalna z katalogu Garmina. */
    fun displayName(garminName: String): String = ExerciseCatalog.polishName(garminName) ?: garminName

    /** Jak w [RunningForm]: zły tekst = -1 (poza każdym zakresem), błąd zgłasza walidator z numerem ćwiczenia. */
    fun toDefinition(
        name: String,
        exercises: List<ExerciseForm>,
    ): StrengthWorkout =
        StrengthWorkout(
            name = name,
            exercises =
                exercises.map { form ->
                    StrengthExercise(
                        name = form.name.trim(),
                        sets = form.sets.toIntOrInvalid(),
                        reps = if (form.byTime) null else form.reps.toIntOrInvalid(),
                        seconds = if (form.byTime) form.seconds.toIntOrInvalid() else null,
                        weightKg = form.weightKg.takeIf { it.isNotBlank() }?.let { RunningForm.parseDecimal(it) ?: -1.0 },
                        restSeconds = form.restSeconds.toIntOrInvalid(),
                    )
                },
        )

    private fun String.toIntOrInvalid(): Int = trim().toIntOrNull() ?: -1
}
