package pl.cgconnector.app.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/** Dodaje token aplikacji. Nigdy nie logujemy nagłówka Authorization. */
class AuthInterceptor(
    private val token: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response =
        chain.proceed(
            chain
                .request()
                .newBuilder()
                .header("Authorization", "Bearer $token")
                .build(),
        )
}
