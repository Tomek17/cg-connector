package pl.cgconnector.app.data.local

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.ActivityType
import java.time.Instant

/**
 * Lokalna kopia podsumowania treningu. Telefon trzyma dane jednego użytkownika
 * (tego, do którego należy token), więc nie ma tu `user_id`.
 */
@Entity(
    tableName = "activities",
    indices = [Index("start_time"), Index("type")],
)
data class ActivityEntity(
    /** Identyfikator aktywności nadany przez serwer. */
    @PrimaryKey val id: Long,
    val type: ActivityType,
    val name: String?,
    @ColumnInfo(name = "start_time") val startTime: Instant,
    @ColumnInfo(name = "duration_sec") val durationSec: Long,
    @ColumnInfo(name = "distance_m") val distanceM: Double?,
    @ColumnInfo(name = "avg_hr") val avgHr: Int?,
    @ColumnInfo(name = "max_hr") val maxHr: Int?,
    @ColumnInfo(name = "elevation_gain_m") val elevationGainM: Double?,
    @ColumnInfo(name = "avg_power_w") val avgPowerW: Int?,
    @ColumnInfo(name = "avg_cadence_spm") val avgCadenceSpm: Int?,
    // Od wersji 2 bazy (migracja automatyczna 1→2, kolumny dopuszczają NULL).
    @ColumnInfo(name = "moving_duration_sec") val movingDurationSec: Long? = null,
    @ColumnInfo(name = "avg_speed_mps") val avgSpeedMps: Double? = null,
    @ColumnInfo(name = "pace_min_per_km") val paceMinPerKm: Double? = null,
    @ColumnInfo(name = "aerobic_training_effect") val aerobicTrainingEffect: Double? = null,
    @ColumnInfo(name = "anaerobic_training_effect") val anaerobicTrainingEffect: Double? = null,
    @ColumnInfo(name = "training_load") val trainingLoad: Double? = null,
    // Od wersji 4 bazy: klucz tagu z serwera (`intervals`, `legs`…), null = bez tagu.
    val tag: String? = null,
)

fun ActivityEntity.toDomain() =
    Activity(
        id = id,
        type = type,
        name = name,
        startTime = startTime,
        durationSec = durationSec,
        distanceM = distanceM,
        avgHr = avgHr,
        maxHr = maxHr,
        elevationGainM = elevationGainM,
        avgPowerW = avgPowerW,
        avgCadenceSpm = avgCadenceSpm,
        movingDurationSec = movingDurationSec,
        avgSpeedMps = avgSpeedMps,
        paceMinPerKm = paceMinPerKm,
        aerobicTrainingEffect = aerobicTrainingEffect,
        anaerobicTrainingEffect = anaerobicTrainingEffect,
        trainingLoad = trainingLoad,
        tag = ActivityTag.fromKey(tag),
    )
