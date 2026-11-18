package pl.cgconnector.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.PeriodSummary
import java.time.LocalDate

/**
 * Wiersz `GET /api/v1/summary`. [type] to surowa wartość z API (a nie [ActivityType]), żeby nowe typy
 * z serwera nie zlały się w kluczu z `other`.
 */
@Entity(tableName = "summaries", primaryKeys = ["period", "period_start", "type"])
data class SummaryEntity(
    val period: String,
    @ColumnInfo(name = "period_start") val periodStart: LocalDate,
    @ColumnInfo(name = "period_end") val periodEnd: LocalDate,
    val type: String,
    val count: Int,
    @ColumnInfo(name = "distance_m") val distanceM: Double?,
    @ColumnInfo(name = "duration_sec") val durationSec: Long,
    @ColumnInfo(name = "elevation_gain_m") val elevationGainM: Double?,
    @ColumnInfo(name = "avg_pace_min_per_km") val avgPaceMinPerKm: Double?,
)

fun SummaryEntity.toDomain() =
    PeriodSummary(
        period = period,
        periodStart = periodStart,
        periodEnd = periodEnd,
        type = ActivityType.fromApi(type),
        count = count,
        distanceM = distanceM,
        durationSec = durationSec,
        elevationGainM = elevationGainM,
        avgPaceMinPerKm = avgPaceMinPerKm,
    )
