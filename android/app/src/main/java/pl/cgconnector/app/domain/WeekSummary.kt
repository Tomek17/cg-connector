package pl.cgconnector.app.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/** Suma treningów jednego typu w tygodniu. */
data class TypeTotals(
    val type: ActivityType,
    val count: Int,
    val durationSec: Long,
    /** `null`, gdy żaden trening tego typu nie miał dystansu. */
    val distanceM: Double?,
)

/** Tydzień treningowy (poniedziałek–niedziela) policzony z treningów zapisanych w telefonie. */
data class WeekSummary(
    val weekStart: LocalDate,
    /** Treningi w kolejności wejściowej (lista: od najnowszego). */
    val activities: List<Activity>,
) {
    val count: Int get() = activities.size

    val durationSec: Long get() = activities.sumOf { it.durationSec }

    /** Kilometry biegowe: dla hybrydy liczy się bieg, a nie dystans innych aktywności. */
    val runningDistanceM: Double
        get() = activities.filter { it.type == ActivityType.RUNNING }.sumOf { it.distanceM ?: 0.0 }

    /** Po jednym wierszu na typ, w kolejności [ActivityType]. */
    val byType: List<TypeTotals>
        get() =
            activities
                .groupBy { it.type }
                .toSortedMap()
                .map { (type, list) ->
                    TypeTotals(
                        type = type,
                        count = list.size,
                        durationSec = list.sumOf { it.durationSec },
                        distanceM = list.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum(),
                    )
                }
}

object WeekGrouping {
    /** Poniedziałek tygodnia, w którym zaczął się trening (w strefie użytkownika). */
    fun weekStart(
        instant: Instant,
        zone: ZoneId = AppZone.WARSAW,
    ): LocalDate = weekStart(instant.atZone(zone).toLocalDate())

    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    /** Grupuje treningi po tygodniach, zachowując kolejność wejścia (od najnowszego tygodnia przy liście malejącej). */
    fun group(
        activities: List<Activity>,
        zone: ZoneId = AppZone.WARSAW,
    ): List<WeekSummary> =
        activities
            .groupBy { weekStart(it.startTime, zone) }
            .map { (start, list) -> WeekSummary(start, list) }

    /** Tydzień zawierający [today]; pusty, gdy nie było w nim treningów. */
    fun currentWeek(
        activities: List<Activity>,
        today: LocalDate,
        zone: ZoneId = AppZone.WARSAW,
    ): WeekSummary {
        val start = weekStart(today)
        return WeekSummary(start, activities.filter { weekStart(it.startTime, zone) == start })
    }
}
