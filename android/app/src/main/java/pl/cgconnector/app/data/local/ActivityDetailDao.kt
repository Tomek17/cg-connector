package pl.cgconnector.app.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** Komplet danych z `GET /activities/{id}` gotowy do zapisu. */
data class ActivityDetailBundle(
    val activity: ActivityEntity,
    val details: ActivityDetailsEntity,
    val laps: List<LapEntity>,
    val hrZones: List<HrZoneEntity>,
    val exerciseSets: List<ExerciseSetEntity>,
)

@Dao
abstract class ActivityDetailDao {
    /** `null`, gdy treningu nie ma w bazie. */
    @Transaction
    @Query("SELECT * FROM activities WHERE id = :id")
    abstract fun observe(id: Long): Flow<ActivityWithDetails?>

    /** Zastępuje szczegóły treningu w całości (np. serwer poprawił okrążenia). */
    @Transaction
    open suspend fun replace(bundle: ActivityDetailBundle) {
        val id = bundle.activity.id
        upsertActivity(bundle.activity)
        deleteLaps(id)
        deleteHrZones(id)
        deleteExerciseSets(id)
        insertLaps(bundle.laps)
        insertHrZones(bundle.hrZones)
        insertExerciseSets(bundle.exerciseSets)
        upsertDetails(bundle.details)
    }

    @Upsert
    protected abstract suspend fun upsertActivity(activity: ActivityEntity)

    @Upsert
    protected abstract suspend fun upsertDetails(details: ActivityDetailsEntity)

    @Query("DELETE FROM activity_laps WHERE activity_id = :id")
    protected abstract suspend fun deleteLaps(id: Long)

    @Query("DELETE FROM activity_hr_zones WHERE activity_id = :id")
    protected abstract suspend fun deleteHrZones(id: Long)

    @Query("DELETE FROM activity_exercise_sets WHERE activity_id = :id")
    protected abstract suspend fun deleteExerciseSets(id: Long)

    @Insert
    protected abstract suspend fun insertLaps(laps: List<LapEntity>)

    @Insert
    protected abstract suspend fun insertHrZones(zones: List<HrZoneEntity>)

    @Insert
    protected abstract suspend fun insertExerciseSets(sets: List<ExerciseSetEntity>)
}
