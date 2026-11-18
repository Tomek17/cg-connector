package pl.cgconnector.app.domain

import java.time.LocalDate

/** Wiersz `GET /api/v1/summary`: suma treningów jednego typu w okresie. */
data class PeriodSummary(
    val period: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val type: ActivityType,
    val count: Int,
    val distanceM: Double?,
    val durationSec: Long,
    val elevationGainM: Double?,
    val avgPaceMinPerKm: Double?,
)
