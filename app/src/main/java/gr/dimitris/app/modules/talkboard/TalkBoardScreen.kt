package gr.dimitris.app.modules.talkboard

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil3.compose.AsyncImage
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.speech.GentleCheck
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.ListeningIndicator
import gr.dimitris.app.ui.components.PictureCard
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes
import java.io.File

/** The scrolling grid of pictures. Tagged so a test can scroll to a word that starts below the fold. */
const val BOARD_GRID_TAG = "board-grid"

/** «Ολόκληρη», and the two controls the expansion puts in its place. Tagged because «Πες το» is a word
 * the board already uses at the bottom for the other thing a sentence can do: be read out to him. */
const val EXPAND_TAG = "expand-whole"
const val EXPAND_SAY_TAG = "expand-say"
const val EXPAND_SENTENCE_TAG = "expand-sentence"

/** The one new button: the content words he tapped, as one whole Greek sentence. */
const val WHOLE = "Ολόκληρη"

/** «Πες το» here is him saying it, not the phone — hence the microphone on it. */
const val SAY_IT_BACK = "Πες το"
const val CLOSE_EXPANSION = "Κλείσε"
const val SAID_IT = "Το είπα!"

/** While the judge is being asked. A fact, not a countdown — there is no timer anywhere near him. */
const val MAKING_SENTENCE = "Φτιάχνω την πρόταση…"

@Composable
fun TalkBoardScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    // A Flow from a closed database never emits again, so a backup import gets a new view model.
    val generation by graph.dbGeneration.collectAsStateWithLifecycle()
    val vm: TalkBoardViewModel = viewModel(key = "talkboard-$generation") { TalkBoardViewModel(graph) }
    val strip by vm.strip.items.collectAsStateWithLifecycle()
    val stripFull by vm.stripFull.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val quick by vm.quick.collectAsStateWithLifecycle()
    val shown by vm.shown.collectAsStateWithLifecycle()
    val speechError by vm.speechError.collectAsStateWithLifecycle()
    val spoken by vm.spoken.collectAsStateWithLifecycle()
    val judgeReady by vm.judgeReady.collectAsStateWithLifecycle()
    val expansion by vm.expansion.collectAsStateWithLifecycle()
    val listenLevel by vm.listenLevel.collectAsStateWithLifecycle()

    // Recognition opens the microphone, so it asks for the same permission the modules ask for.
    val askListen = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.sayExpansion() else vm.micDenied()
    }

    val picture: (Item) -> File? = { item -> item.imagePath?.let { graph.files.resolve(it) } }

    DimitrisScreen(
        title = "Μίλα",
        onBack = onBack,
        talkButton = false,
        bottom = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BigButton("Πες το", onClick = vm::speakStrip, icon = Icons.Rounded.VolumeUp, tone = ButtonTone.Secondary,
                    enabled = strip.isNotEmpty(), modifier = Modifier.weight(2f))
                Spacer(Modifier.width(Sizes.gapSmall))
                QuietButton("", onClick = vm::undo, icon = Icons.AutoMirrored.Rounded.Backspace,
                    contentDescription = "Σβήσε το τελευταίο", iconOnly = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Sizes.gapSmall))
                QuietButton("", onClick = vm::clear, icon = Icons.Rounded.Clear,
                    contentDescription = "Καθάρισε", iconOnly = true, modifier = Modifier.weight(1f))
            }
        },
    ) {
        StripRow(
            items = strip,
            full = stripFull,
            spoken = spoken,
            showsExpand = showsExpand(strip.size, judgeReady),
            expansion = expansion,
            listenLevel = listenLevel,
            onExpand = vm::expand,
            onSay = { askListen.launch(Manifest.permission.RECORD_AUDIO) },
            onConfirm = vm::confirmExpansion,
            onStop = vm::stopListening,
            onClose = vm::closeExpansion,
            picture = picture,
        )
        speechError?.let {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(Sizes.gapSmall))

        if (quick.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
                items(quick, key = { it.id }) { item -> QuickCard(item, picture(item)) { vm.tapQuick(item) } }
            }
            Spacer(Modifier.height(Sizes.gapSmall))
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
            items(tabs, key = { it.label }) { t ->
                FilterChip(selected = t == tab, onClick = { vm.selectTab(t) },
                    label = { Text(t.label, style = MaterialTheme.typography.bodyLarge) },
                    modifier = Modifier.heightIn(min = Sizes.touchMin))
            }
        }
        Spacer(Modifier.height(Sizes.gapSmall))

        if (shown.isEmpty()) {
            Text(
                if (tab == Tab.Favourites) "Ό,τι χρησιμοποιείς πιο συχνά θα εμφανίζεται εδώ." else "Τίποτα εδώ ακόμα.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(Sizes.pictureCard),
            horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
            verticalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
            modifier = Modifier.fillMaxWidth().testTag(BOARD_GRID_TAG),
        ) {
            items(shown, key = { it.id }) { item ->
                PictureCard(imageFile = picture(item), label = item.text, onClick = { vm.tap(item) })
            }
        }
    }
}

/**
 * The sentence so far, as pictures over words — the same way it was built, so a boy who reads
 * pictures can check his own sentence before he says it. The check mark stays until he changes it.
 *
 * With a judge behind it, the same area holds the whole of «Ολόκληρη»: the button while the words are
 * only words, and then the sentence they make — above the chips, which stay exactly where they were
 * so that he can see what it was built out of — with one primary and «Κλείσε» under it. Nothing else
 * on the board moves: the quick row, the tabs, the grid and the bottom three are where they were.
 */
@Composable
private fun StripRow(
    items: List<Item>,
    full: Boolean,
    spoken: Boolean,
    showsExpand: Boolean,
    expansion: Expansion?,
    listenLevel: Float,
    onExpand: () -> Unit,
    onSay: () -> Unit,
    onConfirm: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
    picture: (Item) -> File?,
) {
    // The newest word is the one being chosen, so it is the one that has to be on screen.
    val stripState = rememberLazyListState()
    LaunchedEffect(items.size) { stripState.animateScrollToItem(items.lastIndex.coerceAtLeast(0)) }
    Surface(shape = RoundedCornerShape(Sizes.corner), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            if (expansion != null) {
                // The sentence is the thing on the screen while this is open: he reads it, hears it,
                // and says it back. Never an empty line — while the judge is being asked it says so.
                Text(
                    expansion.sentence ?: MAKING_SENTENCE,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.fillMaxWidth().testTag(EXPAND_SENTENCE_TAG),
                )
                Spacer(Modifier.height(Sizes.gapSmall))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (items.isEmpty()) {
                        Text("Πάτα εικόνες για να φτιάξεις πρόταση.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        // No key: the same word may be chosen twice, and position is what identifies it here.
                        LazyRow(state = stripState, horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
                            items(items) { item ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Pictogram(picture(item), Sizes.stripPicture)
                                    Text(item.text, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center, maxLines = 1)
                                }
                            }
                        }
                        if (full) Text("Γεμάτο. Πες το ή σβήσε.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                    }
                }
                SuccessMark(visible = (expansion?.done ?: (spoken && items.isNotEmpty())), size = Sizes.stripPicture)
            }
            if (expansion != null) {
                ExpansionControls(expansion, listenLevel, onSay, onConfirm, onStop, onClose)
            } else if (showsExpand) {
                Spacer(Modifier.height(Sizes.gapSmall))
                BigButton(WHOLE, onClick = onExpand, icon = Icons.Rounded.AutoAwesome, tone = ButtonTone.Secondary,
                    modifier = Modifier.testTag(EXPAND_TAG))
            }
        }
    }
}

/**
 * What is under the sentence: one primary and «Κλείσε», and while the window is open neither of them.
 *
 * The primary is the gentle check of spec §12, exactly as the three speech modules run it: «Πες το»
 * until the phone has agreed with him or has asked him twice, and then «Το είπα!», which is his word
 * against the phone's and has never been withheld. A miss in between buys one «Δοκίμασε ξανά» with
 * the sentence untouched.
 */
@Composable
private fun ExpansionControls(
    expansion: Expansion,
    listenLevel: Float,
    onSay: () -> Unit,
    onConfirm: () -> Unit,
    onStop: () -> Unit,
    onClose: () -> Unit,
) {
    if (expansion.nudge) {
        Spacer(Modifier.height(Sizes.gapSmall))
        Text(
            GentleCheck.TRY_AGAIN, style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.secondary,
        )
    }
    if (expansion.heard != null) {
        Spacer(Modifier.height(Sizes.gapSmall))
        Text(
            // A miss is the phone's uncertainty, never a verdict on how he said it.
            if (expansion.matched) "Άκουσα «${expansion.heard}». Μπράβο!" else "Άκουσα «${expansion.heard}». Το τηλέφωνο δεν είναι σίγουρο.",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    expansion.error?.let {
        Spacer(Modifier.height(Sizes.gapSmall))
        Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
    }
    Spacer(Modifier.height(Sizes.gapSmall))
    if (expansion.listening) {
        // The window is open: one thing to do, which is to speak, and one button, which closes it
        // when he decides he is finished.
        ListeningIndicator(level = listenLevel, onStop = onStop)
        return
    }
    // Nothing to repeat yet, or it is done: «Κλείσε» alone, so there is one thing to press.
    if (!expansion.thinking && !expansion.done) {
        if (expansion.canConfirm) {
            BigButton(SAID_IT, onClick = onConfirm, tone = ButtonTone.Success, modifier = Modifier.testTag(EXPAND_SAY_TAG))
        } else {
            BigButton(SAY_IT_BACK, onClick = onSay, icon = Icons.Rounded.Mic, tone = ButtonTone.Primary,
                modifier = Modifier.testTag(EXPAND_SAY_TAG))
        }
        Spacer(Modifier.height(Sizes.gapSmall))
    }
    QuietButton(CLOSE_EXPANSION, onClick = onClose)
}

/** A quick phrase: picture first, then the word, on one 72dp tall card. */
@Composable
private fun QuickCard(item: Item, image: File?, onClick: () -> Unit) {
    val feedback = LocalFeedback.current
    Card(
        onClick = { feedback.tap(); onClick() },
        modifier = Modifier.heightIn(min = Sizes.touchMin),
        shape = RoundedCornerShape(Sizes.corner),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Pictogram(image, Sizes.quickPicture)
            Spacer(Modifier.width(Sizes.gapSmall))
            Text(item.text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** A picture that was deleted or moved falls back to the placeholder, so the word still works. */
@Composable
private fun Pictogram(file: File?, size: Dp) {
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (file != null && file.exists()) {
            AsyncImage(
                model = file,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                error = rememberVectorPainter(Icons.Rounded.Image),
                modifier = Modifier.size(size),
            )
        } else {
            Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(size),
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
