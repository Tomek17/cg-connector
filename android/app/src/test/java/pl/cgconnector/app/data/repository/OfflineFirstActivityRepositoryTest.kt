package pl.cgconnector.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.cgconnector.app.data.local.CgDatabase
import pl.cgconnector.app.data.remote.ActivityDetailDto
import pl.cgconnector.app.data.remote.ActivityDto
import pl.cgconnector.app.data.remote.ActivityRemoteDataSource
import pl.cgconnector.app.data.remote.LapDto
import pl.cgconnector.app.data.remote.MeDto
import pl.cgconnector.app.data.remote.NotConfiguredException
import pl.cgconnector.app.data.remote.SummaryDto
import pl.cgconnector.app.data.remote.SummaryItemDto
import pl.cgconnector.app.data.remote.Vo2MaxPointDto
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.RefreshOutcome
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class OfflineFirstActivityRepositoryTest {
    private lateinit var db: CgDatabase
    private val clock = Clock.fixed(Instant.parse("2026-11-18T13:20:00Z"), ZoneOffset.UTC)

    private class FakeRemote : ActivityRemoteDataSource {
        var items: List<ActivityDto> = emptyList()
        var vo2max: List<Vo2MaxPointDto> = emptyList()
        var me = MeDto(name = "a", displayName = "a")
        var detail: ActivityDetailDto? = null
        var error: Exception? = null
        var detailError: Exception? = null
        val requests = mutableListOf<Pair<LocalDate, LocalDate>>()
        val vo2Requests = mutableListOf<Pair<LocalDate, LocalDate>>()

        override suspend fun fetchActivities(
            from: LocalDate,
            to: LocalDate,
        ): List<ActivityDto> {
            requests += from to to
            error?.let { throw it }
            return items
        }

        override suspend fun fetchActivityDetail(id: Long): ActivityDetailDto {
            detailError?.let { throw it }
            return detail ?: throw IOException("no detail")
        }

        override suspend fun fetchVo2Max(
            from: LocalDate,
            to: LocalDate,
        ): List<Vo2MaxPointDto> {
            vo2Requests += from to to
            return vo2max
        }

        override suspend fun fetchMe(): MeDto = me

        override suspend fun fetchWeeklySummary(): SummaryDto =
            SummaryDto(
                period = "week",
                from = "2026-11-16",
                to = "2026-11-22",
                items = listOf(SummaryItemDto("2026-11-16", "2026-11-22", "running", 2, 16_000.0, 5_400)),
            )
    }

    private val remote = FakeRemote()
    private lateinit var repository: OfflineFirstActivityRepository
    private lateinit var stats: RoomStatsRepository

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CgDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = OfflineFirstActivityRepository(db.activityDao(), db.activityDetailDao(), db.statsDao(), remote, clock)
        stats = RoomStatsRepository(db.statsDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun firstRefresh_fetchesYearOfHistoryAndStoresIt() =
        runTest {
            remote.items = listOf(dto(1, "running", "2026-11-17T07:30:00+01:00"))

            val result = repository.refresh()

            assertThat(result).isEqualTo(RefreshResult.Success(1))
            assertThat(remote.requests.single()).isEqualTo(LocalDate.of(2025, 11, 18) to LocalDate.of(2026, 11, 18))
            val stored = repository.observeActivities(null).first().single()
            assertThat(stored.type).isEqualTo(ActivityType.RUNNING)
            assertThat(stored.startTime).isEqualTo(Instant.parse("2026-11-17T06:30:00Z"))
        }

    @Test
    fun nextRefresh_startsThreeDaysBeforeNewestLocalActivity() =
        runTest {
            remote.items = listOf(dto(1, "running", "2026-11-10T07:30:00+01:00"))
            repository.refresh()

            repository.refresh()

            assertThat(remote.requests.last().first).isEqualTo(LocalDate.of(2026, 11, 7))
        }

    @Test
    fun serverDown_keepsLocalDataAndReportsUnavailable() =
        runTest {
            remote.items = listOf(dto(1, "strength", "2026-11-10T07:30:00+01:00"))
            repository.refresh()
            remote.error = IOException("connection refused")

            val result = repository.refresh()

            assertThat(result).isEqualTo(RefreshResult.Unavailable)
            assertThat(repository.observeActivities(null).first()).hasSize(1)
        }

    @Test
    fun notConfigured_isReported() =
        runTest {
            remote.error = NotConfiguredException()

            assertThat(repository.refresh()).isEqualTo(RefreshResult.NotConfigured)
        }

    @Test
    fun unknownType_isStoredAsOther() =
        runTest {
            remote.items = listOf(dto(1, "paragliding", "2026-11-10T07:30:00+01:00"))

            repository.refresh()

            assertThat(repository.observeActivities(ActivityType.OTHER).first()).hasSize(1)
        }

    @Test
    fun refresh_storesVo2MaxServerStatusAndSummaries() =
        runTest {
            remote.vo2max = listOf(Vo2MaxPointDto("2026-11-10", 51.5, "running"))
            remote.me = MeDto(name = "a", displayName = "a", garminStatus = "ok", lastSyncAt = "2026-11-18T14:03:00+01:00")

            repository.refresh()

            assertThat(stats.observeVo2Max().first().single().value).isEqualTo(51.5)
            val status = stats.observeSyncStatus().first()
            assertThat(status.serverLastSyncAt).isEqualTo(Instant.parse("2026-11-18T13:03:00Z"))
            assertThat(status.garminStatus).isEqualTo("ok")
            assertThat(status.lastRefreshOutcome).isEqualTo(RefreshOutcome.SUCCESS)
            assertThat(status.lastRefreshSuccessAt).isEqualTo(clock.instant())
            assertThat(stats.observeWeeklySummaries().first().single().count).isEqualTo(2)
        }

    @Test
    fun nextRefresh_fetchesVo2MaxFromThreeDaysBeforeNewestLocalPoint() =
        runTest {
            remote.vo2max = listOf(Vo2MaxPointDto("2026-11-10", 51.5, "running"))
            repository.refresh()

            repository.refresh()

            assertThat(remote.vo2Requests.first().first).isEqualTo(LocalDate.of(2025, 11, 18))
            assertThat(remote.vo2Requests.last().first).isEqualTo(LocalDate.of(2026, 11, 7))
        }

    @Test
    fun failedRefresh_recordsOutcomeButKeepsLastSuccessTime() =
        runTest {
            repository.refresh()
            remote.error = IOException("down")

            repository.refresh()

            val status = stats.observeSyncStatus().first()
            assertThat(status.lastRefreshOutcome).isEqualTo(RefreshOutcome.UNAVAILABLE)
            assertThat(status.lastRefreshSuccessAt).isEqualTo(clock.instant())
            assertThat(status.lastRefreshAttemptAt).isEqualTo(clock.instant())
        }

    @Test
    fun activityDetail_recordsAttemptInSyncStatusWithoutMovingLastRefresh() =
        runTest {
            remote.detailError = HttpException(Response.error<Any>(401, "{}".toResponseBody()))

            assertThat(repository.refreshActivityDetail(5)).isEqualTo(RefreshResult.Unauthorized)

            var status = stats.observeSyncStatus().first()
            assertThat(status.lastRefreshOutcome).isEqualTo(RefreshOutcome.UNAUTHORIZED)
            assertThat(status.lastRefreshAttemptAt).isEqualTo(clock.instant())
            assertThat(status.lastRefreshSuccessAt).isNull()

            remote.detailError = IOException("offline")
            repository.refreshActivityDetail(5)
            status = stats.observeSyncStatus().first()
            assertThat(status.lastRefreshOutcome).isEqualTo(RefreshOutcome.UNAVAILABLE)

            remote.detailError = null
            remote.detail = ActivityDetailDto(id = 5, type = "running", startTime = "2026-11-10T07:30:00+01:00", durationSec = 60)
            repository.refreshActivityDetail(5)
            status = stats.observeSyncStatus().first()
            assertThat(status.lastRefreshOutcome).isEqualTo(RefreshOutcome.SUCCESS)
            assertThat(status.lastRefreshSuccessAt).isNull()
        }

    @Test
    fun activityDetail_isFetchedOnDemandAndAvailableOffline() =
        runTest {
            remote.items = listOf(dto(5, "running", "2026-11-10T07:30:00+01:00"))
            repository.refresh()
            assertThat(repository.observeActivityDetail(5).first()!!.hasDetails).isFalse()
            remote.detail =
                ActivityDetailDto(
                    id = 5,
                    type = "running",
                    startTime = "2026-11-10T07:30:00+01:00",
                    durationSec = 1_800,
                    detailsAvailable = true,
                    laps = listOf(LapDto(index = 1, distanceM = 1000.0, paceMinPerKm = 5.5)),
                )

            assertThat(repository.refreshActivityDetail(5)).isEqualTo(RefreshResult.Success(1))
            remote.detailError = IOException("offline")
            assertThat(repository.refreshActivityDetail(5)).isEqualTo(RefreshResult.Unavailable)

            val cached = repository.observeActivityDetail(5).first()!!
            assertThat(cached.hasDetails).isTrue()
            assertThat(cached.detailsFetchedAt).isEqualTo(clock.instant())
            assertThat(cached.laps.single().paceMinPerKm).isEqualTo(5.5)
        }

    private fun dto(
        id: Long,
        type: String,
        start: String,
    ) = ActivityDto(id = id, type = type, startTime = start, durationSec = 1_800)
}
