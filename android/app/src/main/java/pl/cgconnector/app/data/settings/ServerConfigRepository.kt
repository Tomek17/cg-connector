package pl.cgconnector.app.data.settings

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import pl.cgconnector.app.domain.ConfigState
import pl.cgconnector.app.domain.ServerConfig

interface ServerConfigRepository {
    val state: Flow<ConfigState>

    /** Pełna konfiguracja (adres + token) albo `null`, gdy nie da się połączyć z serwerem. */
    val config: Flow<ServerConfig?>
        get() = state.map { (it as? ConfigState.Configured)?.config }

    suspend fun save(config: ServerConfig)

    suspend fun clear()
}
