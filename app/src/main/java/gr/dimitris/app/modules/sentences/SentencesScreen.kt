package gr.dimitris.app.modules.sentences

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.ListenButton
import gr.dimitris.app.ui.components.PictureCard
import gr.dimitris.app.ui.components.Pictogram
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes
import java.io.File
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.ui.components.ModuleDifficultyRow

/** Every card on the sentence board carries it, so a test can read what is on offer and tap it. */
const val SENTENCE_TILE_TAG = "sentence-tile"

/** Every one of the three small words a gap board offers. */
const val SENTENCE_GAP_TAG = "sentence-gap"

/** The one text field in the app that Dimitris himself types into. */
const val SENTENCE_TYPED_TAG = "sentence-typed"

/** [count] sentences, one per item the session budgeted for this module. */
@Composable
fun SentencesScreen(count: Int, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    // The count is part of the key: a resumed ViewModel would otherwise keep the old session's length.
    val vm: SentencesViewModel = viewModel(key = "sentences-${sessionId ?: "practice"}-$count") { SentencesViewModel(graph, sessionId, count) }
    val s by vm.state.collectAsStateWithLifecycle()

    val picture: (Tile) -> File? = { tile -> tile.item.imagePath?.let { graph.files.resolve(it) } }

    /**
     * A mixed session ends with its own summary, so a second "well done" screen in the middle of it
     * is one tap of noise: the module hands straight back. Free practice keeps the end screen — it is
     * the only ending there is — and so does a level change, whichever way it went, because the level
     * is announced on it and he has to be able to read it before it goes.
     */
    val endScreen = sessionId == null || s.levelChanged != null
    LaunchedEffect(s.done) { if (s.done && !endScreen) onDone() }

    if (s.done && endScreen) {
        DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            // Nothing to build: every word on the device was deleted, or none has arrived yet. He
            // is told why and let straight out; «Εντάξει» below is the way on, and inside a session
            // the module has already handed back without showing him anything.
            if (s.sentence == null) {
                Text("Χρειάζονται περισσότερες λέξεις.", style = MaterialTheme.typography.headlineMedium)
                Spacer(Modifier.height(Sizes.gapSmall))
                Text("Ζήτα από κάποιον να προσθέσει.", style = MaterialTheme.typography.bodyLarge)
                return@DimitrisScreen
            }
            SuccessMark(visible = true)
            Spacer(Modifier.height(Sizes.gap))
            Text("Τέλος με τις προτάσεις!", style = MaterialTheme.typography.headlineMedium)
            if (s.levelChanged != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(
                    if (s.levelChanged!! > s.level) "Ανεβαίνεις στο επίπεδο ${s.levelChanged}. Μπράβο!"
                    else "Πάμε λίγο πιο εύκολα: επίπεδο ${s.levelChanged}.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            // The end screen speaks too, so a silent phone has to be said here as well.
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val variant = s.sentence?.variant ?: Variant.BUILD
    DimitrisScreen(
        title = "Προτάσεις ${s.index + 1}/${s.total}",
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = {
            // «Άκου» says the whole sentence, in the order the cards have to go down. There is no
            // ladder here to withhold it behind, and nothing to earn it with: it is the first thing
            // in the bottom block, above «Παράλειψη», at its full width. On a typed board it is the
            // sentence he is being asked to write, which is the same answer said out loud.
            ListenButton(onClick = vm::listenModel, enabled = s.sentence != null && !s.modelPlaying)
            Spacer(Modifier.height(Sizes.gapSmall))
            when {
                // Finished: the only way on is «Επόμενο», and it is green because he found it himself.
                s.correct == true -> BigButton("Επόμενο", onClick = vm::next, tone = ButtonTone.Success)
                // Three actions down here and never four: «Το έγραψα» lives in the body, under the
                // sentence it is about — the move «Βοήθεια» already made in `ScriptsScreen`, and for
                // the same reason. A block under his thumb that grows a fourth button between one
                // frame and the next is one more thing than one working thumb should have to choose
                // between.
                variant == Variant.TYPED -> {
                    BigButton("Έτοιμο", onClick = vm::submitTyped, enabled = s.typed.isNotBlank() && !s.checking)
                    Spacer(Modifier.height(Sizes.gapSmall))
                    // Live through «Διαβάζω...»: a skip cancels the reading and passes on the board
                    // ([SentencesViewModel.skip]), rather than being a button that does nothing for
                    // the judge's eight seconds.
                    QuietButton("Παράλειψη", onClick = vm::skip)
                }
                // A gap board has nothing to take back: one tap is the whole answer.
                variant == Variant.GAP -> QuietButton("Παράλειψη", onClick = vm::skip)
                else -> Row {
                    QuietButton(
                        "", onClick = vm::undo, icon = Icons.AutoMirrored.Rounded.Backspace,
                        contentDescription = "Σβήσε το τελευταίο", iconOnly = true,
                        enabled = s.chosen.isNotEmpty(), modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(Sizes.gapSmall))
                    QuietButton("Παράλειψη", onClick = vm::skip, modifier = Modifier.weight(2f))
                }
            }
        },
    ) {
        // The five dots, on the first screen of the module and nowhere else (spec §13): how hard
        // this is, is his to set — and the middle of a mixed session is no place to be asked.
        if (sessionId == null && s.index == 0) ModuleDifficultyRow(ModuleId.SENTENCES, onChanged = vm::reload)
        val sentence = s.sentence
        if (sentence == null) { Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium); return@DimitrisScreen }

        when (sentence.variant) {
            Variant.GAP -> GapBoard(s, sentence, vm)
            Variant.TYPED -> TypedBoard(s, sentence, vm, picture)
            Variant.BUILD -> BuildBoard(s, sentence, vm, picture)
        }
    }
}

/** The board as it always was: the cards, in the order they have to go down. */
@Composable
private fun BuildBoard(s: SentencesState, sentence: Sentence, vm: SentencesViewModel, picture: (Tile) -> File?) {
    ChosenStrip(s.chosen, done = s.correct == true, picture = picture)

    // An order that was not the sentence is answered in writing as well as out loud: the sound
    // may be off, or missed. The sentence stays on the screen while he builds it again —
    // copying it is the exercise, and it is never called a mistake.
    if (s.correct == false) Correction(sentence.text)
    if (s.error != null) ScreenError(s.error)
    Spacer(Modifier.height(Sizes.gapSmall))

    LazyVerticalGrid(
        // Two big cards a row for a sentence of three or four words, three when the board also
        // carries the odd one out: five cards in two columns is a third row below the fold, and
        // a card he has to scroll to find is a card that is not on offer. From level 5 the
        // sentences are longer than any board can hold in two rows, and the grid scrolls.
        columns = GridCells.Fixed(if (s.shuffledTiles.size > CARDS_IN_TWO_ROWS) 3 else 2),
        horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
        verticalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
        modifier = Modifier.fillMaxWidth(),
    ) {
        items(s.shuffledTiles, key = { it.item.id }) { tile ->
            // A card he has already used is dimmed and dead rather than gone: a board that
            // reshuffles itself under his thumb is a board he has to read again every tap. Once
            // the sentence is right the whole board goes quiet — the odd card out at level 4 is
            // the only one still lit, and a buzz from it would be an answer to a finished question.
            val live = s.correct != true && s.chosen.none { it.item.id == tile.item.id }
            if (tile.madeUp) {
                WordCard(tile.label, onClick = { vm.tap(tile) }, enabled = live, modifier = Modifier.testTag(SENTENCE_TILE_TAG))
            } else {
                PictureCard(
                    imageFile = picture(tile), label = tile.label, onClick = { vm.tap(tile) },
                    enabled = live, modifier = Modifier.testTag(SENTENCE_TILE_TAG),
                )
            }
        }
    }
}

/**
 * The sentence with one small word taken out of it, and the three that could go there.
 *
 * The whole sentence is on the screen from the first second: the question is not "what does this
 * say?" but "which of these three does it want?", and a man with Broca's aphasia reads perfectly
 * well. It is the producing that is hard, and one word is the smallest amount of producing there is.
 */
@Composable
private fun GapBoard(s: SentencesState, sentence: Sentence, vm: SentencesViewModel) {
    Surface(
        shape = RoundedCornerShape(Sizes.corner), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(
                SentencesViewModel.WHICH_WORD, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(sentence.withBlank(SentencesViewModel.BLANK), style = MaterialTheme.typography.headlineMedium)
        }
    }
    if (s.correct == false) Correction(sentence.text)
    if (s.error != null) ScreenError(s.error)
    Spacer(Modifier.height(Sizes.gap))
    // The same grey word cards the builder lays the small words out on, so «στο» looks like «στο»
    // wherever in the module he meets it — and «Άκου» stays the only filled button on the screen.
    Row(horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall), modifier = Modifier.fillMaxWidth()) {
        for (option in sentence.gap?.options.orEmpty()) {
            WordCard(
                option, onClick = { vm.chooseGap(option) }, enabled = s.correct != true,
                modifier = Modifier.weight(1f).testTag(SENTENCE_GAP_TAG),
            )
        }
    }
}

/**
 * A picture, and a keyboard.
 *
 * The only place in the app that asks him to produce every word of a sentence himself, which is why
 * it is the last two levels and one board in three of them. The picture is what the sentence is
 * about and the only thing on the screen: the words are his.
 *
 * Scrollable, because the keyboard takes two thirds of the phone. What actually holds everything
 * clear of it is [DimitrisScreen]'s own `safeDrawingPadding`, one level up: the safe-drawing insets
 * include the IME, so the whole screen — the bottom block included — is already laid out above the
 * keyboard, and the scroll here is what lets him reach the picture in the third of the phone that is
 * left. The `imePadding` below is a no-op while that stays true and is kept as the local statement
 * of the same intent, so this column keeps working if it is ever lifted out of that screen.
 */
@Composable
private fun TypedBoard(s: SentencesState, sentence: Sentence, vm: SentencesViewModel, picture: (Tile) -> File?) {
    val focus = remember { FocusRequester() }
    // One request per board, so the keyboard is already up when he arrives and the first thing he
    // does is write rather than aim at a text field with his left hand.
    LaunchedEffect(s.index) { runCatching { focus.requestFocus() } }
    // And again the moment the judge has finished. He presses «Έτοιμο», waits up to eight seconds,
    // and the answer lands: without this the keyboard would have to be found and hit again with his
    // left hand before he could change a word of what he wrote.
    LaunchedEffect(s.checking) {
        if (!s.checking && s.correct != true) runCatching { focus.requestFocus() }
    }
    val scroll = rememberScrollState()
    // A refusal puts the sentence to copy and «Το έγραψα» at the foot of a column that is a third of
    // a phone tall with the keyboard up, so the column is brought down to them. Keyed on the extent
    // as well as on the answer, because the correction has to be laid out before there is anything to
    // scroll to; once it is, this settles in one more pass.
    LaunchedEffect(s.whole, scroll.maxValue) {
        if (s.whole != null) runCatching { scroll.animateScrollTo(scroll.maxValue) }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(scroll).imePadding()) {
        // The picture goes once there is a sentence to copy. With the keyboard up this column is a
        // third of a phone tall, and after a refusal the three things he needs in it are the sentence,
        // the field and the button — the picture has done its work, and the sentence names the word
        // it was of. It comes back on the next board.
        if (s.whole == null) sentence.picture?.let { card ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Pictogram(picture(card), size = Sizes.pictureCard)
            }
            // The instruction first and the word under it, so the two read as one line: «Γράψε μια
            // ερώτηση για: φαρμακείο». Level 8 wants a question and has to say so — see
            // [SentencesViewModel.WRITE_A_QUESTION] — and the judge is handed this same line.
            Text(
                if (sentence.question) SentencesViewModel.WRITE_A_QUESTION else SentencesViewModel.WRITE_IT,
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                card.item.text, style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(Sizes.gapSmall))
        OutlinedTextField(
            value = s.typed,
            onValueChange = vm::onTypedChange,
            // Never disabled while the judge is reading: Material3 folds `enabled` into the field's
            // own `focusable`, so switching it off takes the focus away and the keyboard with it, and
            // he would come back from an eight-second wait to a screen he has to aim at again.
            // `readOnly` stops the typing without touching the focus.
            readOnly = s.checking || s.correct == true,
            textStyle = MaterialTheme.typography.headlineSmall,
            shape = RoundedCornerShape(Sizes.corner),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.submitTyped() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)
                .focusRequester(focus).testTag(SENTENCE_TYPED_TAG),
        )
        if (s.checking) {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text("Διαβάζω...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The judge's one warm line, whichever way the sentence went. On an accepted one it is the
        // only thing said about a sentence he got right, and it used to be fetched and thrown away.
        s.feedback?.let {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
        }
        // What he wrote did not land: the whole sentence, to copy or to confirm. Never «λάθος».
        s.whole?.let { whole ->
            Correction(whole)
            Spacer(Modifier.height(Sizes.gapSmall))
            // Here rather than in the bottom slot, so that block stays «Άκου» / «Έτοιμο» /
            // «Παράλειψη» — and so the button sits under the sentence it is about.
            QuietButton(SentencesViewModel.I_WROTE_IT, onClick = vm::confirmTyped)
        }
        if (s.error != null) ScreenError(s.error)
    }
}

/** «Σχεδόν.» and the sentence to copy. The one answer this module gives to an order it did not want. */
@Composable
private fun Correction(whole: String) {
    Spacer(Modifier.height(Sizes.gapSmall))
    Text(SentencesViewModel.WRONG_ORDER, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
    Text("${SentencesViewModel.CORRECTION} $whole", style = MaterialTheme.typography.headlineMedium)
}

@Composable
private fun ScreenError(error: String?) {
    if (error == null) return
    Spacer(Modifier.height(Sizes.gapSmall))
    Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
}

/**
 * A card with no picture behind it: one of the small words the sentence needs. They look different
 * from his own vocabulary on purpose — «ο» and «στο» are not things he has a picture of, and drawing
 * a photo frame round them would say they were.
 */
@Composable
private fun WordCard(label: String, onClick: () -> Unit, enabled: Boolean, modifier: Modifier = Modifier) {
    val feedback = LocalFeedback.current
    Card(
        onClick = { feedback.tap(); onClick() },
        enabled = enabled,
        modifier = modifier.heightIn(min = Sizes.touchMin),
        shape = RoundedCornerShape(Sizes.corner),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
            disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box(Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin).padding(12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, maxLines = 2)
        }
    }
}

/**
 * The sentence so far, as pictures over words — the same strip the talk board has, so building a
 * sentence looks the same wherever he does it, and he can check his own before it is judged.
 */
@Composable
private fun ChosenStrip(chosen: List<Tile>, done: Boolean, picture: (Tile) -> File?) {
    // The newest word is the one being chosen, so it is the one that has to be on screen.
    val stripState = rememberLazyListState()
    LaunchedEffect(chosen.size) { stripState.animateScrollToItem(chosen.lastIndex.coerceAtLeast(0)) }
    Surface(
        shape = RoundedCornerShape(Sizes.corner), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (chosen.isEmpty()) {
                    Text(
                        "Πάτα τις εικόνες με τη σειρά.", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    // No key: position is what identifies a word here, not which card it came from.
                    LazyRow(state = stripState, horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
                        items(chosen) { tile ->
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                // A small word has no picture to put over it, and the placeholder
                                // frame over «στο» would be a picture that says "missing".
                                if (!tile.madeUp) Pictogram(picture(tile))
                                Text(tile.label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 1)
                            }
                        }
                    }
                }
            }
            SuccessMark(visible = done, size = Sizes.stripPicture)
        }
    }
}

/** How many cards two rows of two hold on a phone, with the sentence and the correction above them. */
private const val CARDS_IN_TWO_ROWS = 4
