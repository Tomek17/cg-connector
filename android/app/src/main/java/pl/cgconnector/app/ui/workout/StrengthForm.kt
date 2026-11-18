package pl.cgconnector.app.ui.workout

import pl.cgconnector.app.domain.workout.StrengthExercise
import pl.cgconnector.app.domain.workout.StrengthWorkout

/** Ćwiczenie w edycji; [byTime] = na czas (s) zamiast powtórzeń. */
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
    /**
     * Częste ćwiczenia z katalogu Garmina (`garminconnect.exercises`, sprawdzone 2026-11-18). Nazwa musi być
     * dokładnie z katalogu; inną serwer odrzuci z podpowiedzią, a wpis dostanie status „błąd”.
     */
    val SUGGESTIONS =
        listOf(
            "Barbell Back Squat",
            "Barbell Front Squat",
            "Goblet Squat",
            "Barbell Deadlift",
            "Romanian Deadlift",
            "Barbell Hip Thrust with Bench",
            "Hip Raise",
            "Barbell Bench Press",
            "Dumbbell Bench Press",
            "Barbell Shoulder Press",
            "Dumbbell Shoulder Press",
            "Barbell Row",
            "Dumbbell Row",
            "Pull-up",
            "Chin-up",
            "Push-up",
            "Lat Pull-down",
            "Face Pull",
            "Lunge",
            "Dumbbell Lunge",
            "Dumbbell Bulgarian Split Squat",
            "Dumbbell Biceps Curl",
            "Triceps Extension",
            "Bench Dip",
            "Calf Raise",
            "Kettlebell Swing",
            "Plank",
            "Side Plank",
            "Crunch",
            "Russian Twist",
            "Mountain Climber",
            "Burpee",
        )
    private const val MAX_SUGGESTIONS = 5

    /** Podpowiedzi dla wpisanego tekstu; pusto, gdy nazwa już pasuje dokładnie. */
    fun suggestionsFor(text: String): List<String> {
        val query = text.trim()
        if (query.isEmpty() || SUGGESTIONS.any { it.equals(query, ignoreCase = true) }) return emptyList()
        return SUGGESTIONS.filter { it.contains(query, ignoreCase = true) }.take(MAX_SUGGESTIONS)
    }

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
