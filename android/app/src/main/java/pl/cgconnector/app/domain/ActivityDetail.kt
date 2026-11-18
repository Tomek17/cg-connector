package pl.cgconnector.app.domain

import java.time.Instant

/** Trening ze szczegółami pobranymi na żądanie i zapisanymi w Room. */
data class ActivityDetail(
    val activity: Activity,
    /** `null` = szczegółów jeszcze nie pobrano z serwera. */
    val detailsFetchedAt: Instant?,
    /** false = serwer nie zdołał pobrać szczegółów z Garmina. */
    val detailsAvailable: Boolean,
    val laps: List<Lap>,
    val hrZones: List<HrZone>,
    val exerciseSets: List<ExerciseSet>,
) {
    val hasDetails: Boolean get() = detailsFetchedAt != null
}

data class Lap(
    val index: Int,
    val distanceM: Double?,
    val durationSec: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val avgSpeedMps: Double?,
    val paceMinPerKm: Double?,
    val elevationGainM: Double?,
)

data class HrZone(
    val zone: Int,
    val seconds: Double,
    val lowBpm: Int?,
)

data class ExerciseSet(
    val index: Int,
    /** `ACTIVE` | `REST`. */
    val setType: String?,
    val category: String?,
    val exercise: String?,
    val reps: Int?,
    val weightKg: Double?,
    val durationSec: Double?,
) {
    val isRest: Boolean get() = setType.equals("REST", ignoreCase = true)
}
