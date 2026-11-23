package pl.cgconnector.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import pl.cgconnector.app.R
import pl.cgconnector.app.data.onboarding.PendingInviteStore
import pl.cgconnector.app.domain.InviteLink
import pl.cgconnector.app.ui.navigation.CgNavHost
import pl.cgconnector.app.ui.theme.CgTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()
    @Inject lateinit var pendingInvite: PendingInviteStore

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) acceptInvite(intent)
        askForNotifications(savedInstanceState)
        setContent {
            CgTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val startState by viewModel.startState.collectAsStateWithLifecycle()
                    val loading = stringResource(R.string.app_loading)
                    when (val state = startState) {
                        StartState.Loading ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.semantics { contentDescription = loading },
                                )
                            }
                        is StartState.Ready -> CgNavHost(start = state.start)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        acceptInvite(intent)
    }

    /** Link zaproszenia: adres i kod czekają na kreator pierwszego uruchomienia (albo ekran Ustawień). */
    private fun acceptInvite(intent: Intent?) {
        if (intent?.action != Intent.ACTION_VIEW) return
        InviteLink.parse(intent.dataString)?.let(pendingInvite::offer)
    }

    /** Raz, przy pierwszym uruchomieniu: zgoda na powiadomienia (trening nie dotarł, stare dane). Odmowa nic nie psuje. */
    private fun askForNotifications(savedInstanceState: Bundle?) {
        if (savedInstanceState != null || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted =
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (granted || prefs.getBoolean(KEY_ASKED, false)) return
        prefs.edit { putBoolean(KEY_ASKED, true) }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private companion object {
        const val PREFS = "main_activity"
        const val KEY_ASKED = "notifications_asked"
    }
}
