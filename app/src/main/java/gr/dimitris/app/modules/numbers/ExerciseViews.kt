package gr.dimitris.app.modules.numbers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.GreekNumbers
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes

/** A big answer button. Green when it is the right answer after answering, muted when it was a wrong tap. */
@Composable
fun OptionButton(label: String, value: Int, chosen: Int?, correct: Boolean?, answer: Int, onClick: (Int) -> Unit, modifier: Modifier = Modifier, big: Boolean = true) {
    val feedback = LocalFeedback.current
    val revealed = correct == true
    val container = when {
        revealed && value == answer -> MaterialTheme.colorScheme.tertiary
        chosen == value && correct == false -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val content = if (chosen == value && correct == false) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
    Button(
        onClick = { feedback.tap(); onClick(value) },
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        shape = RoundedCornerShape(Sizes.corner),
        modifier = modifier.heightIn(min = if (big) 96.dp else Sizes.touchMin),
    ) {
        Text(label, style = if (big) MaterialTheme.typography.displayLarge else MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
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
                OptionButton(v.toString(), v, s.chosen, s.correct, e.answer, onChoose, Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NumberLineView(e: NumberExercise.NumberLine, s: NumbersState, onChoose: (Int) -> Unit) {
    Text(e.target.toString(), style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Sizes.gap))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 4, modifier = Modifier.fillMaxWidth()) {
        e.ticks.forEach { t -> OptionButton(t.toString(), t, s.chosen, s.correct, e.answer, onChoose, Modifier.weight(1f), big = false) }
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
                modifier = Modifier.size(Sizes.touchMin),
            ) { Text(if (counted) (i + 1).toString() else "", style = MaterialTheme.typography.titleLarge) }
        }
    }
    Spacer(Modifier.height(Sizes.gap))
    if (s.tapped >= e.n) {
        Row(Modifier.fillMaxWidth()) {
            e.options.forEach { v -> OptionButton(v.toString(), v, s.chosen, s.correct, e.answer, onChoose, Modifier.weight(1f).padding(4.dp)) }
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
            OptionButton(if (e.showWord) v.toString() else GreekNumbers.words(v), v, s.chosen, s.correct, e.answer, onChoose, Modifier.fillMaxWidth(), big = e.showWord)
            Spacer(Modifier.height(Sizes.gapSmall))
        }
    }
}

@Composable
fun CoinPickView(e: NumberExercise.CoinPick, s: NumbersState, onChoose: (Int) -> Unit) {
    Column {
        e.options.forEach { v -> OptionButton(Euro.format(v), v, s.chosen, s.correct, e.answer, onChoose, Modifier.fillMaxWidth(), big = false); Spacer(Modifier.height(Sizes.gapSmall)) }
    }
}

@Composable
fun PriceCompareView(e: NumberExercise.PriceCompare, s: NumbersState, onChoose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        listOf(e.a, e.b).forEach { p ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(4.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                OptionButton(Euro.format(p.cents), p.cents, s.chosen, s.correct, e.answer, onChoose, Modifier.fillMaxWidth(), big = false)
            }
        }
    }
}

@Composable
fun PayView(e: NumberExercise.Pay, s: NumbersState, onChoose: (Int) -> Unit) {
    Text(Euro.format(e.priceCents), style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Sizes.gap))
    Column {
        e.options.forEach { v -> OptionButton(Euro.format(v), v, s.chosen, s.correct, e.answer, onChoose, Modifier.fillMaxWidth(), big = false); Spacer(Modifier.height(Sizes.gapSmall)) }
    }
}
