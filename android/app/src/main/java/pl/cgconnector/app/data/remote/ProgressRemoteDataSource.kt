package pl.cgconnector.app.data.remote

import kotlinx.coroutines.delay
import pl.cgconnector.app.domain.ActivityTag
import javax.inject.Inject

/** Tagi i dane zakładki Progress: zawsze prosto z serwera (bez kopii w Room). */
interface ProgressRemoteDataSource {
    suspend fun fetchTags(): ProgressTagsDto

    suspend fun fetchTagProgress(tag: String): TagProgressDto

    suspend fun setActivityTag(
        activityId: Long,
        tag: String?,
    ): ActivityDto
}

class ApiProgressRemoteDataSource
    @Inject
    constructor(
        private val apiProvider: CgApiProvider,
    ) : ProgressRemoteDataSource {
        override suspend fun fetchTags(): ProgressTagsDto = api().progressTags()

        override suspend fun fetchTagProgress(tag: String): TagProgressDto = api().tagProgress(tag)

        override suspend fun setActivityTag(
            activityId: Long,
            tag: String?,
        ): ActivityDto = api().setActivityTag(activityId, TagUpdateDto(tag))

        private suspend fun api(): CgApi = apiProvider.get() ?: throw NotConfiguredException()
    }

/** Dane syntetyczne dla buildu z `USE_FAKE_API = true`: po kilka sesji dla interwałów i nóg. */
class FakeProgressRemoteDataSource
    @Inject
    constructor() : ProgressRemoteDataSource {
        override suspend fun fetchTags(): ProgressTagsDto {
            delay(LATENCY_MS)
            return ProgressTagsDto(
                tags =
                    listOf(
                        TagCountDto(ActivityTag.INTERVALS.key, "running", SESSIONS, "2026-11-20"),
                        TagCountDto(ActivityTag.LEGS.key, "strength", SESSIONS, "2026-11-19"),
                    ),
            )
        }

        override suspend fun fetchTagProgress(tag: String): TagProgressDto {
            delay(LATENCY_MS)
            val days = (0 until SESSIONS).map { "2026-11-${(it * 7 + 1).toString().padStart(2, '0')}" }
            return when (tag) {
                ActivityTag.INTERVALS.key ->
                    TagProgressDto(
                        tag = tag,
                        type = "running",
                        running =
                            days.mapIndexed { i, day ->
                                RunPointDto(
                                    activityId = i.toLong() + 1,
                                    date = day,
                                    name = "Interwały 6×800 m",
                                    distanceM = 9_000.0 + i * 250,
                                    paceMinPerKm = 4.25 - i * 0.05,
                                    avgHr = 172 - i,
                                    avgCadenceSpm = 182 + i,
                                    workLaps = 6,
                                )
                            },
                    )
                ActivityTag.LEGS.key ->
                    TagProgressDto(
                        tag = tag,
                        type = "strength",
                        strength =
                            listOf(
                                ExerciseProgressDto(
                                    category = "SQUAT",
                                    exercise = "BARBELL_BACK_SQUAT",
                                    sessions = SESSIONS,
                                    sets =
                                        days.flatMapIndexed { i, day ->
                                            (1..3).map { n ->
                                                SetPointDto(i.toLong() + 1, day, n, 80.0 + i * 2.5, 6 - n % 2)
                                            }
                                        },
                                ),
                            ),
                    )
                else -> TagProgressDto(tag = tag, type = ActivityTag.fromKey(tag)?.type?.apiValue ?: "other")
            }
        }

        override suspend fun setActivityTag(
            activityId: Long,
            tag: String?,
        ): ActivityDto {
            delay(LATENCY_MS)
            return ActivityDto(id = activityId, type = "other", startTime = "2026-11-20T07:00:00+01:00", durationSec = 0, tag = tag)
        }

        private companion object {
            const val LATENCY_MS = 300L
            const val SESSIONS = 4
        }
    }
