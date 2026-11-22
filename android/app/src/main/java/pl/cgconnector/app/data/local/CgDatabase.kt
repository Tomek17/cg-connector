package pl.cgconnector.app.data.local

import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        ActivityEntity::class,
        ActivityDetailsEntity::class,
        LapEntity::class,
        HrZoneEntity::class,
        ExerciseSetEntity::class,
        Vo2MaxEntity::class,
        SummaryEntity::class,
        SyncStatusEntity::class,
        QueuedWorkoutEntity::class,
    ],
    version = 4,
    exportSchema = true,
    // 1→2: nowe tabele i kolumny NULL w activities; Room generuje SQL na podstawie schemas/1.json i 2.json.
    // 2→3: nowa tabela workout_queue (kolejka treningów).
    // 3→4: kolumna activities.tag (NULL); istniejące wiersze dostaną tagi przy następnym odświeżeniu.
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
    ],
)
@TypeConverters(Converters::class)
abstract class CgDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao

    abstract fun activityDetailDao(): ActivityDetailDao

    abstract fun statsDao(): StatsDao

    abstract fun workoutQueueDao(): WorkoutQueueDao

    companion object {
        const val NAME = "cg.db"
    }
}
