package pl.cgconnector.app.domain.workout

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Definicja → JSON w formacie serwera (wersja 1). Budowany ręcznie, bo dyskryminatory są różne
 * (`sport` dla treningu, `type` dla kroku i celu), a krok biegowy ma `type` = rodzaj kroku.
 * Ten JSON zapisujemy w Room przed wysyłką i wysyłamy bez zmian (`POST /api/v1/workouts`).
 */
object WorkoutJson {
    const val VERSION = 1

    fun encode(definition: WorkoutDefinition): JsonObject =
        buildJsonObject {
            put("version", VERSION)
            put("sport", definition.sport.apiValue)
            put("name", definition.name.trim())
            when (definition) {
                is RunningWorkout -> put("steps", JsonArray(definition.items.map(::item)))
                is StrengthWorkout -> put("exercises", JsonArray(definition.exercises.map(::exercise)))
                is FreeformWorkout -> {
                    definition.description.trim().takeIf { it.isNotEmpty() }?.let { put("description", it) }
                    definition.minutes?.let { put("minutes", it) }
                }
            }
        }

    private fun item(item: RunItem): JsonObject =
        when (item) {
            is RunStep -> step(item)
            is RepeatBlock ->
                buildJsonObject {
                    put("type", "repeat")
                    put("times", item.times)
                    put("steps", buildJsonArray { item.steps.forEach { add(step(it)) } })
                }
        }

    private fun step(step: RunStep): JsonObject =
        buildJsonObject {
            put("type", step.kind.apiValue)
            when (val end = step.end) {
                is StepEnd.Time -> put("seconds", end.seconds)
                is StepEnd.Distance -> put("distance_km", end.km)
                StepEnd.LapButton -> put("lap_button", true)
            }
            step.target?.let { put("target", target(it)) }
        }

    private fun target(target: StepTarget): JsonObject =
        buildJsonObject {
            when (target) {
                is StepTarget.Pace -> {
                    put("type", "pace")
                    put("from", Pace.format(target.fromSecPerKm))
                    put("to", Pace.format(target.toSecPerKm))
                }
                is StepTarget.HrZone -> {
                    put("type", "hr_zone")
                    put("zone", target.zone)
                }
                is StepTarget.HrRange -> {
                    put("type", "hr_range")
                    put("from", target.fromBpm)
                    put("to", target.toBpm)
                }
            }
        }

    private fun exercise(exercise: StrengthExercise): JsonObject =
        buildJsonObject {
            put("exercise", exercise.name.trim())
            put("sets", exercise.sets)
            exercise.reps?.let { put("reps", it) }
            exercise.seconds?.let { put("seconds", it) }
            exercise.weightKg?.let { put("weight_kg", it) }
            put("rest_seconds", exercise.restSeconds)
        }

    /**
     * JSON w formacie serwera → definicja (do kopiowania treningu z Planu i szablonów). `null`, gdy JSON
     * jest niepełny albo z nowszej wersji; nie waliduje zakresów (robi to [WorkoutValidator] przy zapisie).
     */
    fun decode(json: JsonObject): WorkoutDefinition? =
        runCatching {
            val name = json.string("name") ?: return null
            when (val sport = json.string("sport")?.let(WorkoutSport::fromApi)) {
                WorkoutSport.RUNNING -> RunningWorkout(name, json.array("steps").map { decodeItem(it.jsonObject) })
                WorkoutSport.STRENGTH -> StrengthWorkout(name, json.array("exercises").map { decodeExercise(it.jsonObject) })
                WorkoutSport.OTHER, WorkoutSport.BOULDERING ->
                    FreeformWorkout(name, sport, json.string("description").orEmpty(), json.int("minutes"))
                null -> null
            }
        }.getOrNull()

    private fun decodeItem(json: JsonObject): RunItem =
        if (json.string("type") == "repeat") {
            RepeatBlock(json.int("times") ?: 1, json.array("steps").map { decodeStep(it.jsonObject) })
        } else {
            decodeStep(json)
        }

    private fun decodeStep(json: JsonObject): RunStep {
        val kind = RunStepKind.entries.first { it.apiValue == json.string("type") }
        val end =
            when {
                json.int("seconds") != null -> StepEnd.Time(json.int("seconds")!!)
                json.double("distance_km") != null -> StepEnd.Distance(json.double("distance_km")!!)
                else -> StepEnd.LapButton
            }
        return RunStep(kind, end, json["target"]?.jsonObject?.let(::decodeTarget))
    }

    private fun decodeTarget(json: JsonObject): StepTarget? =
        when (json.string("type")) {
            "pace" -> {
                val from = json.string("from")?.let(Pace::parse)
                val to = json.string("to")?.let(Pace::parse)
                if (from != null && to != null) StepTarget.Pace(from, to) else null
            }
            "hr_zone" -> json.int("zone")?.let { StepTarget.HrZone(it) }
            "hr_range" -> {
                val from = json.int("from")
                val to = json.int("to")
                if (from != null && to != null) StepTarget.HrRange(from, to) else null
            }
            else -> null
        }

    private fun decodeExercise(json: JsonObject): StrengthExercise =
        StrengthExercise(
            name = requireNotNull(json.string("exercise")),
            sets = json.int("sets") ?: 1,
            reps = json.int("reps"),
            seconds = json.int("seconds"),
            weightKg = json.double("weight_kg"),
            restSeconds = json.int("rest_seconds") ?: StrengthExercise.DEFAULT_REST_SECONDS,
        )

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.array(key: String): JsonArray = this[key]?.jsonArray ?: JsonArray(emptyList())
}
