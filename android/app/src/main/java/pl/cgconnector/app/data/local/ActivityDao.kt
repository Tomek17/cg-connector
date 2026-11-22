package pl.cgconnector.app.data.local

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import pl.cgconnector.app.domain.ActivityType
import java.time.Instant

@Dao
interface ActivityDao {
    /** Treningi od najnowszego. `type == null` oznacza wszystkie typy. */
    @Query(
        """
        SELECT * FROM activities
        WHERE (:type IS NULL OR type = :type)
        ORDER BY start_time DESC
        """,
    )
    fun observeActivities(type: ActivityType?): Flow<List<ActivityEntity>>

    @Query("SELECT * FROM activities WHERE id = :id")
    suspend fun getById(id: Long): ActivityEntity?

    @Query("UPDATE activities SET tag = :tag WHERE id = :id")
    suspend fun updateTag(
        id: Long,
        tag: String?,
    )

    @Query("SELECT MAX(start_time) FROM activities")
    suspend fun latestStartTime(): Instant?

    @Query("SELECT COUNT(*) FROM activities")
    suspend fun count(): Int

    @Upsert
    suspend fun upsertAll(activities: List<ActivityEntity>)

    @Query("DELETE FROM activities")
    suspend fun deleteAll()
}
