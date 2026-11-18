package pl.cgconnector.app.ui.vo2max

import pl.cgconnector.app.domain.Vo2MaxPoint

/** Punkt wykresu w układzie 0..1 (x od lewej, y od dołu). */
data class ChartPoint(val x: Float, val y: Float)

data class ChartData(
    val points: List<ChartPoint>,
    val minValue: Double,
    val maxValue: Double,
)

/** Czysta geometria wykresu liniowego (bez Compose), testowana na JVM. */
object LineChartMath {
    /** Margines osi Y, żeby linia nie dotykała krawędzi; płaska seria dostaje zakres ±[FLAT_PADDING]. */
    private const val PADDING_RATIO = 0.1
    private const val FLAT_PADDING = 1.0

    /** Oś X proporcjonalna do dat (nierówne odstępy między pomiarami są widoczne). Wejście: od najstarszego. */
    fun normalize(points: List<Vo2MaxPoint>): ChartData? {
        if (points.isEmpty()) return null
        val values = points.map { it.value }
        val rawMin = values.min()
        val rawMax = values.max()
        val pad = if (rawMax == rawMin) FLAT_PADDING else (rawMax - rawMin) * PADDING_RATIO
        val min = rawMin - pad
        val max = rawMax + pad
        val firstDay = points.first().date.toEpochDay()
        val span = (points.last().date.toEpochDay() - firstDay).coerceAtLeast(0)
        val chartPoints =
            points.map { point ->
                val x = if (span == 0L) 0.5f else ((point.date.toEpochDay() - firstDay).toDouble() / span).toFloat()
                val y = ((point.value - min) / (max - min)).toFloat()
                ChartPoint(x, y)
            }
        return ChartData(chartPoints, min, max)
    }
}
