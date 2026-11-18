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
)
