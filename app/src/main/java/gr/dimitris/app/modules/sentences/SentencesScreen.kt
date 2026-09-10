package gr.dimitris.app.modules.sentences

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.ListenButton
import gr.dimitris.app.ui.components.PictureCard
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes
import java.io.File
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.ui.components.ModuleDifficultyRow

/** Every card on the sentence board carries it, so a test can read what is on offer and tap it. */
const val SENTENCE_TILE_TAG = "sentence-tile"

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

    DimitrisScreen(
        title = "Προτάσεις ${s.index + 1}/${s.total}",
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = {
            // «Άκου» says the whole sentence, in the order the cards have to go down. There is no
            // ladder here to withhold it behind, and nothing to earn it with: it is the first thing
            // in the bottom block, above «Παράλειψη», at its full width.
            ListenButton(onClick = vm::listenModel, enabled = s.sentence != null && !s.modelPlaying)
            Spacer(Modifier.height(Sizes.gapSmall))
            // Finished: the only way on is «Επόμενο», and it is green because he found it himself.
            if (s.correct == true) BigButton("Επόμενο", onClick = vm::next, tone = ButtonTone.Success)
            else Row {
                QuietButton(
                    "", onClick = vm::undo, icon = Icons.AutoMirrored.Rounded.Backspace,
                    contentDescription = "Σβήσε το τελευταίο", iconOnly = true,
                    enabled = s.chosen.isNotEmpty(), modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(Sizes.gapSmall))
                QuietButton("Παράλειψη", onClick = vm::skip, modifier = Modifier.weight(2f))
            }
        },
    ) {
        // The five dots, on the first screen of the module and nowhere else (spec §13): how hard
        // this is, is his to set — and the middle of a mixed session is no place to be asked.
        if (sessionId == null && s.index == 0) ModuleDifficultyRow(ModuleId.SENTENCES, onChanged = vm::reload)
        val sentence = s.sentence
        if (sentence == null) { Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium); return@DimitrisScreen }

        ChosenStrip(s.chosen, done = s.correct == true, picture = picture)

        // An order that was not the sentence is answered in writing as well as out loud: the sound
        // may be off, or missed. The sentence stays on the screen while he builds it again —
        // copying it is the exercise, and it is never called a mistake.
        if (s.correct == false) {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(SentencesViewModel.WRONG_ORDER, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
            Text("Σωστά: ${sentence.text}", style = MaterialTheme.typography.headlineMedium)
        }
        if (s.error != null) {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(Modifier.height(Sizes.gapSmall))

        LazyVerticalGrid(
            // Two big cards a row for a sentence of three or four words, three when the board also
            // carries the odd one out: five cards in two columns is a third row below the fold, and
            // a card he has to scroll to find is a card that is not on offer.
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
                PictureCard(
                    imageFile = picture(tile), label = tile.label, onClick = { vm.tap(tile) },
                    enabled = s.correct != true && s.chosen.none { it.item.id == tile.item.id },
                    modifier = Modifier.testTag(SENTENCE_TILE_TAG),
                )
            }
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
                                Pictogram(picture(tile))
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

/** A picture that was deleted or moved falls back to the placeholder, so the word still works. */
@Composable
private fun Pictogram(file: File?) {
    Box(Modifier.size(Sizes.stripPicture), contentAlignment = Alignment.Center) {
        if (file != null && file.exists()) {
            AsyncImage(
                model = file, contentDescription = null, contentScale = ContentScale.Fit,
                error = rememberVectorPainter(Icons.Rounded.Image), modifier = Modifier.size(Sizes.stripPicture),
            )
        } else {
            Icon(
                Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(Sizes.stripPicture),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** How many cards two rows of two hold on a phone, with the sentence and the correction above them. */
private const val CARDS_IN_TWO_ROWS = 4
