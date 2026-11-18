package pl.cgconnector.app.data.remote

import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import javax.inject.Inject

/**
 * Dane syntetyczne do czasu, aż serwer udostępni `/api/v1/activities` (M1).
 * Deterministyczne, bez prawdziwych danych zdrowotnych.
 */
class FakeActivityRemoteDataSource
    @Inject
    constructor() : ActivityRemoteDataSource {
        override suspend fun fetchActivities(
            from: LocalDate,
            to: LocalDate,
        ): List<ActivityDto> {
            delay(SIMULATED_LATENCY_MS)
            return generateSequence(to) { it.minusDays(1) }
                .takeWhile { !it.isBefore(from) }
                .take(MAX_DAYS)
                .mapIndexedNotNull { index, day -> sampleFor(index, day) }
                .toList()
        }

        private fun sampleFor(
            index: Int,
            day: LocalDate,
        ): ActivityDto? {
            val start = ZonedDateTime.of(day, LocalTime.of(7, 0), ZONE).toOffsetDateTime().toString()
            val id = day.toEpochDay()
            return when (index % 7) {
                0, 3 ->
                    ActivityDto(
                        id = id,
                        type = "running",
                        name = "Bieg spokojny",
                        startTime = start,
                        durationSec = 2_700L + index * 10,
                        distanceM = 8_000.0 + index * 25,
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

        private companion object {
            val ZONE: ZoneId = ZoneId.of("Europe/Warsaw")
            const val SIMULATED_LATENCY_MS = 300L
            const val MAX_DAYS = 60
        }
    }
