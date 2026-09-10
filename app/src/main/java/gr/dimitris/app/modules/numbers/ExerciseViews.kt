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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.GreekNumbers
import gr.dimitris.app.core.greek.GreekTime
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

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

/**
 * A big answer button. Green when it is the right answer after answering, muted when it was a wrong
 * tap.
 *
 * [lines] is how many lines the label may take. One, and no wrapping at all, is the rule for a number
 * — a "1000" broken over two lines is two numbers — but the levels phase 12 added put words on the
 * buttons too: a time is a time and its name («3:30 / τρεις και μισή»), and a four-digit number said
 * out is four words long. Those get the room they need rather than an ellipsis through the middle of
 * the answer.
 */
@Composable
fun OptionButton(
    label: String,
    value: Int,
    s: NumbersState,
    answer: Int,
    onClick: (Int) -> Unit,
    modifier: Modifier = Modifier,
    big: Boolean = true,
    lines: Int = 1,
) {
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
            textAlign = TextAlign.Center, maxLines = lines, softWrap = lines > 1,
        )
    }
}

/**
 * The answer buttons two to a row, which is what four of them need: a row of four puts «Παρασκευή»
 * or «τρεις και μισή» into a column 90dp wide, and a column of four pushes the last one off the
 * bottom of the screen on the levels whose question is three lines long.
 */
@Composable
fun OptionGrid(
    e: NumberExercise,
    s: NumbersState,
    onChoose: (Int) -> Unit,
    big: Boolean = true,
    lines: Int = 1,
    label: (Int) -> String = { it.toString() },
) {
    Column(Modifier.fillMaxWidth()) {
        e.options.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                row.forEach { v ->
                    OptionButton(label(v), v, s, e.answer, onChoose, Modifier.weight(1f).padding(4.dp), big = big, lines = lines)
                }
                // An odd last row keeps its button the same width as the ones above it.
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(Sizes.gapSmall))
        }
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
    val stimulus = if (e.showWord) GreekNumbers.words(e.number) else e.number.toString()
    Text(
        stimulus,
        // «εννέα χιλιάδες εννιακόσια ενενήντα εννέα» at display size is six lines of stimulus and no
        // room left for the buttons. Level 14 is the only level whose words are that long.
        style = if (stimulus.length > LONG_STIMULUS) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.displayLarge,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(Sizes.gap))
    Column {
        e.options.forEach { v ->
            OptionButton(
                if (e.showWord) v.toString() else GreekNumbers.words(v), v, s, e.answer, onChoose,
                Modifier.fillMaxWidth(), big = e.showWord, lines = if (e.showWord) 1 else WORD_LINES,
            )
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

// ---------------------------------------------------------------- levels 8–15

/**
 * A sum with its answer left open: `34 + 25 = ?`.
 *
 * At level 9 — and only there — a line of hundreds sits under it with the first number marked. That
 * level is where a carry first matters, and the line is the difference between "I crossed a hundred"
 * and four numbers that all look equally possible. It is drawn, not tappable: the answer is still one
 * of the four buttons.
 */
@Composable
fun ArithmeticView(e: NumberExercise.Arithmetic, s: NumbersState, onChoose: (Int) -> Unit) {
    Text(
        "${e.equation} = ${NumberExercise.BLANK}",
        style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (e.level == CARRY_LEVEL) {
        Spacer(Modifier.height(Sizes.gapSmall))
        HundredsLine(e.a)
    }
    Spacer(Modifier.height(Sizes.gap))
    OptionGrid(e, s, onChoose)
}

/** `? × 4 = 32`: the blank is an operand, and the four buttons are what might fill it. */
@Composable
fun MissingView(e: NumberExercise.Missing, s: NumbersState, onChoose: (Int) -> Unit) {
    Text(e.equation, style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Sizes.gap))
    OptionGrid(e, s, onChoose)
}

/**
 * Change from a note. The amounts are in the question; the buttons are the money, one to a row like
 * every other euro exercise, so the comma and the € sign line up and can be compared down the column.
 *
 * Once the answer is out, the coins and notes that make it are spelled out under it — «5 € + 1 € +
 * 50 λ + 10 λ». Not before: it is the same number twice, and one of the two is the answer.
 */
@Composable
fun ChangeView(e: NumberExercise.Change, s: NumbersState, onChoose: (Int) -> Unit) {
    Column {
        e.options.forEach { v ->
            OptionButton(Euro.format(v), v, s, e.answer, onChoose, Modifier.fillMaxWidth(), big = false)
            Spacer(Modifier.height(Sizes.gapSmall))
        }
        if (s.correct == true || s.revealed) {
            Text(
                e.pieces.joinToString("  +  ") { coinLabel(it) },
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A coin in as few characters as it can honestly be written: «50 λ», «2 €». */
private fun coinLabel(cents: Int): String = if (cents < 100) "$cents λ" else "${cents / 100} €"

/**
 * The analogue face, and four times under it. Every button carries the digits *and* the words, because
 * this is the one exercise whose question cannot be spoken — the face is the question — so both ways
 * of naming an hour have to be in reach of the same tap.
 */
@Composable
fun ClockView(e: NumberExercise.Clock, s: NumbersState, onChoose: (Int) -> Unit) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ClockFace(e.minutes) }
    Spacer(Modifier.height(Sizes.gap))
    // Four lines: the digits, and up to three for the words. «εννέα και τέταρτο» does not fit beside
    // «εννέα παρά τέταρτο» in one line each, and cut off after two words they are the same button.
    OptionGrid(e, s, onChoose, big = false, lines = CLOCK_LINES) { v -> "${GreekTime.digits(v)}\n${GreekTime.words(v)}" }
}

/** Twelve o'clock at the top, an hour hand and a longer minute hand, and a tick for every hour. */
@Composable
fun ClockFace(minutes: Int, modifier: Modifier = Modifier) {
    val ink = MaterialTheme.colorScheme.onBackground
    val face = MaterialTheme.colorScheme.surfaceVariant
    val hands = MaterialTheme.colorScheme.primary
    Canvas(modifier.size(CLOCK_SIZE).semantics { contentDescription = "Ρολόι" }) {
        val radius = size.minDimension / 2f
        val middle = Offset(size.width / 2f, size.height / 2f)

        /** [turn] is how far round from twelve, as a fraction of the whole circle. */
        fun hand(turn: Float, length: Float, width: Dp) {
            val angle = turn * 2f * PI.toFloat() - PI.toFloat() / 2f
            drawLine(
                hands, middle, middle + Offset(cos(angle) * length, sin(angle) * length),
                strokeWidth = width.toPx(), cap = StrokeCap.Round,
            )
        }

        drawCircle(face, radius = radius, center = middle)
        drawCircle(ink, radius = radius, center = middle, style = Stroke(width = 3.dp.toPx()))
        repeat(12) { i ->
            val angle = i / 12f * 2f * PI.toFloat() - PI.toFloat() / 2f
            val outer = middle + Offset(cos(angle) * radius * 0.92f, sin(angle) * radius * 0.92f)
            val inner = middle + Offset(cos(angle) * radius * 0.78f, sin(angle) * radius * 0.78f)
            // The quarters get the long ticks: they are the marks a face is actually read against.
            drawLine(ink, inner, outer, strokeWidth = (if (i % 3 == 0) 5.dp else 3.dp).toPx(), cap = StrokeCap.Round)
        }
        val minute = GreekTime.minuteOf(minutes)
        // The hour hand creeps: at half past three it is between the three and the four, which is
        // exactly the thing that makes a real face hard and a toy one useless.
        hand((GreekTime.hourOf(minutes) + minute / 60f) / 12f, radius * 0.52f, 9.dp)
        hand(minute / 60f, radius * 0.80f, 5.dp)
        drawCircle(hands, radius = 6.dp.toPx(), center = middle)
    }
}

/** «Τι μέρα είναι μετά από 3 μέρες;» — four days, by name. */
@Composable
fun DayAfterView(e: NumberExercise.DayAfter, s: NumbersState, onChoose: (Int) -> Unit) {
    OptionGrid(e, s, onChoose, big = false) { v -> GreekTime.day(v) }
}

/** A two-step problem. The story is the prompt the screen already shows; these are the answers. */
@Composable
fun WordProblemView(e: NumberExercise.WordProblem, s: NumbersState, onChoose: (Int) -> Unit) {
    OptionGrid(e, s, onChoose)
}

/**
 * A line from zero to a thousand with [at] marked on it. Level 9's aid, and nothing he can tap.
 */
@Composable
private fun HundredsLine(at: Int) {
    val rule = MaterialTheme.colorScheme.onBackground
    val mark = MaterialTheme.colorScheme.secondary
    val span = HUNDREDS_SPAN.toFloat()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val inset = LABEL_WIDTH / 2
        val usable = maxWidth - LABEL_WIDTH
        fun xOf(v: Int): Dp = inset + usable * (v.coerceIn(0, HUNDREDS_SPAN) / span)
        Column(Modifier.fillMaxWidth()) {
            Canvas(Modifier.fillMaxWidth().height(LINE_HEIGHT)) {
                val y = size.height / 2f
                drawLine(rule, Offset(inset.toPx(), y), Offset(size.width - inset.toPx(), y), strokeWidth = 3.dp.toPx(), cap = StrokeCap.Round)
                (0..HUNDREDS_SPAN step 100).forEach { t ->
                    drawLine(rule, Offset(xOf(t).toPx(), y - 8.dp.toPx()), Offset(xOf(t).toPx(), y + 8.dp.toPx()), strokeWidth = 2.dp.toPx())
                }
                drawCircle(mark, radius = 9.dp.toPx(), center = Offset(xOf(at).toPx(), y))
            }
            Box(Modifier.fillMaxWidth().height(LABEL_HEIGHT)) {
                listOf(0, HUNDREDS_SPAN / 2, HUNDREDS_SPAN).forEach { t ->
                    Text(
                        t.toString(), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                        maxLines = 1, softWrap = false,
                        modifier = Modifier.width(LABEL_WIDTH).offset(x = xOf(t) - LABEL_WIDTH / 2),
                    )
                }
            }
        }
    }
}

/** Tall enough for the ticks either side of the rule. */
private val LINE_HEIGHT = 40.dp
private val LABEL_HEIGHT = 28.dp

/** Wide enough for "1000" and centred on its tick, so the end labels do not lean on their neighbour. */
private val LABEL_WIDTH = 64.dp

/** Big enough that the hands are two different lengths at arm's length, small enough to leave the buttons room. */
private val CLOCK_SIZE = 220.dp

/** The level whose sums carry, and the only one that gets the line of hundreds. */
private const val CARRY_LEVEL = 9

/** How far the level-9 line runs. The same thousand level 6 walks along. */
private const val HUNDREDS_SPAN = 1000

/** Past this many characters a stimulus is a sentence, not a number, and stops being display-sized. */
private const val LONG_STIMULUS = 20

/** Lines a spelled-out number may take on a button. «τέσσερις χιλιάδες τετρακόσια σαράντα τέσσερα». */
private const val WORD_LINES = 3

/**
 * Lines a time may take: one for the digits and up to three for the words.
 *
 * Measured, not guessed. At two lines «εννέα και τέταρτο» and «εννέα παρά τέταρτο» were both cut
 * after «εννέα και» and «εννέα παρά» on a half-width button — two different times wearing the same
 * label, with the right answer among the halves that had been thrown away.
 */
private const val CLOCK_LINES = 4
