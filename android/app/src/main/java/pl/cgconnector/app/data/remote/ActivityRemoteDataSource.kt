package pl.cgconnector.app.data.remote

import java.time.LocalDate

/**
 * Źródło danych po stronie serwera. Każda metoda rzuca wyjątek, gdy serwer jest niedostępny;
 * wywołujący traktuje to jako normalny stan.
 */
interface ActivityRemoteDataSource {
    suspend fun fetchActivities(
        from: LocalDate,
        to: LocalDate,
    ): List<ActivityDto>

    suspend fun fetchActivityDetail(id: Long): ActivityDetailDto

    suspend fun fetchVo2Max(
        from: LocalDate,
        to: LocalDate,
    ): List<Vo2MaxPointDto>

    suspend fun fetchMe(): MeDto

    /** Domyślne okno serwera (12 ostatnich tygodni). */
    suspend fun fetchWeeklySummary(): SummaryDto
}

class NotConfiguredException : IllegalStateException("Server is not configured")
