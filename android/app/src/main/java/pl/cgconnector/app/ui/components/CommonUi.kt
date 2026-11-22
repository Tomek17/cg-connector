package pl.cgconnector.app.ui.components

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.ui.theme.StatusTone
import pl.cgconnector.app.ui.theme.successColor
import pl.cgconnector.app.ui.theme.toneColors

/** Strzałka „wstecz” na górnym pasku. */
@Composable
fun BackIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
    }
}

/** Krzyżyk „anuluj” na górnym pasku kreatora. */
@Composable
fun CloseIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.cancel))
    }
}

/** Odświeżanie na górnym pasku; w trakcie odświeżania kręcący się wskaźnik zamiast ikony. */
@Composable
fun RefreshIconButton(
    onClick: () -> Unit,
    isRefreshing: Boolean = false,
) {
    IconButton(onClick = onClick, enabled = !isRefreshing) {
        if (isRefreshing) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        } else {
            Icon(painterResource(R.drawable.ic_refresh), contentDescription = stringResource(R.string.refresh))
        }
    }
}

@DrawableRes
fun ActivityType.iconRes(): Int =
    when (this) {
        ActivityType.RUNNING -> R.drawable.ic_type_running
        ActivityType.STRENGTH -> R.drawable.ic_type_strength
        ActivityType.CLIMBING -> R.drawable.ic_type_climbing
        ActivityType.OTHER -> R.drawable.ic_type_other
    }

/** Ikona typu treningu w kółku; opis pomija się, bo nazwa typu stoi obok w tekście. */
@Composable
fun ActivityTypeIcon(
    type: ActivityType,
    modifier: Modifier = Modifier,
) {
    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.secondaryContainer, modifier = modifier.size(40.dp)) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painterResource(type.iconRes()),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Kolorowa plakietka stanu (np. „Wysłany do Garmin Connect”). */
@Composable
fun StatusBadge(
    text: String,
    tone: StatusTone,
) {
    val (container, content) = toneColors(tone)
    Surface(shape = RoundedCornerShape(50), color = container, contentColor = content) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
        )
    }
}

/** Mała kropka stanu (np. serwer online/offline). */
@Composable
fun StatusDot(
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val color =
        when (tone) {
            StatusTone.SUCCESS -> successColor()
            StatusTone.ERROR -> MaterialTheme.colorScheme.error
            StatusTone.NEUTRAL -> MaterialTheme.colorScheme.outline
        }
    Surface(shape = CircleShape, color = color, modifier = modifier.size(10.dp)) {}
}
