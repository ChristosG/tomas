package gr.dimitris.app.caregiver.progress

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gr.dimitris.app.ui.theme.Palette
import gr.dimitris.app.ui.theme.Sizes

/**
 * The whole charting library. Navy bars on a mist ground, one bar per value, the labels underneath
 * as ordinary text so they scale with the caregiver's font size — a chart nobody can read at arm's
 * length is not information.
 *
 * Bars are drawn against the tallest value in the list, so the shape of a week is visible whether
 * he practised for four minutes a day or forty. A zero keeps its slot and draws nothing: the gap is
 * the point.
 */
@Composable
fun BarChart(
    values: List<Float>,
    labels: List<String>,
    modifier: Modifier = Modifier,
    height: Dp = 120.dp,
) {
    if (values.isEmpty()) return
    val ground = Palette.mist
    val bar = Palette.navy
    Column(modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val corner = CornerRadius(8.dp.toPx())
            drawRoundRect(color = ground, cornerRadius = corner)
            val top = 8.dp.toPx()
            val floor = size.height - top
            val tallest = values.max()
            val slot = size.width / values.size
            // Capped as well as proportional: one week of cue levels is one bar, and a bar half the
            // screen wide reads as a wall rather than as a measurement.
            val width = (slot * 0.56f).coerceAtMost(72.dp.toPx())
            val minimum = 3.dp.toPx()
            values.forEachIndexed { i, value ->
                if (value <= 0f) return@forEachIndexed
                val scaled = if (tallest <= 0f) 0f else (value / tallest) * (floor - top)
                val barHeight = scaled.coerceAtLeast(minimum)
                drawRoundRect(
                    color = bar,
                    topLeft = Offset(i * slot + (slot - width) / 2f, floor - barHeight),
                    size = Size(width, barHeight),
                    cornerRadius = CornerRadius(4.dp.toPx()),
                )
            }
        }
        if (labels.isNotEmpty()) {
            Spacer(Modifier.height(Sizes.gapSmall))
            Row(Modifier.fillMaxWidth()) {
                labels.forEach { label ->
                    Text(
                        label,
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}
