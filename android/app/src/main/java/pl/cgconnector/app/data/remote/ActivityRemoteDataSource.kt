package pl.cgconnector.app.data.remote

import java.time.LocalDate

/** Źródło danych o aktywnościach po stronie serwera. */
interface ActivityRemoteDataSource {
    /** Rzuca wyjątek, gdy serwer jest niedostępny; wywołujący traktuje to jako normalny stan. */
    suspend fun fetchActivities(
        from: LocalDate,
        to: LocalDate,
    ): List<ActivityDto>
}

class NotConfiguredException : IllegalStateException("Server is not configured")
