package pl.cgconnector.app.data.onboarding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import pl.cgconnector.app.domain.InviteLink
import javax.inject.Inject
import javax.inject.Singleton

/** Zaproszenie z linku, które czeka na ekran Ustawień (tylko w pamięci, nie zapisywane). */
@Singleton
class PendingInviteStore
    @Inject
    constructor() {
        private val pending = MutableStateFlow<InviteLink?>(null)
        val invite: StateFlow<InviteLink?> = pending.asStateFlow()

        fun offer(invite: InviteLink) {
            pending.value = invite
        }

        fun consume(): InviteLink? = pending.value.also { pending.value = null }
    }
