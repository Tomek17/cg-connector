package pl.cgconnector.app.data.remote

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * Propozycja kontraktu REST serwera CG (opis w android/README.md).
 * Uwierzytelnienie: nagłówek `Authorization: Bearer <token aplikacji>` dodawany przez [AuthInterceptor].
 */
interface CgApi {
    /** Profil użytkownika wynikający z tokenu. */
    @GET("api/v1/me")
    suspend fun me(): MeDto

    /**
     * Podsumowania aktywności z przedziału dat (włącznie), daty w formacie ISO-8601 `YYYY-MM-DD`
     * w strefie Europe/Warsaw.
     */
    @GET("api/v1/activities")
    suspend fun activities(
        @Query("from") from: String,
        @Query("to") to: String,
    ): ActivitiesResponseDto
}
