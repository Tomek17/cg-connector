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

@RunWith(AndroidJUnit4::class)
class ActivityDaoTest {
    private lateinit var db: CgDatabase
    private lateinit var dao: ActivityDao

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, CgDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        dao = db.activityDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun observeActivities_returnsNewestFirst() =
        runTest {
            dao.upsertAll(
                listOf(
                    entity(1, ActivityType.RUNNING, "2026-11-01T07:00:00Z"),
                    entity(2, ActivityType.STRENGTH, "2026-11-03T07:00:00Z"),
                    entity(3, ActivityType.CLIMBING, "2026-11-02T07:00:00Z"),
                ),
            )

            val ids = dao.observeActivities(null).first().map { it.id }

            assertThat(ids).containsExactly(2L, 3L, 1L).inOrder()
        }

    @Test
    fun observeActivities_filtersByType() =
        runTest {
            dao.upsertAll(
                listOf(
                    entity(1, ActivityType.RUNNING, "2026-11-01T07:00:00Z"),
                    entity(2, ActivityType.STRENGTH, "2026-11-02T07:00:00Z"),
                    entity(3, ActivityType.RUNNING, "2026-11-03T07:00:00Z"),
                ),
            )

            val runs = dao.observeActivities(ActivityType.RUNNING).first()

            assertThat(runs.map { it.id }).containsExactly(3L, 1L).inOrder()
            assertThat(runs.all { it.type == ActivityType.RUNNING }).isTrue()
        }

    @Test
    fun upsertAll_replacesExistingActivity() =
        runTest {
            dao.upsertAll(listOf(entity(1, ActivityType.RUNNING, "2026-11-01T07:00:00Z", name = "Stara")))
            dao.upsertAll(listOf(entity(1, ActivityType.RUNNING, "2026-11-01T07:00:00Z", name = "Nowa")))

            assertThat(dao.count()).isEqualTo(1)
            assertThat(dao.getById(1)?.name).isEqualTo("Nowa")
        }

    @Test
    fun latestStartTime_isNullForEmptyDbAndMaxOtherwise() =
        runTest {
            assertThat(dao.latestStartTime()).isNull()

            dao.upsertAll(
                listOf(
                    entity(1, ActivityType.RUNNING, "2026-11-01T07:00:00Z"),
                    entity(2, ActivityType.OTHER, "2026-11-05T18:30:00Z"),
                ),
            )

            assertThat(dao.latestStartTime()).isEqualTo(Instant.parse("2026-11-05T18:30:00Z"))
        }

    @Test
    fun roundTripsAllFields() =
        runTest {
            val original =
                ActivityEntity(
                    id = 42,
                    type = ActivityType.RUNNING,
                    name = "Interwały",
                    startTime = Instant.parse("2026-11-10T06:15:30Z"),
                    durationSec = 3_300,
                    distanceM = 10_500.5,
                    avgHr = 155,
                    maxHr = 178,
                    elevationGainM = 31.2,
                    avgPowerW = 300,
                    avgCadenceSpm = 180,
                )
            dao.upsertAll(listOf(original))

            assertThat(dao.getById(42)).isEqualTo(original)
        }

    private fun entity(
        id: Long,
        type: ActivityType,
        start: String,
        name: String? = null,
    ) = ActivityEntity(
        id = id,
        type = type,
        name = name,
        startTime = Instant.parse(start),
        durationSec = 1_800,
        distanceM = null,
        avgHr = null,
        maxHr = null,
        elevationGainM = null,
        avgPowerW = null,
        avgCadenceSpm = null,
    )
}
