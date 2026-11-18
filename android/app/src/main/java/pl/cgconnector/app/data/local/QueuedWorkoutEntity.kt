package pl.cgconnector.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import pl.cgconnector.app.domain.workout.QueueStatus
import pl.cgconnector.app.domain.workout.QueuedWorkout
import pl.cgconnector.app.domain.workout.WorkoutSport
import java.time.Instant
import java.time.LocalDate

/**
 * Kolejka treningów (outbox aplikacji). Szkic z kreatora trafia tu **przed** wysyłką, z wygenerowanym
 * [clientId] (UUID); ponowienie `POST /workouts` z tym samym `client_id` nie tworzy duplikatu na serwerze.
 * Wpisy utworzone poza aplikacją (np. przez Claude/MCP) przychodzą z `GET /scheduled` bez [clientId]
 * i [definitionJson].
 */
@Entity(
    tableName = "workout_queue",
    indices = [
        Index(value = ["client_id"], unique = true),
        Index(value = ["remote_scheduled_id"], unique = true),
        Index(value = ["date"]),
    ],
)
data class QueuedWorkoutEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "client_id") val clientId: String?,
    val name: String,
    /** `running` | `strength` (wartość API). */
    val sport: String,
    /** Definicja w formacie serwera (wersja 1), wysyłana bez zmian. */
    @ColumnInfo(name = "definition_json") val definitionJson: String?,
    val date: LocalDate,
    /** [QueueStatus.apiValue]. */
    val status: String,
    @ColumnInfo(name = "delete_requested") val deleteRequested: Boolean = false,
    @ColumnInfo(name = "remote_workout_id") val remoteWorkoutId: Long? = null,
    @ColumnInfo(name = "remote_scheduled_id") val remoteScheduledId: Long? = null,
    /** Komunikat serwera (4xx przy wysyłce albo `last_error` outboxu). */
    @ColumnInfo(name = "last_error") val lastError: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Instant,
)

fun QueuedWorkoutEntity.toDomain() =
    QueuedWorkout(
        id = id,
        name = name,
        sport = WorkoutSport.fromApi(sport),
        date = date,
        status = QueueStatus.fromApi(status),
        lastError = lastError,
        createdInApp = clientId != null,
        deleteRequested = deleteRequested,
    )
