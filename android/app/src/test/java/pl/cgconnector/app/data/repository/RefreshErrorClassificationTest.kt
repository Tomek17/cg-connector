package pl.cgconnector.app.data.repository

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.SerializationException
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Test
import pl.cgconnector.app.data.remote.NotConfiguredException
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.time.format.DateTimeParseException

class RefreshErrorClassificationTest {
    private fun http(code: Int) = HttpException(Response.error<Any>(code, "{\"detail\":\"x\"}".toResponseBody()))

    private fun classify(e: Exception) = OfflineFirstActivityRepository.classify(e)

    @Test
    fun unauthorized() {
        assertThat(classify(http(401))).isEqualTo(RefreshResult.Unauthorized)
    }

    @Test
    fun networkProblemsAndServerErrorsAreUnavailable() {
        assertThat(classify(IOException("refused"))).isEqualTo(RefreshResult.Unavailable)
        assertThat(classify(UnknownHostException("x.ts.net"))).isEqualTo(RefreshResult.Unavailable)
        assertThat(classify(SocketTimeoutException())).isEqualTo(RefreshResult.Unavailable)
        assertThat(classify(http(502))).isEqualTo(RefreshResult.Unavailable)
        assertThat(classify(http(503))).isEqualTo(RefreshResult.Unavailable)
    }

    @Test
    fun parsingErrorsAndOtherClientErrorsAreInvalidResponse() {
        assertThat(classify(SerializationException("bad json"))).isEqualTo(RefreshResult.InvalidResponse)
        assertThat(classify(DateTimeParseException("bad", "x", 0))).isEqualTo(RefreshResult.InvalidResponse)
        assertThat(classify(http(404))).isEqualTo(RefreshResult.InvalidResponse)
        assertThat(classify(http(422))).isEqualTo(RefreshResult.InvalidResponse)
    }

    @Test
    fun notConfigured() {
        assertThat(classify(NotConfiguredException())).isEqualTo(RefreshResult.NotConfigured)
    }
}
