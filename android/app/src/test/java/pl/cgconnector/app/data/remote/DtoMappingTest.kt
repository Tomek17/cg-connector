package pl.cgconnector.app.data.remote

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import pl.cgconnector.app.data.local.SyncStatusEntity
import pl.cgconnector.app.domain.ActivityType
import java.time.Instant
import java.time.LocalDate

/** Mapowanie DTO → encje Room (dane syntetyczne). */
class DtoMappingTest {
    private val fetchedAt = Instant.parse("2026-11-18T13:30:00Z")

    @Test
    fun `detail maps summary fields, laps, zones and sets`() {
        val bundle =
            ActivityDetailDto(
                id = 9,
                type = "strength",
                name = "Siłownia",
                startTime = "2026-11-17T18:00:00+01:00",
                durationSec = 3_600,
                trainingLoad = 40.0,
                detailsAvailable = true,
                laps = listOf(LapDto(index = 1, distanceM = 1000.0, durationSec = 330.0, avgHr = 150)),
                hrZones = listOf(HrZoneDto(zone = 2, seconds = 600.0, lowBpm = 117)),
                exerciseSets =
                    listOf(
                        ExerciseSetDto(setType = "ACTIVE", category = "SQUAT", reps = 5, weightKg = 80.0),
                        ExerciseSetDto(setType = "REST", durationSec = 90.0),
                    ),
            ).toBundle(fetchedAt)

        assertThat(bundle.activity.type).isEqualTo(ActivityType.STRENGTH)
        assertThat(bundle.activity.startTime).isEqualTo(Instant.parse("2026-11-17T17:00:00Z"))
        assertThat(bundle.activity.trainingLoad).isEqualTo(40.0)
        assertThat(bundle.details.activityId).isEqualTo(9)
        assertThat(bundle.details.detailsAvailable).isTrue()
        assertThat(bundle.details.fetchedAt).isEqualTo(fetchedAt)
        assertThat(bundle.laps.single().activityId).isEqualTo(9)
        assertThat(bundle.laps.single().avgHr).isEqualTo(150)
        assertThat(bundle.hrZones.single().lowBpm).isEqualTo(117)
        assertThat(bundle.exerciseSets.map { it.index }).containsExactly(0, 1).inOrder()
        assertThat(bundle.exerciseSets[0].weightKg).isEqualTo(80.0)
        assertThat(bundle.exerciseSets[1].setType).isEqualTo("REST")
    }

    @Test
    fun `duplicate lap and zone numbers do not break primary key`() {
        val bundle =
            ActivityDetailDto(
                id = 1,
                type = "running",
                startTime = "2026-11-17T07:00:00+01:00",
                durationSec = 600,
                laps = listOf(LapDto(index = 1), LapDto(index = 1)),
                hrZones = listOf(HrZoneDto(zone = 1, seconds = 1.0), HrZoneDto(zone = 1, seconds = 2.0)),
            ).toBundle(fetchedAt)

        assertThat(bundle.laps).hasSize(1)
        assertThat(bundle.hrZones).hasSize(1)
    }

    @Test
    fun `vo2max and summary map dates`() {
        val point = Vo2MaxPointDto(date = "2026-11-10", value = 51.2, sport = "running").toEntity()
        assertThat(point.date).isEqualTo(LocalDate.of(2026, 11, 10))

        val rows =
            SummaryDto(
                period = "week",
                from = "2026-11-09",
                to = "2026-11-22",
                items = listOf(SummaryItemDto("2026-11-16", "2026-11-22", "climbing", 1, null, 5_400)),
            ).toEntities()
        assertThat(rows.single().period).isEqualTo("week")
        assertThat(rows.single().periodStart).isEqualTo(LocalDate.of(2026, 11, 16))
        assertThat(rows.single().type).isEqualTo("climbing")
    }

    @Test
    fun `me updates server fields and keeps app refresh fields`() {
        val local = SyncStatusEntity(lastRefreshOutcome = "SUCCESS", lastRefreshSuccessAt = fetchedAt)

        val updated =
            MeDto(
                name = "a",
                displayName = "a",
                garminStatus = "needs_login",
                lastSyncAt = "2026-11-18T14:03:12+01:00",
                lastSyncStatus = "error",
            ).applyTo(local)

        assertThat(updated.serverLastSyncAt).isEqualTo(Instant.parse("2026-11-18T13:03:12Z"))
        assertThat(updated.garminStatus).isEqualTo("needs_login")
        assertThat(updated.serverLastSyncStatus).isEqualTo("error")
        assertThat(updated.lastRefreshSuccessAt).isEqualTo(fetchedAt)
    }

    @Test
    fun `me without sync clears server time`() {
        val updated = MeDto(name = "a", displayName = "a").applyTo(SyncStatusEntity(serverLastSyncAt = fetchedAt))

        assertThat(updated.serverLastSyncAt).isNull()
    }
}
