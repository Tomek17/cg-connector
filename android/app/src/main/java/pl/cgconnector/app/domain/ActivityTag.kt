package pl.cgconnector.app.domain

import java.time.LocalDate

/**
 * Tag aktywności (jeden na aktywność). [key] to wartość w API serwera (`cg_server.tags.TAGS`),
 * [type] to rodzaj aktywności, do którego tag pasuje. Kolejność wpisów = kolejność na ekranach.
 */
enum class ActivityTag(
    val key: String,
    val type: ActivityType,
    /** Grupa w zakładce Progress (wszystkie aktywności rodzaju); nie da się jej nadać aktywności. */
    val isGroup: Boolean = false,
) {
    ALL_RUNNING("all_running", ActivityType.RUNNING, isGroup = true),
    INTERVALS("intervals", ActivityType.RUNNING),
    TEMPO("tempo", ActivityType.RUNNING),
    LONG("long", ActivityType.RUNNING),
    BASE("base", ActivityType.RUNNING),
    EASY("easy", ActivityType.RUNNING),
    STRIDES("strides", ActivityType.RUNNING),
    ALL_STRENGTH("all_strength", ActivityType.STRENGTH, isGroup = true),
    UPPER("upper", ActivityType.STRENGTH),
    FBW("fbw", ActivityType.STRENGTH),
    PUSH("push", ActivityType.STRENGTH),
    PULL("pull", ActivityType.STRENGTH),
    LEGS("legs", ActivityType.STRENGTH),
    WALL("wall", ActivityType.CLIMBING),
    ;

    companion object {
        /** Nieznany klucz (nowszy serwer) = `null`: aplikacja pokazuje aktywność bez tagu. */
        fun fromKey(key: String?): ActivityTag? = entries.firstOrNull { it.key == key }

        /** Pozycje zakładki Progress danego rodzaju: najpierw grupa „wszystkie”, potem tagi. */
        fun forType(type: ActivityType): List<ActivityTag> = entries.filter { it.type == type }

        /** Tagi, które można nadać aktywności (bez grup). */
        fun settableFor(type: ActivityType): List<ActivityTag> = forType(type).filterNot { it.isGroup }

        /** Kategorie zakładki Progress w kolejności z ekranu. */
        val CATEGORIES = listOf(ActivityType.STRENGTH, ActivityType.RUNNING, ActivityType.CLIMBING)
    }
}

/** Ile aktywności ma dany tag i kiedy była ostatnia. */
data class TagCount(
    val tag: ActivityTag,
    val count: Int,
    val lastDate: LocalDate,
)

/** Jeden bieg z tagiem; tempo, tętno i kadencja tylko z odcinków roboczych, gdy [workLaps] > 0. */
data class RunPoint(
    val activityId: Long,
    val date: LocalDate,
    val name: String?,
    val distanceM: Double?,
    val paceMinPerKm: Double?,
    val avgHr: Int?,
    val avgCadenceSpm: Int?,
    val workLaps: Int,
)

/** Jedna seria ćwiczenia; [setNumber] liczony od 1 w ramach ćwiczenia w danej sesji. */
data class SetPoint(
    val activityId: Long,
    val date: LocalDate,
    val setNumber: Int,
    val weightKg: Double?,
    val reps: Int?,
)

data class ExerciseProgress(
    val category: String?,
    val exercise: String,
    val sessions: Int,
    /** Od najstarszej sesji. */
    val sets: List<SetPoint>,
)

/** Dane do wykresów jednego tagu; wypełniona jest lista pasująca do rodzaju tagu. */
data class TagProgress(
    val tag: ActivityTag,
    /** Od najstarszego biegu. */
    val running: List<RunPoint>,
    /** Od najczęściej wykonywanego ćwiczenia. */
    val strength: List<ExerciseProgress>,
    /** Od najstarszej sesji na ściance. */
    val climbing: List<ClimbSession> = emptyList(),
)

/** Sesja na ściance: najtrudniejszy udany problem i próby (z bulderów zapisanych na zegarku). */
data class ClimbSession(
    val activityId: Long,
    val date: LocalDate,
    val name: String?,
    /** Np. `V4`; `null` = żadnego udanego wejścia. */
    val topGrade: String?,
    /** Kolejność trudności Garmina (większa = trudniej). */
    val topSort: Int?,
    val sent: Int,
    val failed: Int,
    /** Od najłatwiejszego stopnia. */
    val grades: List<GradeCount>,
)

data class GradeCount(
    val grade: String,
    val sort: Int,
    val sent: Int,
    val failed: Int,
)

/** Dlaczego nie udało się pobrać danych z serwera (zakładka Progress działa tylko online). */
enum class LoadError { OFFLINE, UNAUTHORIZED, NOT_CONFIGURED, INVALID_RESPONSE }
