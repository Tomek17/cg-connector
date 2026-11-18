package pl.cgconnector.app.domain.workout

import java.util.Locale

/**
 * Definicja treningu tworzona w kreatorze. Odpowiada „definicji w wersji 1” serwera
 * (`server/src/cg_server/workout_definition.py`, schematy `RunningDefinition` i `StrengthDefinition`
 * w `docs/api/openapi.json`). Do JSON zamienia ją [WorkoutJson], sprawdza [WorkoutValidator].
 */
sealed interface WorkoutDefinition {
    val name: String
    val sport: WorkoutSport
}

enum class WorkoutSport(
    val apiValue: String,
) {
    RUNNING("running"),
    STRENGTH("strength"),
    ;

    companion object {
        fun fromApi(value: String): WorkoutSport? = entries.firstOrNull { it.apiValue == value }
    }
}

data class RunningWorkout(
    override val name: String,
    val items: List<RunItem>,
) : WorkoutDefinition {
    override val sport = WorkoutSport.RUNNING
}

data class StrengthWorkout(
    override val name: String,
    val exercises: List<StrengthExercise>,
) : WorkoutDefinition {
    override val sport = WorkoutSport.STRENGTH
}

/** Element treningu biegowego: pojedynczy krok albo blok powtórzeń (bez zagnieżdżania). */
sealed interface RunItem

enum class RunStepKind(
    val apiValue: String,
) {
    WARMUP("warmup"),
    INTERVAL("interval"),
    RECOVERY("recovery"),
    REST("rest"),
    COOLDOWN("cooldown"),
}

data class RunStep(
    val kind: RunStepKind,
    val end: StepEnd,
    val target: StepTarget? = null,
) : RunItem

data class RepeatBlock(
    val times: Int,
    val steps: List<RunStep>,
) : RunItem

/** Kiedy kończy się krok: dokładnie jeden warunek, jak na serwerze. */
sealed interface StepEnd {
    data class Time(
        val seconds: Int,
    ) : StepEnd

    data class Distance(
        val km: Double,
    ) : StepEnd

    /** Do naciśnięcia przycisku okrążenia na zegarku. */
    data object LapButton : StepEnd
}

sealed interface StepTarget {
    /** Zakres tempa w sekundach na km; kolejność końców nie ma znaczenia. */
    data class Pace(
        val fromSecPerKm: Int,
        val toSecPerKm: Int,
    ) : StepTarget

    /** Strefa tętna ustawiona na zegarku (1–5). */
    data class HrZone(
        val zone: Int,
    ) : StepTarget

    data class HrRange(
        val fromBpm: Int,
        val toBpm: Int,
    ) : StepTarget
}

/**
 * Ćwiczenie siłowe. [name] musi być nazwą z katalogu ćwiczeń Garmina (serwer odrzuca nieznane
 * i podpowiada podobne). Dokładnie jedno z [reps] i [seconds].
 */
data class StrengthExercise(
    val name: String,
    val sets: Int,
    val reps: Int? = null,
    val seconds: Int? = null,
    val weightKg: Double? = null,
    val restSeconds: Int = DEFAULT_REST_SECONDS,
) {
    companion object {
        const val DEFAULT_REST_SECONDS = 60
    }
}

/** Tempo w formacie serwera `m:ss` (min/km). */
object Pace {
    private val PATTERN = Regex("""(\d{1,2}):([0-5]\d)""")

    /** `"4:30"` → 270; `null`, gdy format jest zły. Zakresu nie sprawdza (robi to [WorkoutValidator]). */
    fun parse(text: String): Int? {
        val match = PATTERN.matchEntire(text.trim()) ?: return null
        return match.groupValues[1].toInt() * 60 + match.groupValues[2].toInt()
    }

    fun format(secondsPerKm: Int): String = String.format(Locale.ROOT, "%d:%02d", secondsPerKm / 60, secondsPerKm % 60)
}
