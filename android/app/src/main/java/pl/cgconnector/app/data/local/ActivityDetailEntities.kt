package pl.cgconnector.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Embedded
import androidx.room.Entity
import androidx.room.Relation
import pl.cgconnector.app.domain.ActivityDetail
import pl.cgconnector.app.domain.ExerciseSet
import pl.cgconnector.app.domain.HrZone
import pl.cgconnector.app.domain.Lap
import java.time.Instant

/**
 * Znacznik pobrania szczegółów treningu (`GET /activities/{id}`). Osobna tabela, a nie kolumny w
 * `activities`, bo odświeżanie listy robi upsert całego wiersza i skasowałoby ten znacznik.
 */
@Entity(tableName = "activity_details", primaryKeys = ["activity_id"])
data class ActivityDetailsEntity(
    @ColumnInfo(name = "activity_id") val activityId: Long,
    @ColumnInfo(name = "details_available") val detailsAvailable: Boolean,
    @ColumnInfo(name = "fetched_at") val fetchedAt: Instant,
)

@Entity(tableName = "activity_laps", primaryKeys = ["activity_id", "lap_index"])
data class LapEntity(
    @ColumnInfo(name = "activity_id") val activityId: Long,
    @ColumnInfo(name = "lap_index") val index: Int,
    @ColumnInfo(name = "distance_m") val distanceM: Double?,
    @ColumnInfo(name = "duration_sec") val durationSec: Double?,
    @ColumnInfo(name = "avg_hr") val avgHr: Int?,
    @ColumnInfo(name = "max_hr") val maxHr: Int?,
    @ColumnInfo(name = "avg_speed_mps") val avgSpeedMps: Double?,
    @ColumnInfo(name = "pace_min_per_km") val paceMinPerKm: Double?,
    @ColumnInfo(name = "elevation_gain_m") val elevationGainM: Double?,
    @ColumnInfo(name = "avg_power_w") val avgPowerW: Int?,
    @ColumnInfo(name = "avg_cadence_spm") val avgCadenceSpm: Int?,
)

@Entity(tableName = "activity_hr_zones", primaryKeys = ["activity_id", "zone"])
data class HrZoneEntity(
    @ColumnInfo(name = "activity_id") val activityId: Long,
    val zone: Int,
    val seconds: Double,
    @ColumnInfo(name = "low_bpm") val lowBpm: Int?,
)

/** Seria ćwiczenia siłowego; [index] to pozycja na liście z serwera (kolejność wykonania). */
@Entity(tableName = "activity_exercise_sets", primaryKeys = ["activity_id", "set_index"])
data class ExerciseSetEntity(
    @ColumnInfo(name = "activity_id") val activityId: Long,
    @ColumnInfo(name = "set_index") val index: Int,
    @ColumnInfo(name = "set_type") val setType: String?,
    val category: String?,
    val exercise: String?,
    val reps: Int?,
    @ColumnInfo(name = "weight_kg") val weightKg: Double?,
    @ColumnInfo(name = "duration_sec") val durationSec: Double?,
)

/** Wynik zapytania z relacjami: trening i jego szczegóły (puste, jeśli jeszcze nie pobrane). */
data class ActivityWithDetails(
    @Embedded val activity: ActivityEntity,
    @Relation(parentColumn = "id", entityColumn = "activity_id")
    val details: ActivityDetailsEntity?,
    @Relation(parentColumn = "id", entityColumn = "activity_id")
    val laps: List<LapEntity>,
    @Relation(parentColumn = "id", entityColumn = "activity_id")
    val hrZones: List<HrZoneEntity>,
    @Relation(parentColumn = "id", entityColumn = "activity_id")
    val exerciseSets: List<ExerciseSetEntity>,
)

fun ActivityWithDetails.toDomain() =
    ActivityDetail(
        activity = activity.toDomain(),
        detailsFetchedAt = details?.fetchedAt,
        detailsAvailable = details?.detailsAvailable ?: false,
        laps =
            laps.sortedBy { it.index }.map {
                Lap(
                    index = it.index,
                    distanceM = it.distanceM,
                    durationSec = it.durationSec,
                    avgHr = it.avgHr,
                    maxHr = it.maxHr,
                    avgSpeedMps = it.avgSpeedMps,
                    paceMinPerKm = it.paceMinPerKm,
                    elevationGainM = it.elevationGainM,
                )
            },
        hrZones = hrZones.sortedBy { it.zone }.map { HrZone(zone = it.zone, seconds = it.seconds, lowBpm = it.lowBpm) },
        exerciseSets =
            exerciseSets.sortedBy { it.index }.map {
                ExerciseSet(
                    index = it.index,
                    setType = it.setType,
                    category = it.category,
                    exercise = it.exercise,
                    reps = it.reps,
                    weightKg = it.weightKg,
                    durationSec = it.durationSec,
                )
            },
    )
