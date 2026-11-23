package pl.cgconnector.app.domain

import java.net.URI
import java.net.URLDecoder

/** Zaproszenie z linku `cgconnector://join?server=https://…&code=cgi_…` (strona /join serwera). */
data class InviteLink(
    val serverUrl: String,
    val code: String,
) {
    companion object {
        private val CODE = Regex("cgi_[A-Za-z0-9_-]{20,80}")

        /** `null`, gdy to nie jest poprawny link zaproszenia (adres sprawdza potem ConfigValidator). */
        fun parse(link: String?): InviteLink? {
            if (link.isNullOrBlank()) return null
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            if (uri.scheme != "cgconnector" || uri.host != "join") return null
            val params =
                uri.rawQuery
                    .orEmpty()
                    .split("&")
                    .mapNotNull { part ->
                        val i = part.indexOf('=')
                        if (i <= 0) null else part.substring(0, i) to URLDecoder.decode(part.substring(i + 1), "UTF-8")
                    }.toMap()
            val server = params["server"]?.trim().orEmpty()
            val code = params["code"]?.trim().orEmpty()
            if (!server.startsWith("https://") || !CODE.matches(code)) return null
            return InviteLink(server, code)
        }
    }
}
