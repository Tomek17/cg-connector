package pl.cgconnector.app.data.local

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
import pl.cgconnector.app.domain.ActivityType
import java.time.Instant
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ActivityDetailDaoTest {
    private lateinit var db: CgDatabase

    @Before
    fun setUp() {
        db =
            Room
                .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), CgDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun observe_withoutFetchedDetails_returnsActivityWithEmptyDetails() =
        runTest {
            db.activityDao().upsertAll(listOf(activity(1)))

            val result = db.activityDetailDao().observe(1).first()!!

            assertThat(result.details).isNull()
            assertThat(result.laps).isEmpty()
            assertThat(result.toDomain().hasDetails).isFalse()
        }

    @Test
    fun observe_missingActivity_returnsNull() =
        runTest {
            assertThat(db.activityDetailDao().observe(99).first()).isNull()
        }

    @Test
    fun replace_storesDetailsAndReplacesPreviousOnes() =
        runTest {
            val dao = db.activityDetailDao()
            dao.replace(bundle(lapCount = 3))
            dao.replace(bundle(lapCount = 2))

            val detail = dao.observe(1).first()!!.toDomain()

            assertThat(detail.hasDetails).isTrue()
            assertThat(detail.laps.map { it.index }).containsExactly(1, 2).inOrder()
            assertThat(detail.hrZones.map { it.zone }).containsExactly(1, 2).inOrder()
            assertThat(detail.exerciseSets.map { it.setType }).containsExactly("ACTIVE", "REST").inOrder()
        }

    @Test
    fun listRefresh_doesNotEraseFetchedDetails() =
        runTest {
            db.activityDetailDao().replace(bundle(lapCount = 2))

            db.activityDao().upsertAll(listOf(activity(1).copy(name = "Nowa nazwa")))

            val detail = db.activityDetailDao().observe(1).first()!!.toDomain()
            assertThat(detail.activity.name).isEqualTo("Nowa nazwa")
            assertThat(detail.hasDetails).isTrue()
            assertThat(detail.laps).hasSize(2)
        }

    @Test
    fun statsDao_vo2maxSummariesAndSyncStatus() =
        runTest {
            val stats = db.statsDao()
            stats.upsertVo2Max(
                listOf(
                    Vo2MaxEntity(LocalDate.of(2026, 11, 10), "running", 51.0),
                    Vo2MaxEntity(LocalDate.of(2026, 11, 1), "running", 50.0),
                ),
            )
            stats.upsertVo2Max(listOf(Vo2MaxEntity(LocalDate.of(2026, 11, 10), "running", 52.0)))

            assertThat(stats.observeVo2Max().first().map { it.value }).containsExactly(50.0, 52.0).inOrder()
            assertThat(stats.latestVo2MaxDate()).isEqualTo(LocalDate.of(2026, 11, 10))

            stats.replaceSummaries("week", listOf(summary("2026-11-02"), summary("2026-11-09")))
            stats.replaceSummaries("week", listOf(summary("2026-11-16")))
            assertThat(stats.observeSummaries("week").first().map { it.periodStart })
                .containsExactly(LocalDate.of(2026, 11, 16))

            stats.updateSyncStatus { it.copy(garminStatus = "ok") }
            stats.updateSyncStatus { it.copy(lastRefreshOutcome = "SUCCESS") }
            val status = stats.getSyncStatus()!!
            assertThat(status.garminStatus).isEqualTo("ok")
            assertThat(status.lastRefreshOutcome).isEqualTo("SUCCESS")
        }

    private fun summary(start: String) =
        SummaryEntity(
            period = "week",
            periodStart = LocalDate.parse(start),
            periodEnd = LocalDate.parse(start).plusDays(6),
            type = "running",
            count = 2,
            distanceM = 16_000.0,
            durationSec = 5_400,
            elevationGainM = null,
            avgPaceMinPerKm = 5.6,
        )

    private fun activity(id: Long) =
        ActivityEntity(
            id = id,
            type = ActivityType.RUNNING,
            name = "Bieg",
            startTime = Instant.parse("2026-11-10T06:00:00Z"),
            durationSec = 1_800,
            distanceM = 5_000.0,
            avgHr = 140,
            maxHr = 160,
            elevationGainM = null,
            avgPowerW = null,
            avgCadenceSpm = null,
        )

    private fun bundle(lapCount: Int) =
        ActivityDetailBundle(
            activity = activity(1),
            details = ActivityDetailsEntity(1, detailsAvailable = true, fetchedAt = Instant.parse("2026-11-18T10:00:00Z")),
            laps =
                (1..lapCount).map {
                    LapEntity(1, it, 1000.0, 300.0, 140, 150, 3.33, 5.0, null, null, null)
                },
            hrZones = (1..lapCount).map { HrZoneEntity(1, it, 60.0 * it, 100 + it * 10) },
            exerciseSets =
                listOf(
                    ExerciseSetEntity(1, 0, "ACTIVE", "SQUAT", "BARBELL_SQUAT", 5, 80.0, 30.0),
                    ExerciseSetEntity(1, 1, "REST", null, null, null, null, 90.0),
                ).take(lapCount),
        )
}
