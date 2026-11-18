package pl.cgconnector.app.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import pl.cgconnector.app.data.settings.ServerConfigRepository
import pl.cgconnector.app.domain.ServerConfig

class FakeServerConfigRepository(
    initial: ServerConfig? = null,
) : ServerConfigRepository {
    private val state = MutableStateFlow(initial)
    var failOnSave = false
    val saved = mutableListOf<ServerConfig>()

    override val config: Flow<ServerConfig?> = state

    override suspend fun save(config: ServerConfig) {
        if (failOnSave) throw java.io.IOException("disk full")
        saved += config
        state.value = config
    }

    override suspend fun clear() {
        state.value = null
    }
}
