package pl.cgconnector.app.data.local

import androidx.room.TypeConverter
import pl.cgconnector.app.domain.ActivityType
import java.time.Instant

class Converters {
    @TypeConverter
    fun instantToEpochMillis(value: Instant): Long = value.toEpochMilli()

    @TypeConverter
    fun epochMillisToInstant(value: Long): Instant = Instant.ofEpochMilli(value)

    @TypeConverter
    fun activityTypeToString(value: ActivityType): String = value.apiValue

    @TypeConverter
    fun stringToActivityType(value: String): ActivityType = ActivityType.fromApi(value)
}
