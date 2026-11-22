package pl.cgconnector.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import pl.cgconnector.app.R
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
                    val loading = stringResource(R.string.app_loading)
                    when (val state = startState) {
                        StartState.Loading ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(
                                    modifier = Modifier.semantics { contentDescription = loading },
                                )
                            }
                        is StartState.Ready -> CgNavHost(isConfigured = state.isConfigured)
                    }
                }
            }
        }
    }
}
