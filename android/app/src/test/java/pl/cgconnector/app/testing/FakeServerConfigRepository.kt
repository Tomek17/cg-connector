package pl.cgconnector.app.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import pl.cgconnector.app.data.settings.ServerConfigRepository
import pl.cgconnector.app.domain.ConfigState
import pl.cgconnector.app.domain.ServerConfig

class FakeServerConfigRepository(
    initial: ConfigState = ConfigState.NotConfigured,
) : ServerConfigRepository {
    constructor(config: ServerConfig) : this(ConfigState.Configured(config))

    private val mutableState = MutableStateFlow(initial)
    var failOnSave = false
    val saved = mutableListOf<ServerConfig>()

    override val state: Flow<ConfigState> = mutableState

    override suspend fun save(config: ServerConfig) {
        if (failOnSave) throw java.io.IOException("disk full")
        saved += config
        mutableState.value = ConfigState.Configured(config)
    }

    override suspend fun clear() {
        mutableState.value = ConfigState.NotConfigured
    }
}
