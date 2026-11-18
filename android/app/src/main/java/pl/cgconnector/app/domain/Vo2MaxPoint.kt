package pl.cgconnector.app.domain

import java.time.LocalDate

data class Vo2MaxPoint(
    val date: LocalDate,
    val value: Double,
    /** Np. `running`, `cycling`. */
    val sport: String,
)
