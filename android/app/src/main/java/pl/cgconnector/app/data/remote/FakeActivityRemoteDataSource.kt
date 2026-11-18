package pl.cgconnector.app.data.remote

import kotlinx.coroutines.delay
import java.io.IOException
import java.time.Clock
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject
import kotlin.math.sin

/**
 * Dane syntetyczne dla buildu debug (`USE_FAKE_API = true`) i do pracy bez serwera.
 * Deterministyczne, bez prawdziwych danych zdrowotnych. ID aktywności = numer dnia (epoch day).
 */
class FakeActivityRemoteDataSource
    @Inject
    constructor(
        private val clock: Clock,
    ) : ActivityRemoteDataSource {
        override suspend fun fetchActivities(
            from: LocalDate,
            to: LocalDate,
        ): List<ActivityDto> {
            delay(SIMULATED_LATENCY_MS)
            return generateSequence(to) { it.minusDays(1) }
                .takeWhile { !it.isBefore(from) }
                .take(MAX_DAYS)
                .mapNotNull { day -> sampleFor(day) }
                .toList()
        }

        override suspend fun fetchActivityDetail(id: Long): ActivityDetailDto {
            delay(SIMULATED_LATENCY_MS)
            val summary = sampleFor(LocalDate.ofEpochDay(id)) ?: throw IOException("Fake: no activity $id")
            return ActivityDetailDto(
                id = summary.id,
                type = summary.type,
                name = summary.name,
                startTime = summary.startTime,
                durationSec = summary.durationSec,
                distanceM = summary.distanceM,
                avgHr = summary.avgHr,
                maxHr = summary.maxHr,
                elevationGainM = summary.elevationGainM,
                avgPowerW = summary.avgPowerW,
                avgCadenceSpm = summary.avgCadenceSpm,
                paceMinPerKm = summary.distanceM?.let { summary.durationSec / 60.0 / (it / 1000.0) },
                detailsAvailable = true,
                laps = if (summary.type == "running") laps(summary) else emptyList(),
                hrZones = hrZones(summary.durationSec),
                exerciseSets = if (summary.type == "strength") strengthSets() else emptyList(),
            )
        }

        override suspend fun fetchVo2Max(
            from: LocalDate,
            to: LocalDate,
        ): List<Vo2MaxPointDto> {
            delay(SIMULATED_LATENCY_MS)
            return generateSequence(to) { it.minusDays(VO2_EVERY_DAYS) }
                .takeWhile { !it.isBefore(from) }
                .take(MAX_DAYS)
                .map { day ->
                    val wave = sin(day.toEpochDay() / 20.0)
                    Vo2MaxPointDto(date = day.toString(), value = 50.0 + Math.round(wave * 20) / 10.0, sport = "running")
                }.toList()
        }

        override suspend fun fetchMe(): MeDto {
            val now = ZonedDateTime.now(clock.withZone(ZONE))
            val todaySync = ZonedDateTime.of(now.toLocalDate(), SERVER_SYNC_AT, ZONE)
            val lastSync = if (now.isBefore(todaySync)) todaySync.minusDays(1) else todaySync
            return MeDto(
                name = "demo",
                displayName = "Demo",
                garminStatus = "ok",
                lastSyncAt = lastSync.toOffsetDateTime().toString(),
                lastSyncAttemptAt = lastSync.toOffsetDateTime().toString(),
                lastSyncStatus = "ok",
                latestActivityDate = now.toLocalDate().toString(),
            )
        }

        override suspend fun fetchWeeklySummary(): SummaryDto {
            val today = LocalDate.now(clock.withZone(ZONE))
            val monday = today.minusDays(today.dayOfWeek.value - 1L)
            val sunday = monday.plusDays(6)
            val runs = generateSequence(monday) { it.plusDays(1) }.take(7).mapNotNull { sampleFor(it) }
            val items =
                runs
                    .groupBy { it.type }
                    .map { (type, list) ->
                        SummaryItemDto(
                            periodStart = monday.toString(),
                            periodEnd = sunday.toString(),
                            type = type,
                            count = list.size,
                            distanceM = list.mapNotNull { it.distanceM }.takeIf { it.isNotEmpty() }?.sum(),
                            durationSec = list.sumOf { it.durationSec },
                        )
                    }
            return SummaryDto(period = "week", from = monday.toString(), to = sunday.toString(), items = items)
        }

        private fun sampleFor(day: LocalDate): ActivityDto? {
            val start = ZonedDateTime.of(day, LocalTime.of(7, 0), ZONE).toOffsetDateTime().toString()
            val id = day.toEpochDay()
            val variant = (id % 100).toInt()
            return when (Math.floorMod(id, 7L).toInt()) {
                0, 3 ->
                    ActivityDto(
                        id = id,
                        type = "running",
                        name = "Bieg spokojny",
                        startTime = start,
                        durationSec = 2_700L + variant * 10,
                        distanceM = 8_000.0 + variant * 25,
                        avgHr = 142,
                        maxHr = 158,
                        elevationGainM = 45.0,
                        avgPowerW = 265,
                        avgCadenceSpm = 172,
                    )
                1 ->
                    ActivityDto(
                        id = id,
                        type = "strength",
                        name = "Siłownia: góra",
                        startTime = start,
                        durationSec = 3_600L,
                        avgHr = 110,
                        maxHr = 141,
                    )
                5 ->
                    ActivityDto(
                        id = id,
                        type = "running",
                        name = "Interwały 6×800 m",
                        startTime = start,
                        durationSec = 3_300L,
                        distanceM = 10_500.0,
                        avgHr = 155,
                        maxHr = 178,
                        elevationGainM = 30.0,
                        avgPowerW = 300,
                        avgCadenceSpm = 180,
                    )
                4 ->
                    ActivityDto(
                        id = id,
                        type = "climbing",
                        name = "Ścianka",
                        startTime = start,
                        durationSec = 5_400L,
                        avgHr = 118,
                        maxHr = 160,
                    )
                else -> null
            }
        }

        /** Okrążenia co 1 km, ostatnie krótsze; tempo lekko zmienne. */
        private fun laps(summary: ActivityDto): List<LapDto> {
            val distance = summary.distanceM ?: return emptyList()
            val secPerMeter = summary.durationSec / distance
            val full = (distance / 1000).toInt()
            val rest = distance - full * 1000
            val lengths = List(full) { 1000.0 } + listOfNotNull(rest.takeIf { it >= 1 })
            return lengths.mapIndexed { i, meters ->
                val factor = 1 + (if (i % 2 == 0) -0.02 else 0.02)
                val seconds = meters * secPerMeter * factor
                LapDto(
                    index = i + 1,
                    distanceM = meters,
                    durationSec = seconds,
                    avgHr = (summary.avgHr ?: 140) + i % 3,
                    maxHr = summary.maxHr,
                    avgSpeedMps = meters / seconds,
                    paceMinPerKm = seconds / 60.0 / (meters / 1000.0),
                )
            }
        }

        private fun hrZones(durationSec: Long): List<HrZoneDto> {
            val shares = listOf(0.1, 0.35, 0.35, 0.15, 0.05)
            val lows = listOf(98, 117, 137, 156, 176)
            return shares.mapIndexed { i, share -> HrZoneDto(zone = i + 1, seconds = durationSec * share, lowBpm = lows[i]) }
        }

        private fun strengthSets(): List<ExerciseSetDto> =
            listOf(
                ExerciseSetDto("ACTIVE", "BENCH_PRESS", "BARBELL_BENCH_PRESS", 8, 60.0, 40.0),
                ExerciseSetDto("REST", null, null, null, null, 90.0),
                ExerciseSetDto("ACTIVE", "BENCH_PRESS", "BARBELL_BENCH_PRESS", 8, 60.0, 42.0),
                ExerciseSetDto("REST", null, null, null, null, 90.0),
                ExerciseSetDto("ACTIVE", "ROW", "DUMBBELL_ROW", 10, 22.5, 45.0),
                ExerciseSetDto("REST", null, null, null, null, 90.0),
                ExerciseSetDto("ACTIVE", "PULL_UP", "PULL_UP", 6, null, 30.0),
            )

        private companion object {
            val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
            val SERVER_SYNC_AT: LocalTime = LocalTime.of(14, 3)
            const val SIMULATED_LATENCY_MS = 300L
            const val MAX_DAYS = 60
            const val VO2_EVERY_DAYS = 3L
        }
    }
