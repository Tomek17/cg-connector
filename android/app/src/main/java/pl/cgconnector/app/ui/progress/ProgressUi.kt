package pl.cgconnector.app.ui.progress

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import pl.cgconnector.app.R
import pl.cgconnector.app.domain.ActivityTag
import pl.cgconnector.app.domain.ActivityType
import pl.cgconnector.app.domain.LoadError
import pl.cgconnector.app.ui.format.Formatters
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@StringRes
fun ActivityTag.labelRes(): Int =
    when (this) {
        ActivityTag.INTERVALS -> R.string.tag_intervals
        ActivityTag.TEMPO -> R.string.tag_tempo
        ActivityTag.LONG -> R.string.tag_long
        ActivityTag.BASE -> R.string.tag_base
        ActivityTag.EASY -> R.string.tag_easy
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
fun readyOrStatus(
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

/** Jedna linia wykresu: wartości po jednej na trening (`null` = brak, linia ją przeskakuje). */
data class ChartSeries(
    val name: String,
    val values: List<Double?>,
    val color: Color,
    val format: (Double) -> String,
)

private val AXIS_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM", Formatters.POLISH)

/**
 * Wykres treningów: oś Y z podziałką, daty pod punktami, wartość nad każdym punktem (gdy się mieści)
 * i dotknięcie punktu = dokładne wartości tego treningu nad wykresem. Treningi w równych odstępach.
 * [invert] = mniejsza wartość wyżej (tempo: szybciej = wyżej).
 */
@Composable
fun SessionChartCard(
    title: String,
    dates: List<LocalDate>,
    series: List<ChartSeries>,
    axis: ChartAxis,
    axisFormat: (Double) -> String,
    invert: Boolean = false,
) {
    var selected by remember(dates) { mutableStateOf<Int?>(null) }
    val count = dates.size
    val description =
        ProgressChartMath.dateRange(dates)?.let { (first, last) ->
            stringResource(R.string.progress_chart_description, title, count, SHORT_DATE.format(first), SHORT_DATE.format(last))
        } ?: title
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            SelectionLine(selected?.let { index -> dates[index] to series.map { it to it.values[index] } })
            SessionCanvas(
                dates = dates,
                series = series,
                axis = axis,
                axisFormat = axisFormat,
                invert = invert,
                selected = selected,
                onTap = { index -> selected = if (selected == index) null else index },
                modifier = Modifier.fillMaxWidth().height(240.dp).semantics { contentDescription = description },
            )
            if (series.size > 1) Legend(series)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SelectionLine(selection: Pair<LocalDate, List<Pair<ChartSeries, Double?>>>?) {
    if (selection == null) {
        Text(
            stringResource(R.string.progress_chart_tap_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    val (date, values) = selection
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(SHORT_DATE.format(date), style = MaterialTheme.typography.labelLarge)
        values.forEach { (line, value) ->
            Text(
                "${line.name}: ${value?.let(line.format) ?: "–"}",
                style = MaterialTheme.typography.labelLarge,
                color = line.color,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun Legend(series: List<ChartSeries>) {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        series.forEach { line ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).background(line.color, RoundedCornerShape(2.dp)))
                Text(line.name, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun SessionCanvas(
    dates: List<LocalDate>,
    series: List<ChartSeries>,
    axis: ChartAxis,
    axisFormat: (Double) -> String,
    invert: Boolean,
    selected: Int?,
    onTap: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.onSurfaceVariant
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val onLabel = MaterialTheme.colorScheme.surface
    val axisStyle = TextStyle(fontSize = 11.sp, color = axisColor)
    val valueStyle = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Bold, color = onLabel)
    val count = dates.size
    val tickLabels = axis.ticks.map { it to axisFormat(it) }
    // Lewy margines (podpisy osi Y) liczony w pointerInput i w rysowaniu tak samo.
    val leftPadding = remember(tickLabels) { tickLabels.maxOfOrNull { measurer.measure(it.second, axisStyle).size.width } ?: 0 }
    Canvas(
        modifier.pointerInput(count, leftPadding) {
            detectTapGestures { offset ->
                val left = leftPadding + 8.dp.toPx()
                val width = size.width - left - 8.dp.toPx()
                if (width > 0) ProgressChartMath.nearest((offset.x - left) / width, count)?.let(onTap)
            }
        },
    ) {
        val left = leftPadding + 8.dp.toPx()
        val right = size.width - 8.dp.toPx()
        val top = 8.dp.toPx()
        val dateHeight = measurer.measure("0", axisStyle).size.height
        val bottom = size.height - dateHeight - 8.dp.toPx()
        val width = right - left
        val height = bottom - top
        fun xOf(index: Int) = left + ProgressChartMath.x(index, count) * width
        fun yOf(value: Double) = bottom - axis.fraction(value, invert) * height

        tickLabels.forEach { (value, label) ->
            val y = yOf(value)
            drawLine(gridColor, Offset(left, y), Offset(right, y), strokeWidth = 1.dp.toPx())
            val layout = measurer.measure(label, axisStyle)
            drawText(layout, topLeft = Offset(leftPadding - layout.size.width.toFloat(), y - layout.size.height / 2f))
        }

        val dateWidth = measurer.measure("30 wrz", axisStyle).size.width + 8.dp.toPx()
        ProgressChartMath.labelledIndices(count, (width / dateWidth).toInt()).forEach { index ->
            val layout = measurer.measure(AXIS_DATE.format(dates[index]), axisStyle)
            val x = (xOf(index) - layout.size.width / 2f).coerceIn(0f, size.width - layout.size.width)
            drawText(layout, topLeft = Offset(x, bottom + 6.dp.toPx()))
        }

        selected?.let { index ->
            drawLine(axisColor, Offset(xOf(index), top), Offset(xOf(index), bottom), strokeWidth = 1.dp.toPx())
        }

        val stroke = 2.5.dp.toPx()
        val radius = 4.dp.toPx()
        series.forEach { line ->
            val points = line.values.mapIndexedNotNull { i, v -> v?.let { i to Offset(xOf(i), yOf(it)) } }
            if (points.size > 1) {
                val path = Path().apply {
                    moveTo(points.first().second.x, points.first().second.y)
                    points.drop(1).forEach { lineTo(it.second.x, it.second.y) }
                }
                drawPath(path, line.color, style = Stroke(width = stroke))
            }
            points.forEach { (i, at) ->
                drawCircle(line.color, radius = if (i == selected) radius * 1.8f else radius, center = at)
            }
        }

        // Wartości nad punktami: tylko te, które nie nachodzą na już narysowane (reszta po dotknięciu).
        val placed = mutableListOf<Rect>()
        val pad = 3.dp.toPx()
        val gap = 4.dp.toPx()
        series.forEach { line ->
            line.values.forEachIndexed { i, value ->
                if (value == null) return@forEachIndexed
                val layout = measurer.measure(line.format(value), valueStyle)
                val boxSize = Size(layout.size.width + 2 * pad, layout.size.height + pad)
                val at = Offset(xOf(i), yOf(value))
                val above = at.y - radius - gap - boxSize.height
                val y = if (above >= 0f) above else at.y + radius + gap
                val x = (at.x - boxSize.width / 2).coerceIn(0f, size.width - boxSize.width)
                val rect = Rect(Offset(x, y), boxSize)
                if (placed.any { it.overlaps(rect) }) return@forEachIndexed
                placed += rect
                drawRoundRect(line.color, topLeft = rect.topLeft, size = boxSize, cornerRadius = CornerRadius(3.dp.toPx()))
                drawText(layout, topLeft = Offset(x + pad, y + pad / 2))
            }
        }
    }
}
