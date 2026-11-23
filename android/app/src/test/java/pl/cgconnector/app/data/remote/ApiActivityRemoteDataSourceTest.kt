package pl.cgconnector.app.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.LocalDate

class ApiActivityRemoteDataSourceTest {
    private class PagedApi(
        private val pages: List<ActivityListDto>,
    ) : CgApi {
        val requests = mutableListOf<Triple<String?, String?, Int?>>()

        override suspend fun activities(
            from: String?,
            to: String?,
            type: String?,
            limit: Int?,
        ): ActivityListDto {
            requests += Triple(from, to, limit)
            return pages[requests.size - 1]
        }

        override suspend fun me(): MeDto = error("unused")

        override suspend fun activity(id: Long): ActivityDetailDto = error("unused")

        override suspend fun vo2max(
            from: String?,
            to: String?,
        ): Vo2MaxListDto = error("unused")

        override suspend fun summary(
            period: String,
            from: String?,
            to: String?,
        ): SummaryDto = error("unused")

        override suspend fun createWorkout(body: NewWorkoutDto): WorkoutOutDto = error("unused")

        override suspend fun scheduleWorkout(
            workoutId: Long,
            body: ScheduleRequestDto,
        ): ScheduledOutDto = error("unused")

        override suspend fun scheduled(
            from: String?,
            to: String?,
        ): ScheduledListDto = error("unused")

        override suspend fun deleteScheduled(scheduledId: Long): ScheduledOutDto = error("unused")

        override suspend fun setActivityTag(
            id: Long,
            body: TagUpdateDto,
        ): ActivityDto = error("unused")

        override suspend fun progressTags(): ProgressTagsDto = error("unused")

        override suspend fun tagProgress(tag: String): TagProgressDto = error("unused")

        override suspend fun goal(): GoalStateDto = error("unused")

        override suspend fun setGoal(body: GoalDto): GoalStateDto = error("unused")

        override suspend fun deleteGoal(): GoalStateDto = error("unused")
    }

    private fun dto(
        id: Long,
        day: String,
    ) = ActivityDto(id = id, type = "running", startTime = "${day}T07:00:00+01:00", durationSec = 60)

    @Test
    fun singlePageWhenNoMore() =
        runTest {
            val api = PagedApi(listOf(ActivityListDto(listOf(dto(1, "2026-11-17")), hasMore = false)))

            val items = ApiActivityRemoteDataSource.fetchAllPages(api, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 18))

            assertThat(items.map { it.id }).containsExactly(1L)
            assertThat(api.requests).containsExactly(Triple("2026-01-01", "2026-11-18", CgApi.MAX_LIMIT))
        }

    @Test
    fun followsHasMoreWithToSetToOldestDayAndDeduplicates() =
        runTest {
            val api =
                PagedApi(
                    listOf(
                        ActivityListDto(listOf(dto(3, "2026-11-17"), dto(2, "2026-11-10")), hasMore = true),
                        ActivityListDto(listOf(dto(2, "2026-11-10"), dto(1, "2026-11-01")), hasMore = false),
                    ),
                )

            val items = ApiActivityRemoteDataSource.fetchAllPages(api, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 18), limit = 2)

            assertThat(items.map { it.id }).containsExactly(3L, 2L, 1L)
            assertThat(api.requests.map { it.second }).containsExactly("2026-11-18", "2026-11-10").inOrder()
        }

    @Test
    fun stopsWhenServerMakesNoProgress() =
        runTest {
            val samePage = ActivityListDto(listOf(dto(1, "2026-11-17"), dto(2, "2026-11-17")), hasMore = true)
            val api = PagedApi(List(5) { samePage })

            val items = ApiActivityRemoteDataSource.fetchAllPages(api, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 11, 17), limit = 2)

            assertThat(items).hasSize(2)
            assertThat(api.requests).hasSize(1)
        }
}
