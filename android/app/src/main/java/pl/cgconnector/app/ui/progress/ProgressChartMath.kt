package pl.cgconnector.app.ui.progress

import pl.cgconnector.app.ui.vo2max.ChartData
import pl.cgconnector.app.ui.vo2max.ChartPoint
import java.time.LocalDate

/** Geometria wykresów zakładki Progress (bez Compose, testowana na JVM). Układ 0..1 jak w [ChartData]. */
object ProgressChartMath {
    private const val PADDING_RATIO = 0.1

    /** Rozrzut serii jednej sesji w poziomie: ułamek odstępu między sesjami. */
    private const val SESSION_SPREAD = 0.5

    /**
     * Jedna wartość na sesję, sesje w równych odstępach (kolejne treningi, nie kalendarz).
     * Wartości `null` są pomijane. [invert] = mniejsza wartość wyżej (tempo: szybciej = wyżej).
     */
    fun sessions(
        values: List<Double?>,
        invert: Boolean = false,
        flatPadding: Double = 1.0,
    ): ChartData? = sets(values.map { listOf(it) }, invert, flatPadding)

    /**
     * Kilka wartości (serii) na sesję: sesje w równych odstępach, serie jednej sesji rozłożone obok siebie,
     * żeby kropki o tej samej wartości nie zlewały się w jedną. Sesje bez żadnej wartości zostają pominięte.
     */
    fun sets(
        sessions: List<List<Double?>>,
        invert: Boolean = false,
        flatPadding: Double = 1.0,
    ): ChartData? {
        val kept = sessions.map { it.filterNotNull() }.filter { it.isNotEmpty() }
        if (kept.isEmpty()) return null
        val all = kept.flatten()
        val rawMin = all.min()
        val rawMax = all.max()
        val pad = if (rawMax == rawMin) flatPadding else (rawMax - rawMin) * PADDING_RATIO
        val min = rawMin - pad
        val max = rawMax + pad
        fun y(value: Double): Float {
            val fraction = ((value - min) / (max - min)).toFloat()
            return if (invert) 1f - fraction else fraction
        }
        // Każda sesja ma równy przedział osi X, punkt (albo grupa serii) w jego środku.
        val slot = 1.0 / kept.size
        val points =
            kept.flatMapIndexed { index, values ->
                val center = (index + 0.5) * slot
                val width = slot * SESSION_SPREAD
                values.mapIndexed { i, value ->
                    val offset = if (values.size == 1) 0.0 else width * (i.toDouble() / (values.size - 1) - 0.5)
                    ChartPoint((center + offset).toFloat(), y(value))
                }
            }
        return ChartData(points = points, minValue = rawMin, maxValue = rawMax, minY = y(rawMin), maxY = y(rawMax))
    }

    /** Sesje (dni) z seriami w kolejności z serwera; ta sama sesja = to samo `activityId`. */
    fun <T> groupBySession(
        items: List<T>,
        sessionKey: (T) -> Long,
    ): List<List<T>> = items.groupBy(sessionKey).values.toList()

    /** Pierwszy i ostatni dzień do podpisu osi X. */
    fun dateRange(days: List<LocalDate>): Pair<LocalDate, LocalDate>? = if (days.isEmpty()) null else days.min() to days.max()
}
