package pl.cgconnector.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import pl.cgconnector.app.data.local.CgDatabase
import pl.cgconnector.app.data.remote.ActivityDto
import pl.cgconnector.app.data.remote.ActivityRemoteDataSource
import pl.cgconnector.app.data.remote.NotConfiguredException
import pl.cgconnector.app.domain.ActivityType
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
        var error: Exception? = null
        val requests = mutableListOf<Pair<LocalDate, LocalDate>>()

        override suspend fun fetchActivities(
            from: LocalDate,
            to: LocalDate,
        ): List<ActivityDto> {
            requests += from to to
            error?.let { throw it }
            return items
        }
    }

    private val remote = FakeRemote()
    private lateinit var repository: OfflineFirstActivityRepository

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CgDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repository = OfflineFirstActivityRepository(db.activityDao(), remote, clock)
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

    private fun dto(
        id: Long,
        type: String,
        start: String,
    ) = ActivityDto(id = id, type = type, startTime = start, durationSec = 1_800)
}
