package gr.dimitris.app.modules.trace

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.settings.Settings
import gr.dimitris.app.modules.sentences.Tile
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.ListenButton
import gr.dimitris.app.ui.components.Pictogram
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.ui.components.ModuleDifficultyRow
import java.io.File

/** The paper. Tagged so a test can measure it and drag along the letter it is showing. */
const val TRACE_CANVAS_TAG = "trace-canvas"

/** The letter or word he is being asked to write. Tagged so a test can read what it was given. */
const val TRACE_TEXT_TAG = "trace-text"

/**
 * The same line while a recall word is hiding it. It keeps its place in the layout — the paper must
 * not jump the moment he looks away — so the tag is the only way to tell "there" from "gone", and a
 * test that could not tell them apart could not prove he ever wrote anything from memory.
 */
const val TRACE_TEXT_HIDDEN_TAG = "trace-text-hidden"

/**
 * The dictation level's row of letters: what he has written so far, and the empty slot he is on. It
 * is the only thing on that screen that says how far through the word he is, so a test that reads it
 * is reading exactly what he reads.
 */
const val TRACE_SLOTS_TAG = "trace-slots"

/** The one text field in «Γράψε»: the typed level's sentence, written with his left hand. */
const val TRACE_TYPED_TAG = "trace-typed"

/**
 * [count] letters or words, one per item the session budgeted for this module.
 *
 * Nothing on this screen scrolls. The canvas owns the drag — a page that moved under his finger
 * would take the letter with it — so everything else has to fit around it, and the canvas gives up
 * its own height when it does not: a shorter box is a smaller letter, and a hidden «Έτοιμο» is a
 * dead end.
 */
@Composable
fun TraceScreen(count: Int, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    // The count is part of the key: a resumed ViewModel would otherwise keep the old session's length.
    val vm: TraceViewModel = viewModel(key = "trace-${sessionId ?: "practice"}-$count") { TraceViewModel(graph, sessionId, count) }
    val s by vm.state.collectAsStateWithLifecycle()

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
            SuccessMark(visible = true)
            Spacer(Modifier.height(Sizes.gap))
            Text("Τέλος με το γράψιμο!", style = MaterialTheme.typography.headlineMedium)
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

    // Finished — the shape passed, or the judge accepted the sentence. Its own flag, because the
    // typed level has no score: see [TraceState.finished].
    val passed = s.finished
    val missed = s.score?.passed == false
    // A recall word shows the word once, then takes it away: from «Το είδα» on, he writes it himself.
    val recall = s.recall

    /** True in the stretch of a recall word when he is on his own: no word, no letter, blank paper. */
    val hidden = recall && !s.templateVisible

    DimitrisScreen(
        title = "Γράψε ${s.index + 1}/${s.total}",
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = {
            // Written: the only way on is «Επόμενο», and it is green because he got there himself.
            if (passed) BigButton("Επόμενο", onClick = vm::next, tone = ButtonTone.Success)
            // A picture and a keyboard: the same three actions «Προτάσεις» puts under its own typed
            // board, in the same order, because it is the same exercise. «Το έγραψα» is not among
            // them — it lives in the body, under the sentence it is about, so this block stays three.
            else if (s.variant == TraceVariant.TYPED) {
                ListenButton(onClick = vm::listen)
                Spacer(Modifier.height(Sizes.gapSmall))
                BigButton(
                    "Έτοιμο", onClick = vm::submitTyped, tone = ButtonTone.Success,
                    enabled = s.typed.isNotBlank() && !s.checking,
                )
                Spacer(Modifier.height(Sizes.gapSmall))
                // Off while the judge reads, so it is not a button that does nothing: the verdict he
                // is waiting for belongs to this board, and passing on it now would throw his own
                // sentence away. The wait is the judge's eight seconds at the very most.
                QuietButton("Παράλειψη", onClick = vm::skip, enabled = !s.checking)
            }
            // The dictation level, where the word is only ever a sound. «Άκου» and «Καθάρισε» share
            // one slot the way «Το είδα» and «Έτοιμο» do below, and for the same reason: three
            // actions, one of them whichever is the real next step. With a clean sheet that is
            // hearing the word again — there is nothing to wipe and nothing to hand in — and the
            // moment there is ink on it, it is wiping the ink.
            else if (s.variant == TraceVariant.DICTATION) {
                Row {
                    if (s.strokes.isEmpty()) ListenButton(onClick = vm::listen, modifier = Modifier.weight(1f))
                    else QuietButton("Καθάρισε", onClick = vm::clear, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Sizes.gapSmall))
                    BigButton(
                        "Έτοιμο", onClick = vm::check, tone = ButtonTone.Success,
                        enabled = s.fresh.isNotEmpty(), modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton("Παράλειψη", onClick = vm::skip)
            }
            else {
                // Three actions and one primary, on every level (spec §13, `docs/UX.md`). At level 5
                // «Το είδα» used to stand *above* this row, which made four buttons and two loud
                // ones — and the «Έτοιμο» beside it was dead anyway, because there is nothing to
                // hand in until he has taken the letter away. So the two share one slot: the primary
                // is whichever of them is the actual next step.
                //
                // His tap is not the only thing that moves it. A missed try brings the letter back
                // over his strokes (`TraceViewModel.check`), so after a miss the slot reads «Το είδα»
                // again — which is the honest next step, because the letter is on the screen and it
                // has to come off before anything can be handed in. The cost is that `hide()` clears
                // the strokes with it, so the faded ink the nudge put there goes when he presses it:
                // the letter was what he needed to see, and by then he has seen it.
                val hiding = recall && s.templateVisible
                Row {
                    // Dead until there is ink to wipe, so it cannot be the button he learns to press.
                    QuietButton("Καθάρισε", onClick = vm::clear, enabled = s.strokes.isNotEmpty(), modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Sizes.gapSmall))
                    if (hiding) {
                        BigButton("Το είδα", onClick = vm::hide, modifier = Modifier.weight(1f))
                    } else {
                        BigButton(
                            "Έτοιμο", onClick = vm::check, tone = ButtonTone.Success,
                            // The ink of the try he is on, not everything on the paper: after a nudge
                            // the faded strokes are there to look at, not to hand in again.
                            enabled = s.fresh.isNotEmpty(),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton("Παράλειψη", onClick = vm::skip)
            }
        },
    ) {
        // The five dots, on the first screen of the module and nowhere else (spec §13): how hard
        // this is, is his to set — and the middle of a mixed session is no place to be asked.
        if (sessionId == null && s.index == 0) ModuleDifficultyRow(ModuleId.TRACE, onChanged = vm::reload)
        if (s.text.isEmpty()) { Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium); return@DimitrisScreen }

        // He asked for the sentence level and the judge cannot answer, so this sitting is the word
        // level's work. Said once, on the first screen beside the dots, and then out of his way: it is
        // a sentence for whoever reads it over his shoulder, and nothing he can act on himself.
        if (s.noJudge && s.index == 0) {
            Text(
                TraceViewModel.NEEDS_JUDGE, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.secondary,
            )
            Spacer(Modifier.height(Sizes.gapSmall))
        }

        // A picture and a keyboard: no paper, no glyph, and the one exercise in this module that is
        // not a shape at all.
        if (s.variant == TraceVariant.TYPED) {
            TypedPaper(s, vm, picture = { tile -> tile.item.imagePath?.let { graph.files.resolve(it) } })
            return@DimitrisScreen
        }

        // Everything but the title, measured before a word of it is laid out, so the one thing that
        // must never be squeezed to nothing — the paper — can be given its room first.
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // On a short screen at a large font scale there is not room for both the hint and a
            // paper worth writing on. The paper wins: the hand is the same every day, and a canvas
            // too small to write in is a letter he cannot pass.
            val roomForHint = maxHeight >= HINT_NEEDS
            Column(Modifier.fillMaxSize()) {
                if (roomForHint) {
                    Text(
                        if (s.hand == Settings.HAND_RIGHT) "Με το δεξί χέρι" else "Με το αριστερό χέρι",
                        style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(Sizes.gapSmall))
                }

                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    val dictation = s.dictation
                    if (dictation != null) {
                        // The word he heard is never written down: what stands here is how far through
                        // it he has got — the letters he has had accepted, small, and the empty slot
                        // he is writing into now.
                        Text(
                            slots(dictation),
                            style = MaterialTheme.typography.displaySmall,
                            maxLines = 1, textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f).testTag(TRACE_SLOTS_TAG),
                        )
                    } else {
                        // On a recall word the word goes on holding its place after «Το είδα» —
                        // invisible, not gone, so the canvas does not jump the moment he looks away.
                        Text(
                            s.text,
                            // «ΔΗΜΗΤΡΗΣ» at 44 sp is wider than a phone; a long word steps down a size
                            // rather than being cut off, because the word is what he is being asked for.
                            style = if (s.text.length > LONG_TEXT) MaterialTheme.typography.headlineMedium
                            else MaterialTheme.typography.displayLarge,
                            maxLines = 1, textAlign = TextAlign.Start,
                            modifier = Modifier.weight(1f)
                                .alpha(if (hidden) 0f else 1f)
                                .testTag(if (hidden) TRACE_TEXT_HIDDEN_TAG else TRACE_TEXT_TAG),
                        )
                    }
                    // Smaller than the letter beside it, so the tick arriving cannot move the canvas.
                    SuccessMark(visible = passed, size = TICK)
                }
                // A miss says so in writing as well as with the buzz: the sound may be off, or
                // missed. The strokes stay where they are and the letter comes back under them.
                if (missed) {
                    Text(
                        // Too much ink is its own sentence, and so is a word with one letter wrong:
                        // «Ξανά» tells him to do it again, and which letter is what he needs.
                        TraceViewModel.tryAgain(s.score),
                        style = MaterialTheme.typography.headlineMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                if (s.error != null) {
                    Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
                }
                Spacer(Modifier.height(Sizes.gapSmall))

                // The paper is the biggest one that fits above the buttons. A word keeps its taller
                // shape, capped by whichever of the two directions runs out first; a single letter
                // takes a square of the slot — square, because the box has to change in both
                // directions by the same factor when the slot does. The glyph is re-fitted with one
                // uniform scale, so a box that stretched in y alone would slide his own ink sideways
                // off the letter it is being marked against.
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
                    // One letter at a time at the dictation level, however long the word is: the paper
                    // is a square for the slot he is on, not a strip for a word that is never shown.
                    val word = s.text.length > 1 && s.dictation == null
                    val height = maxHeight.coerceAtLeast(MIN_PAPER)
                    val width = if (word) minOf(maxWidth, height * WORD_BOX) else minOf(maxWidth, height)
                    // The floor is a floor: `size` would hand back whatever the slot had, and a slot
                    // squeezed to nothing (a short screen at a large font scale) left him a canvas he
                    // could not write on and an «Έτοιμο» that could never light up. In that one case
                    // the paper keeps its size and this box scrolls instead.
                    val tight = maxHeight < MIN_PAPER
                    Box(
                        Modifier.fillMaxSize()
                            .then(if (tight) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                    ) {
                        TraceCanvas(
                            template = s.target.points,
                            // Always there to follow, except where the point is that it is not: a
                            // recall word after «Το είδα», and every fresh slot of a dictated word.
                            showTemplate = s.templateVisible && s.target.points.isNotEmpty(),
                            // The letters that missed, marked on the paper. A word of eight letters
                            // and one word — «Ξανά» — is not something anybody can act on.
                            highlight = s.failedLetters,
                            strokes = s.strokes,
                            // The tries that have already been marked, drawn faded: he can see where
                            // he went, and what he writes now is judged on its own.
                            judged = s.judged,
                            onStroke = vm::addStroke,
                            modifier = Modifier.requiredSize(width, if (word) width / WORD_BOX else width)
                                .align(Alignment.TopCenter)
                                .onSizeChanged { vm.setCanvasSize(it.width.toFloat(), it.height.toFloat()) }
                                .testTag(TRACE_CANVAS_TAG),
                            enabled = !passed,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The dictated word as the row above the paper shows it: the letters he has had accepted, small, and
 * the empty slot he is writing into now.
 *
 * Small, because they are not what he is doing — they are what he has done, and the one letter that
 * matters is the one with nothing in it yet. The whole word is never here: it is a sound, and putting
 * it on the screen would be the exercise given away.
 */
private fun slots(dictation: Dictation): AnnotatedString = buildAnnotatedString {
    // Finished, the word is no longer a row of slots: it is the word he wrote from hearing, and it
    // stands at the size every other level shows the thing he has just written. A trailing «_» under
    // the tick would say there is one more letter to come.
    if (dictation.done) append(dictation.accepted)
    else {
        withStyle(SpanStyle(fontSize = ACCEPTED_LETTER)) { append(dictation.accepted) }
        append(TraceViewModel.SLOT)
    }
}

/**
 * The typed level: a picture, the one instruction in «Γράψε» that asks for a keyboard, and the field
 * he writes the sentence in.
 *
 * Laid out exactly as «Προτάσεις» lays out its own typed board, and for the reasons written there:
 * the column scrolls because the keyboard takes two thirds of the phone, the field is `readOnly`
 * rather than disabled while the judge reads it (disabling it takes the focus and the keyboard with
 * it), and the focus comes back the moment the answer lands so his next act is to write rather than
 * to aim at a text field with his left hand. The `imePadding` is the local statement of the same
 * intent [DimitrisScreen]'s own `safeDrawingPadding` already carries one level up.
 */
@Composable
private fun TypedPaper(s: TraceState, vm: TraceViewModel, picture: (Tile) -> File?) {
    val focus = remember { FocusRequester() }
    // One request per board, so the keyboard is up before he has to look for it.
    LaunchedEffect(s.index) { runCatching { focus.requestFocus() } }
    // And again the moment the judge has finished, unless the sentence counted and the only button
    // left is «Επόμενο».
    LaunchedEffect(s.checking) { if (!s.checking && !s.finished) runCatching { focus.requestFocus() } }
    val scroll = rememberScrollState()
    // A refusal puts the sentence to copy and «Το έγραψα» at the foot of a column that is a third of a
    // phone tall with the keyboard up, so the column is brought down to them.
    LaunchedEffect(s.whole, scroll.maxValue) {
        if (s.whole != null) runCatching { scroll.animateScrollTo(scroll.maxValue) }
    }
    Column(Modifier.fillMaxWidth().verticalScroll(scroll).imePadding()) {
        // The picture goes once there is a sentence to copy: with the keyboard up, the three things he
        // needs are the sentence, the field and the button. It comes back on the next board.
        if (s.whole == null) s.sentence?.picture?.let { card ->
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Pictogram(picture(card), size = Sizes.pictureCard)
            }
            Text(
                TraceViewModel.WRITE_IT, style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
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
            readOnly = s.checking || s.finished,
            textStyle = MaterialTheme.typography.headlineSmall,
            shape = RoundedCornerShape(Sizes.corner),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { vm.submitTyped() }),
            modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)
                .focusRequester(focus).testTag(TRACE_TYPED_TAG),
        )
        if (s.checking) {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text("Διαβάζω...", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        // The judge's one warm line, whichever way the sentence went.
        s.feedback?.let {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.secondary)
        }
        // What he wrote did not land: the whole sentence, to copy or to confirm. Never «λάθος», and
        // never «Σχεδόν.» either — this module's answer to a miss is the right answer, written out.
        s.whole?.let { whole ->
            Spacer(Modifier.height(Sizes.gapSmall))
            Text("${TraceViewModel.CORRECTION} $whole", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Sizes.gapSmall))
            // Here rather than in the bottom slot, so that block stays «Άκου» / «Έτοιμο» / «Παράλειψη».
            QuietButton(TraceViewModel.I_WROTE_IT, onClick = vm::confirmTyped)
        }
        if (s.error != null) {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A word needs the room, so its box is taller than it is wide. A single letter takes the lot. */
private const val WORD_BOX = 0.9f

/** The letters of a dictated word he has already written: there to be read, not to be worked on. */
private val ACCEPTED_LETTER = 28.sp

/** Below this the hand hint goes, so the paper does not. */
private val HINT_NEEDS = 420.dp

/** Paper smaller than this is not writeable; below it the layout spills rather than the letter. */
private val MIN_PAPER = 200.dp

/** Longer than this and the prompt steps down a size to stay on one line. */
private const val LONG_TEXT = 6

/** No taller than the letter it sits next to: a tick that changes the layout moves his paper. */
private val TICK = 56.dp
