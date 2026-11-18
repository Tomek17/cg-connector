package pl.cgconnector.app.data.remote

import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * REST serwera CG zgodnie z `docs/api/openapi.json`.
 * Uwierzytelnienie: `Authorization: Bearer <token aplikacji>` dodawany przez [AuthInterceptor].
 * Daty (`from`, `to`) to dni kalendarzowe Europe/Warsaw w formacie `YYYY-MM-DD`, oba końce włącznie;
 * `null` = wartość domyślna serwera. Błędy: 401 (token), 404 (brak aktywności), 422 (złe parametry).
 */
interface CgApi {
    @GET("api/v1/me")
    suspend fun me(): MeDto

    /** Od najnowszej. Domyślnie ostatnie 365 dni, `limit` domyślnie i maks. [MAX_LIMIT]. */
    @GET("api/v1/activities")
    suspend fun activities(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
        @Query("type") type: String? = null,
        @Query("limit") limit: Int? = null,
    ): ActivityListDto

    @GET("api/v1/activities/{id}")
    suspend fun activity(
        @Path("id") id: Long,
    ): ActivityDetailDto

    /** Domyślnie ostatnie 365 dni, wszystkie sporty (pole `sport`). */
    @GET("api/v1/vo2max")
    suspend fun vo2max(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): Vo2MaxListDto

    /** `period` = `week` | `month`; domyślnie 12 ostatnich okresów, maks. 2 lata. */
    @GET("api/v1/summary")
    suspend fun summary(
        @Query("period") period: String = "week",
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): SummaryDto

    companion object {
        const val MAX_LIMIT = 2000
    }
}
