package pl.cgconnector.app.ui.progress

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.LoadError
import pl.cgconnector.app.ui.format.Formatters
import pl.cgconnector.app.ui.vo2max.ChartData
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@StringRes
fun ActivityTag.labelRes(): Int =
    when (this) {
        ActivityTag.INTERVALS -> R.string.tag_intervals
        ActivityTag.TEMPO -> R.string.tag_tempo
        ActivityTag.LONG -> R.string.tag_long
        ActivityTag.BASE -> R.string.tag_base
        ActivityTag.STRIDES -> R.string.tag_strides
        ActivityTag.UPPER -> R.string.tag_upper
        ActivityTag.FBW -> R.string.tag_fbw
        ActivityTag.PUSH -> R.string.tag_push
        ActivityTag.PULL -> R.string.tag_pull
        ActivityTag.LEGS -> R.string.tag_legs
        ActivityTag.WALL -> R.string.tag_wall
    }

@StringRes
fun ActivityType.categoryRes(): Int =
    when (this) {
        ActivityType.STRENGTH -> R.string.progress_category_strength
        ActivityType.RUNNING -> R.string.progress_category_running
        ActivityType.CLIMBING -> R.string.progress_category_climbing
        ActivityType.OTHER -> R.string.activity_type_other
    }

@StringRes
fun LoadError.messageRes(): Int =
    when (this) {
        LoadError.OFFLINE -> R.string.progress_offline
        LoadError.UNAUTHORIZED -> R.string.detail_unauthorized
        LoadError.NOT_CONFIGURED -> R.string.detail_not_configured
        LoadError.INVALID_RESPONSE -> R.string.detail_invalid_response
    }

val SHORT_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Formatters.POLISH)

/** Wskaźnik ładowania albo błąd z przyciskiem „Spróbuj ponownie”; `true` = jest co pokazać na ekranie. */
@Composable
fun LoadState(
    isLoading: Boolean,
    error: LoadError?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
): Boolean {
    if (isLoading) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return false
    }
    if (error != null) {
        Column(
            modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(error.messageRes()))
            TextButton(onClick = onRetry) { Text(stringResource(R.string.progress_retry)) }
        }
        return false
    }
    return true
}

/**
 * Karta z wykresem: tytuł, wartość u góry i u dołu osi, zakres dat pod spodem.
 * [connect] = linia między punktami (jedna wartość na sesję); bez niej same kropki (serie).
 */
@Composable
fun ChartCard(
    title: String,
    data: ChartData,
    sessions: Int,
    dates: Pair<LocalDate, LocalDate>,
    format: (Double) -> String,
    connect: Boolean,
    color: Color = MaterialTheme.colorScheme.primary,
    invert: Boolean = false,
) {
    val top = format(if (invert) data.minValue else data.maxValue)
    val bottom = format(if (invert) data.maxValue else data.minValue)
    val description =
        stringResource(
            R.string.progress_chart_description,
            title,
            sessions,
            SHORT_DATE.format(dates.first),
            SHORT_DATE.format(dates.second),
            format(data.minValue),
            format(data.maxValue),
        )
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(top, style = MaterialTheme.typography.labelSmall)
            DotChart(
                data = data,
                connect = connect,
                color = color,
                modifier = Modifier.fillMaxWidth().height(160.dp).semantics { contentDescription = description },
            )
            Text(bottom, style = MaterialTheme.typography.labelSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(SHORT_DATE.format(dates.first), style = MaterialTheme.typography.labelSmall)
                Text(SHORT_DATE.format(dates.second), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DotChart(
    data: ChartData,
    connect: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier) {
        val stroke = 2.dp.toPx()
        // Marginesy, żeby kropki na krawędziach nie były przycięte.
        val inset = stroke * 3
        val width = size.width - 2 * inset
        val height = size.height - 2 * inset
        listOf(data.minY, data.maxY).distinct().forEach { fraction ->
            val y = inset + height * (1 - fraction)
            drawLine(gridColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        val offsets = data.points.map { Offset(inset + it.x * width, inset + (1 - it.y) * height) }
        if (connect && offsets.size > 1) {
            val path =
                Path().apply {
                    moveTo(offsets.first().x, offsets.first().y)
                    offsets.drop(1).forEach { lineTo(it.x, it.y) }
                }
            drawPath(path, color, style = Stroke(width = stroke))
        }
        offsets.forEach { drawCircle(color, radius = stroke * 2, center = it) }
    }
}
