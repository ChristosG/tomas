package gr.dimitris.app.modules.numbers

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.GreekNumbers
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes

/**
 * The colour an answer button wears. Green once the answer is out — whether he found it or was shown
 * it after two misses — muted for the option he tapped and missed.
 */
@Composable
private fun optionColours(value: Int, s: NumbersState, answer: Int): Pair<Color, Color> {
    val shown = s.correct == true || s.revealed
    val container = when {
        shown && value == answer -> MaterialTheme.colorScheme.tertiary
        s.chosen == value && s.correct == false -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val content = if (s.chosen == value && s.correct == false && !(shown && value == answer)) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onPrimary
    }
    return container to content
}

/** A big answer button. Green when it is the right answer after answering, muted when it was a wrong tap. */
@Composable
fun OptionButton(label: String, value: Int, s: NumbersState, answer: Int, onClick: (Int) -> Unit, modifier: Modifier = Modifier, big: Boolean = true) {
    val (container, content) = optionColours(value, s, answer)
    Button(
        // No tap haptic here: choose() answers with success or nudge, and a tap after the answer
        // does nothing at all — a buzz would promise otherwise.
        onClick = { onClick(value) },
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        shape = RoundedCornerShape(Sizes.corner),
        // Material's 24dp default eats a narrow button: a "1000" tick would stack its digits, and
        // reading the number is the exercise.
        contentPadding = PaddingValues(horizontal = 4.dp, vertical = 4.dp),
        modifier = modifier.heightIn(min = if (big) 96.dp else Sizes.touchMin),
    ) {
        Text(
            label, style = if (big) MaterialTheme.typography.displayLarge else MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center, maxLines = 1, softWrap = false,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DotGrid(n: Int, modifier: Modifier = Modifier) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 5, modifier = modifier) {
        repeat(n) { Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.secondary, CircleShape)) }
    }
}

@Composable
fun CompareView(e: NumberExercise.Compare, s: NumbersState, onChoose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        listOf(e.a, e.b).forEach { v ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(4.dp)) {
                if (e.showDots) { DotGrid(v, Modifier.heightIn(min = 60.dp)); Spacer(Modifier.height(8.dp)) }
                OptionButton(v.toString(), v, s, e.answer, onChoose, Modifier.fillMaxWidth())
            }
        }
    }
}

/**
 * A real line: one horizontal rule across the screen, a small tick at every number, the ends and the
 * middle labelled, and a round button standing on each place he may tap. Nothing on the buttons —
 * a tick labelled with its own digit would turn "where does seven go" into "find the glyph 7".
 */
@Composable
fun NumberLineView(e: NumberExercise.NumberLine, s: NumbersState, onChoose: (Int) -> Unit) {
    val lo = e.ticks.first()
    val hi = e.ticks.last()
    val span = (hi - lo).coerceAtLeast(1).toFloat()
    val labelled = listOf(lo, e.ticks[e.ticks.size / 2], hi).distinct()
    val rule = MaterialTheme.colorScheme.onBackground

    Text(e.target.toString(), style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Sizes.gap))
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Half a button of margin at each end, so the circle standing on the first tick is fully on
        // the screen and every centre is a real position on the line.
        val inset = Sizes.touchMin / 2
        val usable = maxWidth - Sizes.touchMin
        fun xOf(v: Int): Dp = inset + usable * ((v - lo) / span)
        Column(Modifier.fillMaxWidth()) {
            Canvas(Modifier.fillMaxWidth().height(LINE_HEIGHT)) {
                val y = size.height / 2f
                drawLine(rule, Offset(inset.toPx(), y), Offset(size.width - inset.toPx(), y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
                e.ticks.forEach { t ->
                    val half = (if (t in labelled) 14.dp else 8.dp).toPx()
                    drawLine(rule, Offset(xOf(t).toPx(), y - half), Offset(xOf(t).toPx(), y + half), strokeWidth = 3.dp.toPx())
                }
            }
            Box(Modifier.fillMaxWidth().height(LABEL_HEIGHT)) {
                labelled.forEach { t ->
                    Text(
                        t.toString(), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.width(LABEL_WIDTH).offset(x = xOf(t) - LABEL_WIDTH / 2),
                    )
                }
            }
            Spacer(Modifier.height(Sizes.gapSmall))
            Box(Modifier.fillMaxWidth().height(Sizes.touchMin)) {
                e.candidates.forEach { v ->
                    val (container, content) = optionColours(v, s, e.answer)
                    Button(
                        onClick = { onChoose(v) },
                        shape = CircleShape,
                        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
                        contentPadding = PaddingValues(0.dp),
                        // Full 72dp, centred on the value's own place on the line.
                        modifier = Modifier.size(Sizes.touchMin).offset(x = xOf(v) - inset)
                            .semantics { contentDescription = "Θέση ${GreekNumbers.words(v)}" },
                    ) {}
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CountView(e: NumberExercise.Count, s: NumbersState, onTapObject: () -> Unit, onChoose: (Int) -> Unit) {
    val feedback = LocalFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 5, modifier = Modifier.fillMaxWidth()) {
        repeat(e.n) { i ->
            val counted = i < s.tapped
            Button(
                onClick = { if (i == s.tapped) onTapObject() else feedback.nudge() },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = if (counted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary),
                // 72dp minus Material's default padding leaves no room for "10".
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier.size(Sizes.touchMin),
            ) { Text(if (counted) (i + 1).toString() else "", style = MaterialTheme.typography.titleLarge, maxLines = 1, softWrap = false) }
        }
    }
    Spacer(Modifier.height(Sizes.gap))
    if (s.tapped >= e.n) {
        Row(Modifier.fillMaxWidth()) {
            e.options.forEach { v -> OptionButton(v.toString(), v, s, e.answer, onChoose, Modifier.weight(1f).padding(4.dp)) }
        }
    } else {
        Text("Πάτα τα ένα-ένα.", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun WordMatchView(e: NumberExercise.WordMatch, s: NumbersState, onChoose: (Int) -> Unit) {
    Text(if (e.showWord) GreekNumbers.words(e.number) else e.number.toString(), style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Sizes.gap))
    Column {
        e.options.forEach { v ->
            OptionButton(if (e.showWord) v.toString() else GreekNumbers.words(v), v, s, e.answer, onChoose, Modifier.fillMaxWidth(), big = e.showWord)
            Spacer(Modifier.height(Sizes.gapSmall))
        }
    }
}

@Composable
fun CoinPickView(e: NumberExercise.CoinPick, s: NumbersState, onChoose: (Int) -> Unit) {
    Column {
        e.options.forEach { v -> OptionButton(Euro.format(v), v, s, e.answer, onChoose, Modifier.fillMaxWidth(), big = false); Spacer(Modifier.height(Sizes.gapSmall)) }
    }
}

@Composable
fun PriceCompareView(e: NumberExercise.PriceCompare, s: NumbersState, onChoose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        listOf(e.a, e.b).forEach { p ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(4.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                OptionButton(Euro.format(p.cents), p.cents, s, e.answer, onChoose, Modifier.fillMaxWidth(), big = false)
            }
        }
    }
}

@Composable
fun PayView(e: NumberExercise.Pay, s: NumbersState, onChoose: (Int) -> Unit) {
    Text(Euro.format(e.priceCents), style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Sizes.gap))
    Column {
        e.options.forEach { v -> OptionButton(Euro.format(v), v, s, e.answer, onChoose, Modifier.fillMaxWidth(), big = false); Spacer(Modifier.height(Sizes.gapSmall)) }
    }
}

/** Tall enough for the ticks either side of the rule. */
private val LINE_HEIGHT = 40.dp
private val LABEL_HEIGHT = 28.dp

/** Wide enough for "1000" and centred on its tick, so the end labels do not lean on their neighbour. */
private val LABEL_WIDTH = 64.dp
