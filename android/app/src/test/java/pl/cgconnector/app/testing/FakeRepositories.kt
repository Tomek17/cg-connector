package pl.cgconnector.app.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import pl.cgconnector.app.data.repository.ActivityRepository
import pl.cgconnector.app.data.repository.Loaded
import pl.cgconnector.app.data.repository.ProgressRepository
import pl.cgconnector.app.data.repository.RefreshResult
import pl.cgconnector.app.data.repository.StatsRepository
import pl.cgconnector.app.domain.Activity
import pl.cgconnector.app.domain.ActivityDetail
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.PeriodSummary
import pl.cgconnector.app.domain.SyncStatus
import pl.cgconnector.app.domain.TagCount
import pl.cgconnector.app.domain.TagProgress
import pl.cgconnector.app.domain.Vo2MaxPoint
import java.time.Instant

class FakeActivityRepository(
    activities: List<Activity> = emptyList(),
    var refreshResult: RefreshResult = RefreshResult.Success(0),
    var detailResult: RefreshResult = RefreshResult.Success(1),
) : ActivityRepository {
    val data = MutableStateFlow(activities)
    val details = MutableStateFlow<Map<Long, ActivityDetail>>(emptyMap())
    var refreshCalls = 0
    val detailRequests = mutableListOf<Long>()

    /** Wywoływane przy udanym pobraniu szczegółów (symuluje zapis do Room). */
    var onDetailFetched: (Long) -> Unit = {}

    override fun observeActivities(type: ActivityType?): Flow<List<Activity>> =
        data.map { list -> list.filter { type == null || it.type == type } }

    override fun observeActivityDetail(id: Long): Flow<ActivityDetail?> = details.map { it[id] }

    override suspend fun refreshActivityDetail(id: Long): RefreshResult {
        detailRequests += id
        if (detailResult is RefreshResult.Success) onDetailFetched(id)
        return detailResult
    }

    override suspend fun refresh(): RefreshResult {
        refreshCalls++
        return refreshResult
    }
}

class FakeStatsRepository(
    vo2max: List<Vo2MaxPoint> = emptyList(),
    status: SyncStatus = SyncStatus(),
    summaries: List<PeriodSummary> = emptyList(),
) : StatsRepository {
    val vo2max = MutableStateFlow(vo2max)
    val status = MutableStateFlow(status)
    val summaries = MutableStateFlow(summaries)

    override fun observeVo2Max(): Flow<List<Vo2MaxPoint>> = vo2max

    override fun observeWeeklySummaries(): Flow<List<PeriodSummary>> = summaries

    override fun observeSyncStatus(): Flow<SyncStatus> = status
}

fun testActivity(
    id: Long,
    type: ActivityType = ActivityType.RUNNING,
    start: Instant = Instant.parse("2026-11-01T07:00:00Z").plusSeconds(id * 86_400),
) = Activity(
    id = id,
    type = type,
    name = "A$id",
    startTime = start,
    durationSec = 1800,
    distanceM = null,
    avgHr = null,
    maxHr = null,
    elevationGainM = null,
    avgPowerW = null,
    avgCadenceSpm = null,
)

class FakeProgressRepository(
    var tagsResult: Loaded<List<TagCount>> = Loaded.Ok(emptyList()),
    var progressResult: (ActivityTag) -> Loaded<TagProgress> = { Loaded.Ok(TagProgress(it, emptyList(), emptyList())) },
    var setTagResult: Loaded<Unit> = Loaded.Ok(Unit),
) : ProgressRepository {
    val savedTags = mutableListOf<Pair<Long, ActivityTag?>>()
    var tagsCalls = 0

    override suspend fun tags(): Loaded<List<TagCount>> {
        tagsCalls++
        return tagsResult
    }

    override suspend fun tagProgress(tag: ActivityTag): Loaded<TagProgress> = progressResult(tag)

    override suspend fun setTag(
        activityId: Long,
        tag: ActivityTag?,
    ): Loaded<Unit> {
        if (setTagResult is Loaded.Ok) savedTags += activityId to tag
        return setTagResult
    }
}

class FakeGoalRepository(
    goal: pl.cgconnector.app.domain.RaceGoal? = null,
    var result: Loaded<Unit> = Loaded.Ok(Unit),
) : pl.cgconnector.app.data.repository.GoalRepository {
    val goal = MutableStateFlow(goal)
    var refreshCalls = 0

    override fun observeGoal(): Flow<pl.cgconnector.app.domain.RaceGoal?> = goal

    override suspend fun refresh(): Loaded<Unit> {
        refreshCalls++
        return result
    }

    override suspend fun save(goal: pl.cgconnector.app.domain.RaceGoal): Loaded<Unit> {
        if (result is Loaded.Ok) this.goal.value = goal
        return result
    }

    override suspend fun clear(): Loaded<Unit> {
        if (result is Loaded.Ok) goal.value = null
        return result
    }
}
