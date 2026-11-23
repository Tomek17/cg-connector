package pl.cgconnector.app.domain

/** Porównanie wersji `X.Y.Z` (z opcjonalnym `v` i sufiksem `-rc1`, który jest starszy od wydania). */
object AppVersion {
    private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(-[0-9A-Za-z.]+)?$""")

    fun isNewer(
        candidate: String,
        current: String,
    ): Boolean {
        val a = PATTERN.find(candidate.trim()) ?: return false
        val b = PATTERN.find(current.trim()) ?: return false
        for (i in 1..3) {
            val x = a.groupValues[i].toInt()
            val y = b.groupValues[i].toInt()
            if (x != y) return x > y
        }
        val preA = a.groupValues[4]
        val preB = b.groupValues[4]
        // 1.0.0 jest nowsze niż 1.0.0-rc1; dwa różne rc traktujemy jako „nie nowsze”.
        return preA.isEmpty() && preB.isNotEmpty()
    }
}
