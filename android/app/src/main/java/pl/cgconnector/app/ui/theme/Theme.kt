package pl.cgconnector.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val LightColors = lightColorScheme(primary = Color(0xFF1E5AA8), secondary = Color(0xFFD14B2B))
private val DarkColors = darkColorScheme(primary = Color(0xFF9CC2FF), secondary = Color(0xFFFFB4A1))

@Composable
fun CgTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme =
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val context = LocalContext.current
                if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            }
            darkTheme -> DarkColors
            else -> LightColors
        }
    MaterialTheme(colorScheme = colorScheme, content = content)
}

/** Ton plakietki/kropki stanu. Zielony jest stały, bo dynamiczne kolory Androida nie mają „sukcesu”. */
enum class StatusTone { SUCCESS, NEUTRAL, ERROR }

private val SuccessLight = Color(0xFF2E7D32)
private val SuccessContainerLight = Color(0xFFC8E6C9)
private val OnSuccessContainerLight = Color(0xFF1B5E20)
private val SuccessDark = Color(0xFF81C784)
private val SuccessContainerDark = Color(0xFF1B5E20)
private val OnSuccessContainerDark = Color(0xFFC8E6C9)

@Composable
fun successColor(): Color = if (isSystemInDarkTheme()) SuccessDark else SuccessLight

/** Para (tło, tekst) dla tonu. */
@Composable
fun toneColors(tone: StatusTone): Pair<Color, Color> =
    when (tone) {
        StatusTone.SUCCESS ->
            if (isSystemInDarkTheme()) {
                SuccessContainerDark to OnSuccessContainerDark
            } else {
                SuccessContainerLight to OnSuccessContainerLight
            }
        StatusTone.NEUTRAL -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        StatusTone.ERROR -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.onErrorContainer
    }
