package pl.cgconnector.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pl.cgconnector.app.data.local.ActivityDetailBundle
import pl.cgconnector.app.data.local.ActivityDetailsEntity
import pl.cgconnector.app.data.local.ActivityEntity
import pl.cgconnector.app.data.local.ExerciseSetEntity
import pl.cgconnector.app.data.local.HrZoneEntity
import pl.cgconnector.app.data.local.LapEntity
import pl.cgconnector.app.data.local.SummaryEntity
import pl.cgconnector.app.data.local.SyncStatusEntity
import pl.cgconnector.app.data.local.Vo2MaxEntity
import pl.cgconnector.app.domain.ActivityType
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

// DTO zgodne z docs/api/openapi.json (serwer cg-server, REST /api/v1).
// Pola, które serwer zawsze wysyła, ale aplikacja ich nie potrzebuje do działania, mają wartości
// domyślne, żeby drobna zmiana po stronie serwera nie psuła parsowania.

/** `GET /api/v1/me` (schemat `Me`). */
@Serializable
data class MeDto(
    val name: String,
    @SerialName("display_name") val displayName: String,
    /** `ok` | `needs_login` | `rate_limited` | null. */
    @SerialName("garmin_status") val garminStatus: String? = null,
    /** Ostatnia **udana** synchronizacja serwera z Garmin Connect (ISO-8601 z offsetem); null = nigdy. */
    @SerialName("last_sync_at") val lastSyncAt: String? = null,
    @SerialName("last_sync_attempt_at") val lastSyncAttemptAt: String? = null,
    @SerialName("last_sync_status") val lastSyncStatus: String? = null,
    /** `YYYY-MM-DD`. */
    @SerialName("latest_activity_date") val latestActivityDate: String? = null,
    /** Może zapraszać nowe osoby z aplikacji (`POST /api/v1/invites`). */
    @SerialName("is_admin") val isAdmin: Boolean = false,
)

/** `GET /api/v1/activities` (schemat `ActivityList`). */
@Serializable
data class ActivityListDto(
    val items: List<ActivityDto>,
    /** Więcej aktywności niż `limit` w przedziale: pobierz resztę z `to` = dzień najstarszej otrzymanej. */
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** Podsumowanie aktywności (schemat `ActivitySummary`). */
@Serializable
data class ActivityDto(
    /** ID aktywności w Garmin Connect, stabilny klucz upsertu. */
    val id: Long,
    /** `running` | `strength` | `climbing` | `other`; nieznane wartości traktowane jak `other`. */
    val type: String,
    /** Surowy typ z Garmina, np. `trail_running`. */
    @SerialName("type_key") val typeKey: String? = null,
    val name: String? = null,
    /** ISO-8601 z offsetem Europe/Warsaw, np. `2026-11-17T07:30:00+01:00`. */
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_sec") val durationSec: Long,
    @SerialName("moving_duration_sec") val movingDurationSec: Long? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("avg_hr") val avgHr: Int? = null,
    @SerialName("max_hr") val maxHr: Int? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    @SerialName("avg_power_w") val avgPowerW: Int? = null,
    @SerialName("avg_cadence_spm") val avgCadenceSpm: Int? = null,
    @SerialName("avg_speed_mps") val avgSpeedMps: Double? = null,
    /** Minuty dziesiętnie (5.5 = 5:30 min/km), tylko bieg. */
    @SerialName("pace_min_per_km") val paceMinPerKm: Double? = null,
    @SerialName("aerobic_training_effect") val aerobicTrainingEffect: Double? = null,
    @SerialName("anaerobic_training_effect") val anaerobicTrainingEffect: Double? = null,
    @SerialName("training_load") val trainingLoad: Double? = null,
    /** Klucz tagu (`intervals`, `legs`…) albo null; ustawia go Claude albo aplikacja. */
    val tag: String? = null,
)

/** `GET /api/v1/activities/{id}` (schemat `ActivityDetail`): pola podsumowania + szczegóły. */
@Serializable
data class ActivityDetailDto(
    val id: Long,
    val type: String,
    @SerialName("type_key") val typeKey: String? = null,
    val name: String? = null,
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_sec") val durationSec: Long,
    @SerialName("moving_duration_sec") val movingDurationSec: Long? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("avg_hr") val avgHr: Int? = null,
    @SerialName("max_hr") val maxHr: Int? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    @SerialName("avg_power_w") val avgPowerW: Int? = null,
    @SerialName("avg_cadence_spm") val avgCadenceSpm: Int? = null,
    @SerialName("avg_speed_mps") val avgSpeedMps: Double? = null,
    @SerialName("pace_min_per_km") val paceMinPerKm: Double? = null,
    @SerialName("aerobic_training_effect") val aerobicTrainingEffect: Double? = null,
    @SerialName("anaerobic_training_effect") val anaerobicTrainingEffect: Double? = null,
    @SerialName("training_load") val trainingLoad: Double? = null,
    val tag: String? = null,
    /** false = szczegółów nie udało się pobrać z Garmina; listy są wtedy puste. */
    @SerialName("details_available") val detailsAvailable: Boolean = false,
    val laps: List<LapDto> = emptyList(),
    @SerialName("hr_zones") val hrZones: List<HrZoneDto> = emptyList(),
    @SerialName("exercise_sets") val exerciseSets: List<ExerciseSetDto> = emptyList(),
)

@Serializable
data class LapDto(
    val index: Int,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("duration_sec") val durationSec: Double? = null,
    @SerialName("avg_hr") val avgHr: Int? = null,
    @SerialName("max_hr") val maxHr: Int? = null,
    @SerialName("avg_speed_mps") val avgSpeedMps: Double? = null,
    @SerialName("pace_min_per_km") val paceMinPerKm: Double? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    @SerialName("avg_power_w") val avgPowerW: Int? = null,
    @SerialName("avg_cadence_spm") val avgCadenceSpm: Int? = null,
)

@Serializable
data class HrZoneDto(
    val zone: Int,
    val seconds: Double,
    @SerialName("low_bpm") val lowBpm: Int? = null,
)

@Serializable
data class ExerciseSetDto(
    /** `ACTIVE` | `REST`. */
    @SerialName("set_type") val setType: String? = null,
    val category: String? = null,
    val exercise: String? = null,
    val reps: Int? = null,
    @SerialName("weight_kg") val weightKg: Double? = null,
    @SerialName("duration_sec") val durationSec: Double? = null,
)

/** `GET /api/v1/vo2max` (schemat `Vo2MaxList`). */
@Serializable
data class Vo2MaxListDto(
    val items: List<Vo2MaxPointDto>,
)

@Serializable
data class Vo2MaxPointDto(
    /** `YYYY-MM-DD`. */
    val date: String,
    val value: Double,
    /** Np. `running`, `cycling`. */
    val sport: String,
)

/** `GET /api/v1/summary` (schemat `Summary`). */
@Serializable
data class SummaryDto(
    /** `week` | `month`. */
    val period: String,
    val from: String,
    val to: String,
    val items: List<SummaryItemDto>,
)

@Serializable
data class SummaryItemDto(
    @SerialName("period_start") val periodStart: String,
    @SerialName("period_end") val periodEnd: String,
    val type: String,
    val count: Int,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("duration_sec") val durationSec: Long,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    @SerialName("avg_pace_min_per_km") val avgPaceMinPerKm: Double? = null,
)

// Mapowanie DTO → encje Room.

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
        movingDurationSec = movingDurationSec,
        avgSpeedMps = avgSpeedMps,
        paceMinPerKm = paceMinPerKm,
        aerobicTrainingEffect = aerobicTrainingEffect,
        anaerobicTrainingEffect = anaerobicTrainingEffect,
        trainingLoad = trainingLoad,
        tag = tag,
    )

/** Szczegóły zawierają też pola podsumowania, więc odświeżają przy okazji wiersz w `activities`. */
fun ActivityDetailDto.toBundle(fetchedAt: Instant) =
    ActivityDetailBundle(
        activity =
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
                movingDurationSec = movingDurationSec,
                avgSpeedMps = avgSpeedMps,
                paceMinPerKm = paceMinPerKm,
                aerobicTrainingEffect = aerobicTrainingEffect,
                anaerobicTrainingEffect = anaerobicTrainingEffect,
                trainingLoad = trainingLoad,
                tag = tag,
            ),
        details = ActivityDetailsEntity(activityId = id, detailsAvailable = detailsAvailable, fetchedAt = fetchedAt),
        // distinctBy: klucz główny tabel to (activity_id, numer); duplikat z serwera nie może wywrócić zapisu.
        laps =
            laps.distinctBy { it.index }.map {
                LapEntity(
                    activityId = id,
                    index = it.index,
                    distanceM = it.distanceM,
                    durationSec = it.durationSec,
                    avgHr = it.avgHr,
                    maxHr = it.maxHr,
                    avgSpeedMps = it.avgSpeedMps,
                    paceMinPerKm = it.paceMinPerKm,
                    elevationGainM = it.elevationGainM,
                    avgPowerW = it.avgPowerW,
                    avgCadenceSpm = it.avgCadenceSpm,
                )
            },
        hrZones =
            hrZones.distinctBy { it.zone }.map {
                HrZoneEntity(activityId = id, zone = it.zone, seconds = it.seconds, lowBpm = it.lowBpm)
            },
        exerciseSets =
            exerciseSets.mapIndexed { index, set ->
                ExerciseSetEntity(
                    activityId = id,
                    index = index,
                    setType = set.setType,
                    category = set.category,
                    exercise = set.exercise,
                    reps = set.reps,
                    weightKg = set.weightKg,
                    durationSec = set.durationSec,
                )
            },
    )

fun Vo2MaxPointDto.toEntity() = Vo2MaxEntity(date = LocalDate.parse(date), sport = sport, value = value)

fun SummaryDto.toEntities(): List<SummaryEntity> =
    items.distinctBy { it.periodStart to it.type }.map {
        SummaryEntity(
            period = period,
            periodStart = LocalDate.parse(it.periodStart),
            periodEnd = LocalDate.parse(it.periodEnd),
            type = it.type,
            count = it.count,
            distanceM = it.distanceM,
            durationSec = it.durationSec,
            elevationGainM = it.elevationGainM,
            avgPaceMinPerKm = it.avgPaceMinPerKm,
        )
    }

/** Dane serwera z `/me` nakładane na lokalny stan synchronizacji (pola aplikacji zostają). */
fun MeDto.applyTo(status: SyncStatusEntity) =
    status.copy(
        serverLastSyncAt = lastSyncAt?.let { OffsetDateTime.parse(it).toInstant() },
        serverLastSyncStatus = lastSyncStatus,
        garminStatus = garminStatus,
    )
