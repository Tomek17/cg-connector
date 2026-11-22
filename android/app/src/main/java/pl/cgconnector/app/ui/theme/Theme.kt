package pl.cgconnector.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.cgconnector.app.domain.ActivityType

/*
 * Własna paleta CG-connector (bez kolorów z tapety, żeby aplikacja zawsze wyglądała tak samo):
 * granat jako kolor główny, pomarańcz jako akcent, zieleń jako trzeci kolor. Jasna i ciemna wersja
 * idą za ustawieniem telefonu.
 */
private val LightColors =
    lightColorScheme(
        primary = Color(0xFF1B4FA0),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFD8E2FF),
        onPrimaryContainer = Color(0xFF001A41),
        secondary = Color(0xFFC4501A),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFFFDBCB),
        onSecondaryContainer = Color(0xFF341100),
        tertiary = Color(0xFF2E7D4F),
        onTertiary = Color(0xFFFFFFFF),
        tertiaryContainer = Color(0xFFB4F1C8),
        onTertiaryContainer = Color(0xFF00210F),
        background = Color(0xFFF7F8FC),
        onBackground = Color(0xFF1A1C20),
        surface = Color(0xFFF7F8FC),
        onSurface = Color(0xFF1A1C20),
        surfaceVariant = Color(0xFFE1E2EC),
        onSurfaceVariant = Color(0xFF44474F),
        surfaceContainerLowest = Color(0xFFFFFFFF),
        surfaceContainerLow = Color(0xFFF1F3F9),
        surfaceContainer = Color(0xFFEBEEF5),
        surfaceContainerHigh = Color(0xFFE5E8F0),
        surfaceContainerHighest = Color(0xFFDFE2EB),
        outline = Color(0xFF75777F),
        outlineVariant = Color(0xFFC4C6D0),
    )

private val DarkColors =
    darkColorScheme(
        primary = Color(0xFFAEC6FF),
        onPrimary = Color(0xFF002E69),
        primaryContainer = Color(0xFF0E4492),
        onPrimaryContainer = Color(0xFFD8E2FF),
        secondary = Color(0xFFFFB693),
        onSecondary = Color(0xFF561F00),
        secondaryContainer = Color(0xFF7A3000),
        onSecondaryContainer = Color(0xFFFFDBCB),
        tertiary = Color(0xFF98D5AD),
        onTertiary = Color(0xFF00391D),
        tertiaryContainer = Color(0xFF10512F),
        onTertiaryContainer = Color(0xFFB4F1C8),
        background = Color(0xFF111318),
        onBackground = Color(0xFFE2E2E9),
        surface = Color(0xFF111318),
        onSurface = Color(0xFFE2E2E9),
        surfaceVariant = Color(0xFF44474F),
        onSurfaceVariant = Color(0xFFC4C6D0),
        surfaceContainerLowest = Color(0xFF0C0E13),
        surfaceContainerLow = Color(0xFF1A1C21),
        surfaceContainer = Color(0xFF1E2025),
        surfaceContainerHigh = Color(0xFF24272D),
        surfaceContainerHighest = Color(0xFF2F3238),
        outline = Color(0xFF8E9099),
        outlineVariant = Color(0xFF44474F),
    )

/** Wyraźniejsze tytuły i grube liczby (VO2max, kilometry); reszta jak w Material 3. */
private val CgTypography =
    Typography().run {
        copy(
            displaySmall = displaySmall.copy(fontWeight = FontWeight.Bold),
            headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold),
            headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold),
            titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold),
            titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
            titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
            labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp),
        )
    }

/** Bardziej zaokrąglone karty i arkusze. */
private val CgShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(10.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

@Composable
fun CgTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = CgTypography,
        shapes = CgShapes,
        content = content,
    )
}

/** Kolor sportu: tło i treść (ikona, tekst) dla kółka z ikoną i kart. */
data class SportColors(
    val container: Color,
    val content: Color,
)

private val SportLight =
    mapOf(
        ActivityType.RUNNING to SportColors(Color(0xFFD8E2FF), Color(0xFF0E4492)),
        ActivityType.STRENGTH to SportColors(Color(0xFFFFDBCB), Color(0xFF7A3000)),
        ActivityType.CLIMBING to SportColors(Color(0xFFB4F1C8), Color(0xFF10512F)),
        ActivityType.OTHER to SportColors(Color(0xFFE9DDFF), Color(0xFF4F378A)),
    )

private val SportDark =
    mapOf(
        ActivityType.RUNNING to SportColors(Color(0xFF0E4492), Color(0xFFD8E2FF)),
        ActivityType.STRENGTH to SportColors(Color(0xFF7A3000), Color(0xFFFFDBCB)),
        ActivityType.CLIMBING to SportColors(Color(0xFF10512F), Color(0xFFB4F1C8)),
        ActivityType.OTHER to SportColors(Color(0xFF4F378A), Color(0xFFE9DDFF)),
    )

/** Bieg: granat, siłownia: pomarańcz, wspinaczka: zieleń, inne: fiolet. */
@Composable
fun sportColors(type: ActivityType): SportColors = (if (isSystemInDarkTheme()) SportDark else SportLight).getValue(type)

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
