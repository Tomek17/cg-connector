package pl.cgconnector.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

@Dao
interface WorkoutQueueDao {
    /** Najbliższe daty na górze; w obrębie dnia kolejność dodania. Usunięte nie są już pokazywane. */
    @Query("SELECT * FROM workout_queue WHERE NOT (status = 'deleted' AND delete_requested = 0) ORDER BY date ASC, id ASC")
    fun observeAll(): Flow<List<QueuedWorkoutEntity>>

    @Query("SELECT * FROM workout_queue ORDER BY id ASC")
    suspend fun getAll(): List<QueuedWorkoutEntity>

    @Query("SELECT * FROM workout_queue WHERE id = :id")
    suspend fun getById(id: Long): QueuedWorkoutEntity?

    @Insert
    suspend fun insert(item: QueuedWorkoutEntity): Long

    @Query("SELECT * FROM workout_queue WHERE remote_scheduled_id = :remoteScheduledId")
    suspend fun getByRemoteScheduledId(remoteScheduledId: Long): QueuedWorkoutEntity?

    // Aktualizacje punktowe, nie @Update całego wiersza: użytkownik może w tym czasie zlecić usunięcie
    // (delete_requested), a worker nie może tego nadpisać starą kopią wiersza.

    @Query("UPDATE workout_queue SET remote_workout_id = :remoteWorkoutId WHERE id = :id")
    suspend fun setRemoteWorkoutId(
        id: Long,
        remoteWorkoutId: Long,
    )

    @Query(
        "UPDATE workout_queue SET remote_scheduled_id = :remoteScheduledId, status = :status, " +
            "last_error = :lastError WHERE id = :id",
    )
    suspend fun setScheduled(
        id: Long,
        remoteScheduledId: Long,
        status: String,
        lastError: String?,
    )

    @Query("UPDATE workout_queue SET status = :status, last_error = :lastError WHERE id = :id")
    suspend fun setStatus(
        id: Long,
        status: String,
        lastError: String?,
    )

    /** Koniec obsługi zlecenia usunięcia (wykonane albo odrzucone). */
    @Query("UPDATE workout_queue SET delete_requested = 0, status = :status, last_error = :lastError WHERE id = :id")
    suspend fun finishDelete(
        id: Long,
        status: String,
        lastError: String?,
    )

    /** Stan z `GET /scheduled`; wiersze z niewykonanym zleceniem usunięcia zostają bez zmian. */
    @Query(
        "UPDATE workout_queue SET name = :name, date = :date, status = :status, last_error = :lastError " +
            "WHERE remote_scheduled_id = :remoteScheduledId AND delete_requested = 0",
    )
    suspend fun applyServerState(
        remoteScheduledId: Long,
        name: String,
        date: LocalDate,
        status: String,
        lastError: String?,
    )

    /** Definicja z serwera dla wpisów utworzonych poza aplikacją (Claude); własnej definicji nie nadpisuje. */
    @Query(
        "UPDATE workout_queue SET definition_json = :definitionJson " +
            "WHERE remote_scheduled_id = :remoteScheduledId AND definition_json IS NULL",
    )
    suspend fun fillDefinition(
        remoteScheduledId: Long,
        definitionJson: String,
    )

    /** „Ponów”: wpis wraca do wysyłki; ponowny POST /schedule przywraca wpis serwera z `failed` do `pending`. */
    @Query(
        "UPDATE workout_queue SET status = 'pending', last_error = NULL, remote_scheduled_id = NULL " +
            "WHERE id = :id AND status = 'failed' AND delete_requested = 0",
    )
    suspend fun resetFailed(id: Long)

    /** Stare wpisy (data przed [before]) w stanie końcowym nie są już potrzebne. */
    @Query(
        "DELETE FROM workout_queue WHERE date < :before AND status IN ('sent', 'failed', 'deleted') " +
            "AND delete_requested = 0",
    )
    suspend fun pruneBefore(before: LocalDate)

    @Query("DELETE FROM workout_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Zlecenie usunięcia; wykona je worker kolejki. */
    @Query("UPDATE workout_queue SET delete_requested = 1 WHERE id = :id")
    suspend fun markDeleteRequested(id: Long)
}
