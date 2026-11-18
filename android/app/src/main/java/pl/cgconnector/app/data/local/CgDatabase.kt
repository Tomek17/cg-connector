package pl.cgconnector.app.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ActivityEntity::class],
    version = 1,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class CgDatabase : RoomDatabase() {
    abstract fun activityDao(): ActivityDao

    companion object {
        const val NAME = "cg.db"
    }
}
