package pl.cgconnector.app.data.remote

import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
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

    /** 201 = utworzony, 200 = ten `client_id` już był (idempotencja). 422 = zła definicja. */
    @POST("api/v1/workouts")
    suspend fun createWorkout(
        @Body body: NewWorkoutDto,
    ): WorkoutOutDto

    /** Dodaje trening do outboxu na dany dzień; 404 = brak treningu, 422 = data w przeszłości / za daleko. */
    @POST("api/v1/workouts/{id}/schedule")
    suspend fun scheduleWorkout(
        @Path("id") workoutId: Long,
        @Body body: ScheduleRequestDto,
    ): ScheduledOutDto

    /** Domyślnie od dziś przez 30 dni; zakres maks. [MAX_SCHEDULED_DAYS] dni. */
    @GET("api/v1/scheduled")
    suspend fun scheduled(
        @Query("from") from: String? = null,
        @Query("to") to: String? = null,
    ): ScheduledListDto

    /** Usuwa wyłącznie wpis outboxu CG-connector: `deleted` od razu albo `deleting`, gdy jest już w Garminie. */
    @DELETE("api/v1/scheduled/{id}")
    suspend fun deleteScheduled(
        @Path("id") scheduledId: Long,
    ): ScheduledOutDto

    /** Ustawia albo (`tag = null`) usuwa tag aktywności. 404 = brak aktywności, 422 = tag nie pasuje do rodzaju. */
    @PUT("api/v1/activities/{id}/tag")
    suspend fun setActivityTag(
        @Path("id") id: Long,
        @Body body: TagUpdateDto,
    ): ActivityDto

    /** Tagi w użyciu (liczba aktywności, ostatni dzień). */
    @GET("api/v1/progress")
    suspend fun progressTags(): ProgressTagsDto

    /** Dane do wykresów aktywności z tagiem (od najstarszej). 404 = nieznany tag. */
    @GET("api/v1/progress/{tag}")
    suspend fun tagProgress(
        @Path("tag") tag: String,
    ): TagProgressDto

    /** Synchronizacja z Garminem teraz (serwer robi ją w tle; wynik w `/me`). */
    @POST("api/v1/sync")
    suspend fun syncNow(): SyncNowDto

    /** Cel użytkownika albo `goal: null`. */
    @GET("api/v1/goal")
    suspend fun goal(): GoalStateDto

    /** Ustawia (zastępuje) cel; 422 = zły cel. */
    @PUT("api/v1/goal")
    suspend fun setGoal(
        @Body body: GoalDto,
    ): GoalStateDto

    @DELETE("api/v1/goal")
    suspend fun deleteGoal(): GoalStateDto

    companion object {
        const val MAX_SCHEDULED_DAYS = 366L
        const val MAX_LIMIT = 2000
    }
}
