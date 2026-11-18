package pl.cgconnector.app.data.local

import androidx.room.Entity
import pl.cgconnector.app.domain.Vo2MaxPoint
import java.time.LocalDate

/** Jeden pomiar VO2max z Garmina; klucz: dzień + sport (serwer może zwrócić kilka sportów dziennie). */
@Entity(tableName = "vo2max", primaryKeys = ["date", "sport"])
data class Vo2MaxEntity(
    val date: LocalDate,
    val sport: String,
    val value: Double,
)

fun Vo2MaxEntity.toDomain() = Vo2MaxPoint(date = date, value = value, sport = sport)
