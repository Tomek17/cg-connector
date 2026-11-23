package pl.cgconnector.app.data.remote

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.ClimbSession
import pl.cgconnector.app.domain.ExerciseProgress
import pl.cgconnector.app.domain.GradeCount
import pl.cgconnector.app.domain.RunPoint
import pl.cgconnector.app.domain.SetPoint
import pl.cgconnector.app.domain.TagCount
import pl.cgconnector.app.domain.TagProgress
import java.time.LocalDate

/** `PUT /api/v1/activities/{id}/tag`; `null` (Json aplikacji pomija wtedy pole) usuwa tag. */
@Serializable
data class TagUpdateDto(
    val tag: String? = null,
)

/** `GET /api/v1/progress` (schemat `ProgressTags`). */
@Serializable
data class ProgressTagsDto(
    val tags: List<TagCountDto>,
)

@Serializable
data class TagCountDto(
    val tag: String,
    val type: String,
    val count: Int,
    @SerialName("last_date") val lastDate: String,
)

/** `GET /api/v1/progress/{tag}` (schemat `TagProgress`). */
@Serializable
data class TagProgressDto(
    val tag: String,
    val type: String,
    val running: List<RunPointDto> = emptyList(),
    val strength: List<ExerciseProgressDto> = emptyList(),
    val climbing: List<ClimbSessionDto> = emptyList(),
)

@Serializable
data class ClimbSessionDto(
    @SerialName("activity_id") val activityId: Long,
    val date: String,
    val name: String? = null,
    @SerialName("top_grade") val topGrade: String? = null,
    @SerialName("top_sort") val topSort: Int? = null,
    val sent: Int,
    val failed: Int,
    val grades: List<GradeCountDto> = emptyList(),
)

@Serializable
data class GradeCountDto(
    val grade: String,
    val sort: Int,
    val sent: Int,
    val failed: Int,
)

@Serializable
data class RunPointDto(
    @SerialName("activity_id") val activityId: Long,
    val date: String,
    val name: String? = null,
    @SerialName("distance_m") val distanceM: Double? = null,
    @SerialName("pace_min_per_km") val paceMinPerKm: Double? = null,
    @SerialName("avg_hr") val avgHr: Int? = null,
    @SerialName("avg_cadence_spm") val avgCadenceSpm: Int? = null,
    @SerialName("work_laps") val workLaps: Int = 0,
)

@Serializable
data class ExerciseProgressDto(
    val category: String? = null,
    val exercise: String,
    val sessions: Int,
    val sets: List<SetPointDto> = emptyList(),
)

@Serializable
data class SetPointDto(
    @SerialName("activity_id") val activityId: Long,
    val date: String,
    @SerialName("set_number") val setNumber: Int,
    @SerialName("weight_kg") val weightKg: Double? = null,
    val reps: Int? = null,
)

/** Tagi nieznane tej wersji aplikacji (nowszy serwer) są pomijane. */
fun ProgressTagsDto.toDomain(): List<TagCount> =
    tags.mapNotNull { dto ->
        ActivityTag.fromKey(dto.tag)?.let { TagCount(it, dto.count, LocalDate.parse(dto.lastDate)) }
    }

/** `null` = tag nieznany tej wersji aplikacji. */
fun TagProgressDto.toDomain(): TagProgress? {
    val tag = ActivityTag.fromKey(tag) ?: return null
    return TagProgress(
        tag = tag,
        running =
            running.map {
                RunPoint(
                    activityId = it.activityId,
                    date = LocalDate.parse(it.date),
                    name = it.name,
                    distanceM = it.distanceM,
                    paceMinPerKm = it.paceMinPerKm,
                    avgHr = it.avgHr,
                    avgCadenceSpm = it.avgCadenceSpm,
                    workLaps = it.workLaps,
                )
            },
        strength =
            strength.map { exercise ->
                ExerciseProgress(
                    category = exercise.category,
                    exercise = exercise.exercise,
                    sessions = exercise.sessions,
                    sets =
                        exercise.sets.map {
                            SetPoint(it.activityId, LocalDate.parse(it.date), it.setNumber, it.weightKg, it.reps)
                        },
                )
            },
        climbing =
            climbing.map { session ->
                ClimbSession(
                    activityId = session.activityId,
                    date = LocalDate.parse(session.date),
                    name = session.name,
                    topGrade = session.topGrade,
                    topSort = session.topSort,
                    sent = session.sent,
                    failed = session.failed,
                    grades = session.grades.map { GradeCount(it.grade, it.sort, it.sent, it.failed) },
                )
            },
    )
}
