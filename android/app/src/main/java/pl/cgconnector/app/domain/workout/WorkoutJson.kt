package pl.cgconnector.app.domain.workout

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
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
}
