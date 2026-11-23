package pl.cgconnector.app.ui.progress

import pl.cgconnector.app.domain.SetPoint
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** Oś Y wykresu: od [low] do [high] co [step] (okrągłe wartości do podziałki). */
data class ChartAxis(val low: Double, val high: Double, val step: Double) {
    val ticks: List<Double>
        get() {
            val count = ((high - low) / step).toInt()
            return (0..count).map { low + it * step }
        }

    /** Położenie wartości na osi: 0 = dół, 1 = góra; [invert] = mniejsza wartość wyżej (tempo). */
    fun fraction(
        value: Double,
        invert: Boolean = false,
    ): Float {
        val f = ((value - low) / (high - low)).toFloat()
        return if (invert) 1f - f else f
    }
}

/** Najcięższa seria jednego treningu: jeden punkt na wykresie siłowym. */
data class TopSet(val activityId: Long, val date: LocalDate, val weightKg: Double?, val reps: Int?)

/** Geometria wykresów zakładki Progress (bez Compose, testowana na JVM). */
object ProgressChartMath {
    private const val TARGET_TICKS = 5

    /**
     * Okrągła podziałka obejmująca wszystkie wartości. [fromZero] = oś od zera (ciężar, powtórzenia, próby);
     * bez tego oś obejmuje tylko zakres danych z zapasem (tempo, tętno).
     * [minStep] = najmniejszy krok (1 dla liczb całkowitych), [steps] = dozwolone kroki zamiast 1/2/2,5/5.
     */
    fun axis(
        values: List<Double>,
        fromZero: Boolean = false,
        minStep: Double = 0.0,
        steps: List<Double>? = null,
    ): ChartAxis? {
        if (values.isEmpty()) return null
        var min = values.min()
        var max = values.max()
        if (fromZero) min = minOf(0.0, min)
        if (max == min) {
            val pad = if (abs(max) < 1e-9) 1.0 else abs(max) * 0.1
            if (!fromZero || min != 0.0) min -= pad
            max += pad
        }
        val raw = (max - min) / TARGET_TICKS
        val nice = steps?.firstOrNull { it >= raw } ?: steps?.last() ?: niceStep(raw)
        val step = maxOf(nice, minStep)
        val low = if (fromZero && min >= 0) 0.0 else floor(min / step) * step
        var high = ceil(max / step) * step
        // Zapas nad najwyższym punktem, żeby zmieścić etykietę wartości.
        if (high - max < step * 0.25) high += step
        return ChartAxis(low, high, step)
    }

    /** 1, 2, 2.5 albo 5 razy potęga dziesięciu, nie mniej niż [raw]. */
    fun niceStep(raw: Double): Double {
        if (raw <= 0) return 1.0
        val magnitude = 10.0.pow(floor(log10(raw)))
        val normalized = raw / magnitude
        val nice =
            when {
                normalized <= 1.0 -> 1.0
                normalized <= 2.0 -> 2.0
                normalized <= 2.5 -> 2.5
                normalized <= 5.0 -> 5.0
                else -> 10.0
            }
        return nice * magnitude
    }

    /** Środek i-tego z [count] treningów na osi X (0..1); treningi w równych odstępach. */
    fun x(
        index: Int,
        count: Int,
    ): Float = ((index + 0.5) / count).toFloat()

    /** Najbliższy punktowi dotknięcia trening (indeks) przy [count] treningach w równych odstępach. */
    fun nearest(
        fraction: Float,
        count: Int,
    ): Int? = if (count == 0) null else (fraction * count).toInt().coerceIn(0, count - 1)

    /**
     * Które daty podpisać pod osią, żeby się nie nakładały: co n-ty trening, zawsze pierwszy i ostatni.
     * [slots] = ile podpisów mieści się w szerokości wykresu.
     */
    fun labelledIndices(
        count: Int,
        slots: Int,
    ): List<Int> {
        if (count == 0) return emptyList()
        val every = ceil(count.toDouble() / slots.coerceAtLeast(1)).toInt().coerceAtLeast(1)
        val picked = (0 until count step every).toMutableList()
        val last = count - 1
        if (picked.last() != last) {
            // Ostatni zamiast przedostatniego, gdyby się na niego nachodził.
            if (last - picked.last() < every && picked.size > 1) picked.removeAt(picked.lastIndex)
            picked += last
        }
        return picked
    }

    /** Najcięższa seria każdego treningu (przy równym ciężarze więcej powtórzeń), od najstarszego. */
    fun topSets(sets: List<SetPoint>): List<TopSet> =
        sets.groupBy { it.activityId }.values.map { session ->
            val best =
                session.maxWithOrNull(compareBy<SetPoint>({ it.weightKg ?: Double.NEGATIVE_INFINITY }, { it.reps ?: -1 }))
                    ?: session.first()
            TopSet(best.activityId, best.date, best.weightKg, best.reps)
        }

    /** Pierwszy i ostatni dzień do opisu dostępności. */
    fun dateRange(days: List<LocalDate>): Pair<LocalDate, LocalDate>? = if (days.isEmpty()) null else days.min() to days.max()
}
