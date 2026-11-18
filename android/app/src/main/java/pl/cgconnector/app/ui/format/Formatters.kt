package pl.cgconnector.app.ui.format

import pl.cgconnector.app.domain.Lap
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

/** Czyste funkcje formatowania liczb i dat (bez zasobów Androida, testowane na JVM). */
object Formatters {
    val POLISH: Locale = Locale.forLanguageTag("pl-PL")

    /** `1:02:03` od godziny, `45:07` poniżej godziny. Ujemne wartości traktowane jak 0. */
    fun duration(totalSec: Long): String {
        val sec = totalSec.coerceAtLeast(0)
        val hours = sec / 3600
        val minutes = (sec % 3600) / 60
        val seconds = sec % 60
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }

    fun duration(totalSec: Double): String = duration(totalSec.roundToLong())

    /**
     * Tempo z minut dziesiętnych na km (5.5 → `5:30`). Sekundy są zaokrąglane, więc 5.999 → `6:00`.
     * `null` dla wartości nieskończonych, ≤ 0 albo absurdalnych (≥ 60 min/km, np. postój).
     */
    fun pace(minPerKm: Double?): String? {
        if (minPerKm == null || !minPerKm.isFinite() || minPerKm <= 0 || minPerKm >= MAX_PACE_MIN_PER_KM) return null
        val totalSec = (minPerKm * 60).roundToLong()
        return String.format(Locale.ROOT, "%d:%02d", totalSec / 60, totalSec % 60)
    }

    /** Tempo w min/km z prędkości w m/s; `null` dla prędkości ≤ 0. */
    fun paceFromSpeed(speedMps: Double?): Double? =
        speedMps?.takeIf { it.isFinite() && it > 0 }?.let { 1000.0 / it / 60.0 }

    /** Tempo z dystansu i czasu; `null`, gdy brakuje danych. */
    fun paceFromDistance(
        distanceM: Double?,
        durationSec: Double?,
    ): Double? {
        if (distanceM == null || durationSec == null || distanceM <= 0 || durationSec <= 0) return null
        return durationSec / 60.0 / (distanceM / 1000.0)
    }

    /** Tempo okrążenia: z serwera, a gdy go brak, z prędkości albo dystansu i czasu. */
    fun lapPace(lap: Lap): Double? =
        lap.paceMinPerKm ?: paceFromSpeed(lap.avgSpeedMps) ?: paceFromDistance(lap.distanceM, lap.durationSec)

    /** Kilometry z dwoma miejscami po przecinku, z polskim separatorem: `8,01`. */
    fun km(distanceM: Double): String = String.format(POLISH, "%.2f", distanceM / 1000.0)

    /** `BARBELL_BENCH_PRESS` → `Barbell bench press`. */
    fun exerciseName(raw: String): String =
        raw
            .replace('_', ' ')
            .trim()
            .lowercase(Locale.ROOT)
            .replaceFirstChar { it.titlecase(Locale.ROOT) }

    /** Ciężar bez zbędnych zer: 60.0 → `60`, 22.5 → `22,5`. */
    fun weight(kg: Double): String =
        if (kg % 1.0 == 0.0) kg.toLong().toString() else String.format(POLISH, "%.1f", kg)

    fun dateTime(
        instant: Instant,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = DATE_TIME.withZone(zone).format(instant)

    private const val MAX_PACE_MIN_PER_KM = 60.0
    private val DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", POLISH)
}
