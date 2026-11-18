package pl.cgconnector.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import pl.cgconnector.app.ui.navigation.CgNavHost
import pl.cgconnector.app.ui.theme.CgTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CgTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val startState by viewModel.startState.collectAsStateWithLifecycle()
                    when (val state = startState) {
                        StartState.Loading -> Unit
                        is StartState.Ready -> CgNavHost(isConfigured = state.isConfigured)
                    }
                }
            }
        }
    }
}
