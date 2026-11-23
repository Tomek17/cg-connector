package pl.cgconnector.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// Treningi i kolejka: schematy z docs/api/openapi.json (PR #10, `/api/v1/workouts`, `/api/v1/scheduled`).

/** `POST /api/v1/workouts` (schemat `NewWorkout`). Ten sam [clientId] drugi raz = brak zmian (200). */
@Serializable
data class NewWorkoutDto(
    @SerialName("client_id") val clientId: String,
    /** `RunningDefinition` | `StrengthDefinition`, zbudowane przez `WorkoutJson`. */
    val definition: JsonObject,
)

/** Schemat `WorkoutOut`; aplikacji wystarcza `id`. */
@Serializable
data class WorkoutOutDto(
    val id: Long,
    @SerialName("client_id") val clientId: String = "",
    val name: String = "",
    val sport: String = "",
    @SerialName("created_by") val createdBy: String = "",
)

/** `POST /api/v1/workouts/{id}/schedule`; `date` = `YYYY-MM-DD`. Ten sam trening i dzień = istniejący wpis. */
@Serializable
data class ScheduleRequestDto(
    val date: String,
)

/** Schemat `ScheduledOut`: wpis outboxu serwera. */
@Serializable
data class ScheduledOutDto(
    val id: Long,
    @SerialName("workout_id") val workoutId: Long,
    val name: String,
    val sport: String,
    /** `YYYY-MM-DD`. */
    val date: String,
    /** `pending` | `sent` | `failed` | `deleting` | `deleted`. */
    val status: String,
    @SerialName("last_error") val lastError: String? = null,
    /** Definicja treningu (wersja 1); starszy serwer jej nie wysyła. */
    val definition: JsonObject? = null,
)

/** `GET /api/v1/scheduled` (schemat `ScheduledList`); bez wpisów `deleted`. */
@Serializable
data class ScheduledListDto(
    val items: List<ScheduledOutDto>,
)
