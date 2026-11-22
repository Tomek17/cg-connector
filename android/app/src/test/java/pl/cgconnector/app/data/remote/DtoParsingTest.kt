package pl.cgconnector.app.data.remote

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.ActivityType
import java.time.Instant

/** Przykłady w formacie z docs/api/openapi.json (dane syntetyczne). */
class DtoParsingTest {
    private val json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
        }

    @Test
    fun `parses me`() {
        val me =
            json.decodeFromString<MeDto>(
                """
                {"name": "user_a", "display_name": "user_a", "garmin_status": "ok",
                 "last_sync_at": "2026-11-18T14:03:12+01:00", "last_sync_attempt_at": "2026-11-18T14:00:00+01:00",
                 "last_sync_status": "ok", "latest_activity_date": "2026-11-17"}
                """,
            )

        assertThat(me.displayName).isEqualTo("user_a")
        assertThat(me.garminStatus).isEqualTo("ok")
        assertThat(me.lastSyncAt).isEqualTo("2026-11-18T14:03:12+01:00")
        assertThat(me.latestActivityDate).isEqualTo("2026-11-17")
    }

    @Test
    fun `parses me with nulls`() {
        val me =
            json.decodeFromString<MeDto>(
                """
                {"name": "b", "display_name": "b", "garmin_status": null, "last_sync_at": null,
                 "last_sync_attempt_at": null, "last_sync_status": null, "latest_activity_date": null}
                """,
            )

        assertThat(me.lastSyncAt).isNull()
        assertThat(me.garminStatus).isNull()
    }

    @Test
    fun `parses activity list and maps to entity`() {
        val list =
            json.decodeFromString<ActivityListDto>(
                """
                {"items": [{"id": 18234567890, "type": "running", "type_key": "trail_running", "name": "Bieg",
                  "start_time": "2026-11-17T07:30:00+01:00", "duration_sec": 2700, "moving_duration_sec": 2650,
                  "distance_m": 8012.5, "avg_hr": 142, "max_hr": 158, "elevation_gain_m": 45.0,
                  "avg_power_w": 265, "avg_cadence_spm": 172, "avg_speed_mps": 2.97, "pace_min_per_km": 5.62,
                  "aerobic_training_effect": 3.1, "anaerobic_training_effect": 0.4, "training_load": 85.0,
                  "future_field": {"x": 1}}],
                 "has_more": false}
                """,
            )

        val dto = list.items.single()
        assertThat(list.hasMore).isFalse()
        assertThat(dto.typeKey).isEqualTo("trail_running")
        assertThat(dto.paceMinPerKm).isEqualTo(5.62)
        val entity = dto.toEntity()
        assertThat(entity.id).isEqualTo(18234567890L)
        assertThat(entity.type).isEqualTo(ActivityType.RUNNING)
        assertThat(entity.startTime).isEqualTo(Instant.parse("2026-11-17T06:30:00Z"))
        assertThat(entity.distanceM).isEqualTo(8012.5)
    }

    @Test
    fun `strength activity with null metrics and unknown type`() {
        val list =
            json.decodeFromString<ActivityListDto>(
                """
                {"items": [
                  {"id": 1, "type": "strength", "type_key": "strength_training", "name": null,
                   "start_time": "2026-07-01T18:00:00+02:00", "duration_sec": 0, "moving_duration_sec": null,
                   "distance_m": null, "avg_hr": null, "max_hr": null, "elevation_gain_m": null, "avg_power_w": null,
                   "avg_cadence_spm": null, "avg_speed_mps": null, "pace_min_per_km": null,
                   "aerobic_training_effect": null, "anaerobic_training_effect": null, "training_load": null},
                  {"id": 2, "type": "paragliding", "type_key": "x", "name": "?", "start_time": "2026-07-02T10:00:00+02:00",
                   "duration_sec": 60}
                 ], "has_more": true}
                """,
            )

        assertThat(list.hasMore).isTrue()
        assertThat(list.items[0].toEntity().type).isEqualTo(ActivityType.STRENGTH)
        assertThat(list.items[0].toEntity().startTime).isEqualTo(Instant.parse("2026-07-01T16:00:00Z"))
        assertThat(list.items[1].toEntity().type).isEqualTo(ActivityType.OTHER)
    }

    @Test
    fun `parses activity detail`() {
        val detail =
            json.decodeFromString<ActivityDetailDto>(
                """
                {"id": 5, "type": "strength", "type_key": "strength_training", "name": "Siłownia",
                 "start_time": "2026-11-16T18:00:00+01:00", "duration_sec": 3600, "moving_duration_sec": null,
                 "distance_m": null, "avg_hr": 110, "max_hr": 141, "elevation_gain_m": null, "avg_power_w": null,
                 "avg_cadence_spm": null, "avg_speed_mps": null, "pace_min_per_km": null,
                 "aerobic_training_effect": 2.0, "anaerobic_training_effect": 1.1, "training_load": 40.0,
                 "details_available": true,
                 "laps": [{"index": 1, "distance_m": 1000.0, "duration_sec": 300.5, "avg_hr": 150, "max_hr": 160,
                           "avg_speed_mps": 3.33, "pace_min_per_km": 5.0, "elevation_gain_m": 3.0,
                           "avg_power_w": 280, "avg_cadence_spm": 176}],
                 "hr_zones": [{"zone": 1, "seconds": 120.0, "low_bpm": 98}, {"zone": 2, "seconds": 600.0, "low_bpm": null}],
                 "exercise_sets": [{"set_type": "ACTIVE", "category": "BENCH_PRESS", "exercise": "BARBELL_BENCH_PRESS",
                                    "reps": 5, "weight_kg": 80.0, "duration_sec": 35.0},
                                   {"set_type": "REST", "category": null, "exercise": null, "reps": null,
                                    "weight_kg": null, "duration_sec": 90.0}]}
                """,
            )

        assertThat(detail.detailsAvailable).isTrue()
        assertThat(detail.laps.single().durationSec).isEqualTo(300.5)
        assertThat(detail.hrZones.map { it.zone }).containsExactly(1, 2).inOrder()
        assertThat(detail.hrZones[1].lowBpm).isNull()
        assertThat(detail.exerciseSets[0].reps).isEqualTo(5)
        assertThat(detail.exerciseSets[0].weightKg).isEqualTo(80.0)
        assertThat(detail.exerciseSets[1].setType).isEqualTo("REST")
    }

    @Test
    fun `parses activity detail without details`() {
        val detail =
            json.decodeFromString<ActivityDetailDto>(
                """
                {"id": 6, "type": "climbing", "type_key": "indoor_climbing", "name": null,
                 "start_time": "2026-11-15T19:00:00+01:00", "duration_sec": 5400, "details_available": false,
                 "laps": [], "hr_zones": [], "exercise_sets": []}
                """,
            )

        assertThat(detail.detailsAvailable).isFalse()
        assertThat(detail.laps).isEmpty()
    }

    @Test
    fun `parses vo2max`() {
        val list =
            json.decodeFromString<Vo2MaxListDto>(
                """
                {"items": [{"date": "2026-11-01", "value": 52.0, "sport": "running"},
                           {"date": "2026-11-08", "value": 52.5, "sport": "cycling"}]}
                """,
            )

        assertThat(list.items.map { it.value }).containsExactly(52.0, 52.5).inOrder()
        assertThat(list.items[1].sport).isEqualTo("cycling")
    }

    @Test
    fun `parses summary`() {
        val summary =
            json.decodeFromString<SummaryDto>(
                """
                {"period": "week", "from": "2026-11-02", "to": "2026-11-15",
                 "items": [{"period_start": "2026-11-02", "period_end": "2026-11-08", "type": "running", "count": 3,
                            "distance_m": 30000.0, "duration_sec": 10800, "elevation_gain_m": 200.0,
                            "avg_pace_min_per_km": 6.0},
                           {"period_start": "2026-11-02", "period_end": "2026-11-08", "type": "strength", "count": 2,
                            "distance_m": null, "duration_sec": 7200, "elevation_gain_m": null,
                            "avg_pace_min_per_km": null}]}
                """,
            )

        assertThat(summary.period).isEqualTo("week")
        assertThat(summary.items).hasSize(2)
        assertThat(summary.items[0].avgPaceMinPerKm).isEqualTo(6.0)
        assertThat(summary.items[1].distanceM).isNull()
    }

    @Test
    fun `parses progress and skips unknown tags`() {
        val tags =
            json.decodeFromString<ProgressTagsDto>(
                """
                {"tags": [{"tag": "intervals", "type": "running", "count": 3, "last_date": "2026-11-20"},
                          {"tag": "future", "type": "running", "count": 1, "last_date": "2026-11-20"}],
                 "available": {"intervals": "running"}}
                """,
            ).toDomain()
        assertThat(tags.map { it.tag }).containsExactly(ActivityTag.INTERVALS)
        assertThat(tags.single().count).isEqualTo(3)

        val progress =
            json.decodeFromString<TagProgressDto>(
                """
                {"tag": "legs", "type": "strength", "running": [],
                 "strength": [{"category": "SQUAT", "exercise": "BARBELL_BACK_SQUAT", "sessions": 1,
                   "sets": [{"activity_id": 5, "date": "2026-11-19", "set_number": 1, "weight_kg": 82.5, "reps": 5}]}]}
                """,
            ).toDomain()!!
        assertThat(progress.tag).isEqualTo(ActivityTag.LEGS)
        val set = progress.strength.single().sets.single()
        assertThat(set.weightKg).isEqualTo(82.5)
        assertThat(set.date).isEqualTo(java.time.LocalDate.of(2026, 11, 19))
    }

    @Test
    fun `null tag is left out of the request body`() {
        assertThat(json.encodeToString(TagUpdateDto.serializer(), TagUpdateDto(null))).isEqualTo("{}")
        assertThat(json.encodeToString(TagUpdateDto.serializer(), TagUpdateDto("legs"))).isEqualTo("""{"tag":"legs"}""")
    }
}
