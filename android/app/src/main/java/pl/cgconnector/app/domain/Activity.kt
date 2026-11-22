package pl.cgconnector.app.domain

import java.time.Instant

/** Podsumowanie treningu widoczne w aplikacji. */
data class Activity(
    val id: Long,
    val type: ActivityType,
    val name: String?,
    val startTime: Instant,
    val durationSec: Long,
    val distanceM: Double?,
    val avgHr: Int?,
    val maxHr: Int?,
    val elevationGainM: Double?,
    val avgPowerW: Int?,
    val avgCadenceSpm: Int?,
    val movingDurationSec: Long? = null,
    val avgSpeedMps: Double? = null,
    /** Minuty dziesiętnie (5.5 = 5:30 min/km). */
    val paceMinPerKm: Double? = null,
    val aerobicTrainingEffect: Double? = null,
    val anaerobicTrainingEffect: Double? = null,
    val trainingLoad: Double? = null,
    val tag: ActivityTag? = null,
)
