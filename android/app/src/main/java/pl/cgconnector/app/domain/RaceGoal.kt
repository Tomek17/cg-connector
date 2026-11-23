package pl.cgconnector.app.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Cel użytkownika: start (np. Półmaraton Warszawski), jego data, dystans i opcjonalny czas docelowy. */
data class RaceGoal(
    val name: String,
    val date: LocalDate,
    val distanceKm: Double,
    val targetSeconds: Int? = null,
) {
    /** Tempo docelowe w sekundach na km; `null` bez czasu docelowego. */
    val targetPaceSecPerKm: Double?
        get() = targetSeconds?.takeIf { distanceKm > 0 }?.let { it / distanceKm }

    /** Dni do startu; 0 = dziś, ujemne = start już był. */
    fun daysLeft(today: LocalDate): Long = ChronoUnit.DAYS.between(today, date)

    companion object {
        const val HALF_MARATHON_KM = 21.0975
        const val MARATHON_KM = 42.195

        /** Dystanse do wyboru jednym dotknięciem. */
        val PRESETS = listOf(5.0, 10.0, HALF_MARATHON_KM, MARATHON_KM)

        private val TIME = Regex("""^(?:(\d{1,3}):)?([0-5]?\d):([0-5]\d)$""")

        /** `1:45:00` albo `45:00` → sekundy; `null`, gdy format jest zły. */
        fun parseTime(text: String): Int? {
            val match = TIME.matchEntire(text.trim()) ?: return null
            val (h, m, s) = match.destructured
            return (h.toIntOrNull() ?: 0) * 3600 + m.toInt() * 60 + s.toInt()
        }

        /** 6300 → `1:45:00`, 1500 → `25:00`. */
        fun formatTime(seconds: Int): String {
            val h = seconds / 3600
            val m = seconds % 3600 / 60
            val s = seconds % 60
            return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
        }
    }
}
