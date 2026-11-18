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
    ],
    version = 2,
    exportSchema = true,
    // 1→2: nowe tabele i kolumny NULL w activities; Room generuje SQL na podstawie schemas/1.json i 2.json.
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
@TypeConverters(Converters::class)
abstract class CgDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao

    abstract fun activityDetailDao(): ActivityDetailDao

    abstract fun statsDao(): StatsDao

    companion object {
        const val NAME = "cg.db"
    }
}
