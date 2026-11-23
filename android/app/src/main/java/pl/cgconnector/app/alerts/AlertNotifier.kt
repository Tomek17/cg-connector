package pl.cgconnector.app.alerts

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import pl.cgconnector.app.MainActivity
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.SyncStatus
import pl.cgconnector.app.domain.alerts.Alert
import pl.cgconnector.app.domain.alerts.AlertRules
import pl.cgconnector.app.domain.alerts.AlertState
import pl.cgconnector.app.domain.workout.QueuedWorkout
import pl.cgconnector.app.ui.format.Formatters
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** Powiadomienia: trening nie dotarł na zegarek, stare dane, Garmin wymaga logowania. */
@Singleton
class AlertNotifier
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val dataStore: DataStore<Preferences>,
    ) {
        private val mutex = Mutex()

        suspend fun check(
            queue: List<QueuedWorkout>,
            status: SyncStatus,
            now: Instant,
        ) = mutex.withLock {
            val prefs = dataStore.data.first()
            val state =
                AlertState(
                    notifiedFailedIds = prefs[KEY_FAILED].orEmpty().mapNotNull { it.toLongOrNull() }.toSet(),
                    staleNotified = prefs[KEY_STALE] ?: false,
                    loginNotified = prefs[KEY_LOGIN] ?: false,
                )
            val (alerts, next) = AlertRules.evaluate(queue, status, now, state)
            if (next != state) {
                dataStore.edit {
                    it[KEY_FAILED] = next.notifiedFailedIds.map(Long::toString).toSet()
                    it[KEY_STALE] = next.staleNotified
                    it[KEY_LOGIN] = next.loginNotified
                }
            }
            alerts.forEach(::post)
        }

        private fun post(alert: Alert) {
            if (!canPost()) return
            ensureChannel()
            val (id, title, text) =
                when (alert) {
                    is Alert.WorkoutFailed ->
                        Triple(
                            ID_WORKOUT_BASE + alert.id.toInt(),
                            context.getString(R.string.alert_workout_failed_title),
                            context.getString(R.string.alert_workout_failed_text, alert.name, DAY.format(alert.date)),
                        )
                    Alert.DataStale ->
                        Triple(ID_STALE, context.getString(R.string.alert_stale_title), context.getString(R.string.alert_stale_text))
                    Alert.GarminLogin ->
                        Triple(ID_LOGIN, context.getString(R.string.alert_login_title), context.getString(R.string.alert_login_text))
                }
            val open =
                PendingIntent.getActivity(
                    context,
                    0,
                    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
            val notification =
                NotificationCompat
                    .Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_nav_today)
                    .setContentTitle(title)
                    .setContentText(text)
                    .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                    .setContentIntent(open)
                    .setAutoCancel(true)
                    .build()
            try {
                NotificationManagerCompat.from(context).notify(id, notification)
            } catch (_: SecurityException) {
                // Uprawnienie cofnięte w międzyczasie: bez powiadomienia.
            }
        }

        private fun canPost(): Boolean =
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
                (
                    Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                        PackageManager.PERMISSION_GRANTED
                )

        private fun ensureChannel() {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) != null) return
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, context.getString(R.string.alert_channel), NotificationManager.IMPORTANCE_DEFAULT),
            )
        }

        private companion object {
            const val CHANNEL = "alerts"
            const val ID_STALE = 1
            const val ID_LOGIN = 2
            const val ID_WORKOUT_BASE = 1000
            val KEY_FAILED = stringSetPreferencesKey("alerts_failed_ids")
            val KEY_STALE = booleanPreferencesKey("alerts_stale")
            val KEY_LOGIN = booleanPreferencesKey("alerts_login")
            val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM", Formatters.POLISH)
        }
    }
