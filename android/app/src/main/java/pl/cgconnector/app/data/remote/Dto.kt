package pl.cgconnector.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pl.cgconnector.app.data.local.ActivityEntity
import pl.cgconnector.app.domain.ActivityType
import java.time.OffsetDateTime

@Serializable
data class MeDto(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String,
    @SerialName("watch_model") val watchModel: String? = null,
    /** Ostatnia udana synchronizacja serwera z Garmin Connect, ISO-8601 z offsetem; null = nigdy. */
    @SerialName("last_sync_at") val lastSyncAt: String? = null,
)

@Serializable
data class ActivitiesResponseDto(
    val items: List<ActivityDto>,
)

@Serializable
data class ActivityDto(
    val id: Long,
    /** `running` | `strength` | `climbing` | `other`; nieznane wartości traktowane jak `other`. */
    val type: String,
    val name: String? = null,
    /** Początek aktywności, ISO-8601 z offsetem, np. `2026-11-17T07:30:00+01:00`. */
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_sec") val durationSec: Long,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("avg_hr") val avgHr: Int? = null,
    @SerialName("max_hr") val maxHr: Int? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    @SerialName("avg_power_w") val avgPowerW: Int? = null,
    @SerialName("avg_cadence_spm") val avgCadenceSpm: Int? = null,
)

fun ActivityDto.toEntity() =
    ActivityEntity(
        id = id,
        type = ActivityType.fromApi(type),
        name = name,
        startTime = OffsetDateTime.parse(startTime).toInstant(),
        durationSec = durationSec,
        distanceM = distanceM,
        avgHr = avgHr,
        maxHr = maxHr,
        elevationGainM = elevationGainM,
        avgPowerW = avgPowerW,
        avgCadenceSpm = avgCadenceSpm,
    )

