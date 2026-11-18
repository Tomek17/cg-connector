package pl.cgconnector.app.domain

/** Konfiguracja połączenia z serwerem CG. */
data class ServerConfig(
    /** Znormalizowany adres bazowy, zawsze `https://`, bez końcowego `/`. */
    val serverUrl: String,
    val token: String,
) {
    // Token nie może trafić do logów przez przypadkowe toString().
    override fun toString(): String = "ServerConfig(serverUrl=$serverUrl, token=***)"
}
