package pl.cgconnector.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface WorkoutQueueDao {
    /** Najbliższe daty na górze; w obrębie dnia kolejność dodania. */
    @Query("SELECT * FROM workout_queue ORDER BY date ASC, id ASC")
    fun observeAll(): Flow<List<QueuedWorkoutEntity>>

    @Query("SELECT * FROM workout_queue ORDER BY id ASC")
    suspend fun getAll(): List<QueuedWorkoutEntity>

    @Query("SELECT * FROM workout_queue WHERE id = :id")
    suspend fun getById(id: Long): QueuedWorkoutEntity?

    @Insert
    suspend fun insert(item: QueuedWorkoutEntity): Long

    @Insert
    suspend fun insertAll(items: List<QueuedWorkoutEntity>)

    @Update
    suspend fun update(item: QueuedWorkoutEntity)

    @Query("DELETE FROM workout_queue WHERE id = :id")
    suspend fun deleteById(id: Long)

    /** Zlecenie usunięcia; wykona je worker kolejki. */
    @Query("UPDATE workout_queue SET delete_requested = 1 WHERE id = :id")
    suspend fun markDeleteRequested(id: Long)
}
