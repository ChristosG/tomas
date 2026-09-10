package gr.dimitris.app.modules.sql

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.ListenButton
import gr.dimitris.app.ui.components.ModuleDifficultyRow
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes

/** Every tile of an ordering board, so a test can read what is on offer and tap it. */
const val SQL_TILE_TAG = "sql-tile"

/** Every tile he has already laid down, in the order he laid them. Tapping one takes it back. */
const val SQL_CHOSEN_TAG = "sql-chosen"

/** Each of the three options on a «διάλεξε» board. */
const val SQL_OPTION_TAG = "sql-option"

/** The one text field in this module. */
const val SQL_TYPED_TAG = "sql-typed"

/** [count] puzzles, one per item the session budgeted for this module. */
@Composable
fun SqlScreen(count: Int, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    // The count is part of the key: a resumed ViewModel would otherwise keep the old session's length.
    val vm: SqlViewModel = viewModel(key = "sql-${sessionId ?: "practice"}-$count") { SqlViewModel(graph, sessionId, count) }
    val s by vm.state.collectAsStateWithLifecycle()

    /**
     * A mixed session ends with its own summary, so a second "well done" screen in the middle of it
     * is one tap of noise: the module hands straight back. Free practice keeps the end screen — it is
     * the only ending there is — and so does a level change, because the level is announced on it.
     */
    val endScreen = sessionId == null || s.levelChanged != null
    LaunchedEffect(s.done) { if (s.done && !endScreen) onDone() }

    if (s.done && endScreen) {
        DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            SuccessMark(visible = true)
            Spacer(Modifier.height(Sizes.gap))
            Text("Τέλος με το SQL!", style = MaterialTheme.typography.headlineMedium)
            if (s.levelChanged != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(
                    if (s.levelChanged!! > s.level) "Ανεβαίνεις στο επίπεδο ${s.levelChanged}. Μπράβο!"
                    else "Πάμε λίγο πιο εύκολα: επίπεδο ${s.levelChanged}.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val puzzle = s.puzzle
    DimitrisScreen(
        title = "SQL ${s.index + 1}/${s.total}",
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = {
            // Three at most, and «Άκου» is not one of them (docs/UX.md): the question said again,
            // at the top of the block, where it is on every other screen in the app.
            ListenButton(onClick = vm::listen, enabled = puzzle != null && !s.speaking)
            Spacer(Modifier.height(Sizes.gapSmall))
            when {
                // Answered, or shown after two misses: the question is over and the only way on is
                // «Επόμενο». Green only when he found it himself.
                s.correct == true -> BigButton("Επόμενο", onClick = vm::next, tone = ButtonTone.Success)
                s.revealed -> BigButton("Επόμενο", onClick = vm::next)
                // A board he hands in: the tiles as he laid them, or the query he wrote.
                puzzle?.kind == SqlPuzzleKind.ORDER || puzzle?.kind == SqlPuzzleKind.WRITE -> {
                    BigButton(
                        "Έτοιμο", onClick = vm::submit, enabled = ready(s, puzzle) && !s.running,
                    )
                    Spacer(Modifier.height(Sizes.gapSmall))
                    QuietButton("Παράλειψη", onClick = vm::skip)
                }
                // A board he taps: one tap is the whole answer, so there is nothing to hand in.
                else -> QuietButton("Παράλειψη", onClick = vm::skip)
            }
        },
    ) {
        // The five dots, on the first screen of the module and nowhere else (spec §13). Rebuilt on
        // the spot: the level is what decides which *kind* of question this is.
        if (sessionId == null && s.index == 0) ModuleDifficultyRow(ModuleId.SQL, onChanged = vm::reload)
        if (puzzle == null) {
            Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium)
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
            return@DimitrisScreen
        }
        val scroll = rememberScrollState()
        Column(Modifier.fillMaxWidth().verticalScroll(scroll).imePadding()) {
            Text(puzzle.question, style = MaterialTheme.typography.titleLarge)
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(Sizes.gapSmall))
            Shapes(s.shown)
            // The answer's own result: on two of the four boards it *is* the question.
            s.wanted?.let { grid ->
                Spacer(Modifier.height(Sizes.gapSmall))
                Text("Θέλουμε αυτό:", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                ResultGrid(grid)
            }
            Spacer(Modifier.height(Sizes.gap))
            when (puzzle.kind) {
                SqlPuzzleKind.ORDER -> OrderBoard(s, puzzle, vm)
                SqlPuzzleKind.PICK -> OptionBoard(s, puzzle.options, vm, mono = true)
                SqlPuzzleKind.KEYWORD -> {
                    Query(puzzle.blanked)
                    Spacer(Modifier.height(Sizes.gapSmall))
                    OptionBoard(s, puzzle.options, vm, mono = true)
                }
                SqlPuzzleKind.WRITE -> TypedBoard(s, vm)
            }
            // A miss says so in writing as well as by the buzz: the sound may be off, or missed.
            if (s.correct == false) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(
                    if (s.revealed) "Να το σωστό." else "Ξανά.",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.secondary,
                )
            }
            // The answer, once it has been shown: never a wall, and never without the thing to copy.
            if (s.revealed) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Query(if (puzzle.kind == SqlPuzzleKind.ORDER) puzzle.orderedAnswer else puzzle.target.text)
            }
            SuccessMark(visible = s.correct == true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(Sizes.gap))
        }
    }
}

/** Whether «Έτοιμο» has anything to hand in yet. */
private fun ready(s: SqlState, puzzle: SqlPuzzle): Boolean = when (puzzle.kind) {
    SqlPuzzleKind.ORDER -> s.chosen.size == puzzle.tiles.size
    SqlPuzzleKind.WRITE -> s.typed.isNotBlank()
    else -> false
}

/**
 * The shape of the tables the question is about: the name, and the columns with their types.
 *
 * On the screen from the first second and never withheld. The question is "can he write the query",
 * not "can he remember that the column is called `φορές`" — and a man who cannot retrieve a word is
 * not taught by being made to guess one, which is spec §12's rule about the model voice said about a
 * schema.
 */
@Composable
private fun Shapes(tables: List<SqlTable>) {
    for (table in tables) {
        Surface(
            shape = RoundedCornerShape(Sizes.corner), color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth().padding(bottom = Sizes.gapSmall),
        ) {
            // One line, the way a schema is written down: `users(id INTEGER, name TEXT, …)`. The
            // table name had a headline of its own until the level-5 board — two tables, and a
            // question about both — put the tiles he has to tap below the fold. The types stay: he
            // was a programmer, and `TEXT` is what says a value wants quotes round it.
            Text(
                table.columns.joinToString(", ", "${table.name}(", ")") { "${it.name} ${it.type.name}" },
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            )
        }
    }
}

/**
 * A result, as a plain grid: monospace, the header row in bold, and horizontally scrollable so a
 * wide answer never pushes the screen sideways.
 *
 * At most [ROWS_SHOWN] rows on the screen and the rest said as a number, because a grid he has to
 * scroll down through is a grid whose shape he cannot see — and the shape is the answer.
 */
@Composable
private fun ResultGrid(result: SqlResult) {
    Surface(
        shape = RoundedCornerShape(Sizes.corner), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp)) {
            if (result.empty) {
                Text("(καμία γραμμή)", style = MaterialTheme.typography.bodyLarge, fontFamily = FontFamily.Monospace)
                return@Column
            }
            val columns = result.columns.take(COLUMNS_SHOWN)
            val shown = result.rows.take(ROWS_SHOWN)
            // Laid out by column and not by row: a column is a [Column], so it takes the width of its
            // own widest cell and every cell in it lines up, without anybody having to guess a width.
            // A fixed one cut «κατηγορία» in half in the header of the first board that used it.
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(Sizes.gap),
            ) {
                columns.forEachIndexed { at, name ->
                    Column {
                        Cell(name, bold = true)
                        for (row in shown) Cell(row.getOrNull(at).orEmpty())
                    }
                }
            }
            if (result.rows.size > ROWS_SHOWN || result.truncated) {
                Text(
                    "… ${result.rows.size} γραμμές" + if (result.truncated) " ή περισσότερες" else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Cell(text: String, bold: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyLarge,
        fontFamily = FontFamily.Monospace,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        maxLines = 1,
    )
}

/** One line of SQL, as it is written: monospace, and never wrapped into something that is not SQL. */
@Composable
private fun Query(text: String) {
    Surface(
        shape = RoundedCornerShape(Sizes.corner), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** The tiles he has laid down, and the ones still on offer. */
@Composable
private fun OrderBoard(s: SqlState, puzzle: SqlPuzzle, vm: SqlViewModel) {
    Surface(
        shape = RoundedCornerShape(Sizes.corner), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().height(STRIP_HEIGHT),
    ) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (s.chosen.isEmpty()) {
                Text(
                    "Πάτα τις λέξεις με τη σειρά.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                // One row that scrolls sideways, at a fixed height, and never a second row: a strip
                // that grew as he laid tiles down pushed the board under his thumb *downwards* by a
                // tile's height, and the last word of the query ended up off the screen — on the one
                // board where the whole exercise is reaching every word in turn.
                //
                // Tapping a tile here takes it back — the undo, next to the thing it is about, which
                // is where a control goes when the bottom block is full.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
                ) {
                    for (tile in s.chosen) {
                        Tile(
                            tile, onClick = { vm.untap(tile) },
                            enabled = s.correct != true && !s.revealed, tag = SQL_CHOSEN_TAG, fill = false,
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(Sizes.gapSmall))
    Flow(puzzle.tiles) { tile ->
        // A tile already used is dimmed and dead rather than gone: a board that reshuffles itself
        // under his thumb is a board he has to read again every tap.
        Tile(
            tile, onClick = { vm.tap(tile) },
            enabled = s.correct != true && !s.revealed && tile !in s.chosen,
            tag = SQL_TILE_TAG,
        )
    }
}

/** The three options, one under the other so a whole query fits on a line he can read. */
@Composable
private fun OptionBoard(s: SqlState, options: List<String>, vm: SqlViewModel, mono: Boolean) {
    for (option in options) {
        Tile(
            option, onClick = { vm.choose(option) },
            enabled = s.correct != true && !s.revealed,
            tag = SQL_OPTION_TAG, mono = mono, wide = true,
        )
        Spacer(Modifier.height(Sizes.gapSmall))
    }
}

/** A keyboard, and whatever the database said about the last thing he handed in. */
@Composable
private fun TypedBoard(s: SqlState, vm: SqlViewModel) {
    // The field does **not** take the focus on arrival, which is the one place this module parts
    // company with «Προτάσεις».
    //
    // There the board is a picture and a word, and a keyboard that is already up saves a man with one
    // working hand from aiming at a text field. Here the three things above the field — the question,
    // the shape of the tables, and the result he is aiming at — are what he has to read *before* he
    // writes anything, and a keyboard that opens by itself scrolls every one of them off the top of
    // the screen. One tap on the field, when he is ready, costs less than arriving at a screen that
    // has already thrown away the question.
    OutlinedTextField(
        value = s.typed,
        onValueChange = vm::onTypedChange,
        // Never disabled while SQLite is reading: Material3 folds `enabled` into the field's own
        // `focusable`, so switching it off would take the focus and the keyboard away underneath him.
        readOnly = s.running || s.correct == true || s.revealed,
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        shape = RoundedCornerShape(Sizes.corner),
        // No autocapitalisation and no autocorrect: this is a keyboard for SQL, and a phone that
        // helpfully turns `λέξεις` into something else is the module's worst enemy.
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { vm.submit() }),
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)
            .testTag(SQL_TYPED_TAG),
    )
    if (s.running) {
        Spacer(Modifier.height(Sizes.gapSmall))
        Text("Τρέχω...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    // What SQLite said, in Greek, with its own English underneath — he was a programmer, and
    // «near "FORM": syntax error» is the most useful sentence on the screen.
    s.refusal?.let { line ->
        Spacer(Modifier.height(Sizes.gapSmall))
        Text(line, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        s.refusalDetail?.let {
            Text(
                it, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    // What his query actually returned, next to what was wanted: the comparison is the lesson.
    s.got?.let { grid ->
        Spacer(Modifier.height(Sizes.gapSmall))
        Text("Πήρες αυτό:", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ResultGrid(grid)
    }
}

/** A wrapping row of tiles. Compose has no flow layout that is stable here, so this is one. */
@Composable
private fun Flow(items: List<String>, item: @Composable (String) -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
        // One tile a row above this width, two below it: a tile is a word of SQL and it must never
        // be cut in half, and a `WHERE orders.item = 'καφές'` tile is most of a phone wide.
        val long = items.any { it.length > TILES_SIDE_BY_SIDE }
        if (long) {
            items.forEach { item(it) }
        } else {
            items.chunked(2).forEach { pair ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
                    pair.forEach { Box(Modifier.weight(1f)) { item(it) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * One tile: a word of SQL, at least 72 dp tall, in the monospace this whole module is written in.
 *
 * The same grey card the sentence builder lays its small words on, so a thing to be tapped looks the
 * same wherever in the app he meets one.
 */
@Composable
private fun Tile(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean,
    tag: String,
    mono: Boolean = true,
    wide: Boolean = false,
    /** False inside the sideways-scrolling strip, where the width is the word's and not the row's. */
    fill: Boolean = true,
) {
    val feedback = LocalFeedback.current
    Card(
        onClick = { feedback.tap(); onClick() },
        enabled = enabled,
        modifier = Modifier.then(if (wide) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = Sizes.touchMin).testTag(tag),
        shape = RoundedCornerShape(Sizes.corner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box(
            Modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier.widthIn(min = Sizes.touchMin))
                .heightIn(min = Sizes.touchMin).padding(12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleLarge,
                fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                textAlign = TextAlign.Center,
                maxLines = 3,
            )
        }
    }
}

/** How many rows of a result are drawn. Past this the shape is gone and the count is the answer. */
private const val ROWS_SHOWN = 10

/** How many columns. Nothing here selects more than four, and six is the width of a phone. */
private const val COLUMNS_SHOWN = 6

/** A tile longer than this gets a row of its own. */
private const val TILES_SIDE_BY_SIDE = 14

/**
 * The strip he lays the words into: one 72 dp tile and its padding, fixed.
 *
 * Fixed, and that is the point of it. While it grew with what he had put down, every tile he laid
 * pushed the board below it further down the screen — so on a four-tile query the last word was off
 * the bottom by the time he needed it, on the one board whose whole exercise is reaching each word in
 * turn. It scrolls sideways instead.
 */
private val STRIP_HEIGHT = 96.dp
