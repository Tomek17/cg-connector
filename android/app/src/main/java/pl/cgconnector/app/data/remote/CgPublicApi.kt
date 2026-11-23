package pl.cgconnector.app.data.remote

import retrofit2.http.Body
import retrofit2.http.POST

/** Endpointy bez tokenu aplikacji (zanim aplikacja go ma). */
interface CgPublicApi {
    /** Jednorazowy kod zaproszenia `cgi_…` → token aplikacji. 404 = zły, zużyty albo wygasły kod. */
    @POST("api/v1/invite/redeem")
    suspend fun redeemInvite(
        @Body body: InviteRequestDto,
    ): InviteResponseDto
}
