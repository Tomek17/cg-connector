package pl.cgconnector.app.data.repository

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import kotlinx.coroutines.flow.first
import java.time.Instant
import javax.inject.Inject

/** „Wstrzymane do” po 429/408 z `Retry-After`: kolejka nie wysyła nic wcześniej, nawet po ponownym wyzwoleniu. */
interface QueuePauseStore {
    suspend fun pausedUntil(): Instant?

    suspend fun pauseUntil(until: Instant)
}

class DataStoreQueuePauseStore
    @Inject
    constructor(
        private val dataStore: DataStore<Preferences>,
    ) : QueuePauseStore {
        override suspend fun pausedUntil(): Instant? = dataStore.data.first()[KEY]?.let(Instant::ofEpochMilli)

        override suspend fun pauseUntil(until: Instant) {
            dataStore.edit { it[KEY] = until.toEpochMilli() }
        }

        private companion object {
            val KEY = longPreferencesKey("workout_queue_paused_until")
        }
    }
