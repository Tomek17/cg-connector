package pl.cgconnector.app.testing

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import pl.cgconnector.app.data.onboarding.SetupProgressStore
import pl.cgconnector.app.data.onboarding.SetupStep

class FakeSetupProgressStore(
    initial: SetupStep? = null,
) : SetupProgressStore {
    val current = MutableStateFlow(initial)

    override val step: Flow<SetupStep?> = current

    override suspend fun save(step: SetupStep) {
        current.value = step
    }

    override suspend fun finish() {
        current.value = null
    }
}
