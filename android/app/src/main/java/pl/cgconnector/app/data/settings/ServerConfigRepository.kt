package pl.cgconnector.app.data.settings

import kotlinx.coroutines.flow.Flow
import pl.cgconnector.app.domain.ServerConfig

interface ServerConfigRepository {
    /** Aktualna konfiguracja albo `null`, jeśli aplikacja nie jest jeszcze skonfigurowana. */
    val config: Flow<ServerConfig?>

    suspend fun save(config: ServerConfig)

    suspend fun clear()
}
