package gr.dimitris.app.modules.singsay

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.ListenButton
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SingSayScreen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: SingSayViewModel = viewModel(
        key = "singsay-${sessionId ?: "practice"}-${items.size}-${items.firstOrNull()?.id}",
    ) { SingSayViewModel(graph, items, sessionId) }
    val s by vm.state.collectAsStateWithLifecycle()
    // Navigating away — «Μίλα», or the session moving on — is not a back press: the ViewModel is
    // still alive on the back stack, so it is told to drop its take and stop its sound itself.
    DisposableEffect(vm) { onDispose { vm.screenGone() } }
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) vm.toggleRecording() else vm.micDenied()
    }

    /**
     * A mixed session ends with its own summary, so a second "well done" screen in the middle of it
     * is one tap of noise: the module hands straight back. Free practice keeps the end screen — it
     * is the only ending there is.
     */
    val endScreen = sessionId == null
    LaunchedEffect(s.done) { if (s.done && !endScreen) onDone() }

    if (s.done && endScreen) {
        DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            SuccessMark(visible = true)
            Spacer(Modifier.height(Sizes.gap))
            Text("Τέλος με το τραγούδι!", style = MaterialTheme.typography.headlineMedium)
        }
        return
    }

    DimitrisScreen(
        title = "Τραγούδα ${s.index + 1}/${s.total}",
        // Back is "I want out", not "I finished": the module drops what it was doing and says so.
        onBack = { vm.leave(onLeave) },
        bottom = {
            // The tap pad: the biggest thing on the screen, at the bottom where his left thumb lives.
            BigButton(
                if (s.stage == SingStage.SPEAK) "Το είπα!" else "Χτύπα",
                onClick = { if (s.stage == SingStage.SPEAK) vm.didIt() else vm.tap() },
                tone = if (s.stage == SingStage.SPEAK) ButtonTone.Success else ButtonTone.Secondary,
                modifier = Modifier.height(110.dp), enabled = !s.playing && !s.isRecording,
            )
            Spacer(Modifier.height(Sizes.gapSmall))
            // «Άκου» is here rather than up with the syllables: it is the one control that must not
            // be hunted for, and it belongs where his thumb already is. It shares the row with «Το
            // έκανα» so the pad above keeps its full height — nothing shrinks to make room for it.
            val canListen = !s.playing && !s.isRecording
            if (s.stage != SingStage.SPEAK) {
                Row {
                    ListenButton(onClick = vm::listenModel, enabled = canListen, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Sizes.gapSmall))
                    // Not while the microphone is open: a phrase finished mid-take ends with a
                    // recording that spans stages, and the «Στοπ» that would have closed it is a
                    // screen away.
                    BigButton("Το έκανα", onClick = vm::didIt, tone = ButtonTone.Success, enabled = canListen, modifier = Modifier.weight(1f))
                }
            } else {
                // The last stage says it with nothing left under it, and «Άκου» is still there:
                // that is the whole of spec §12 in one button.
                ListenButton(onClick = vm::listenModel, enabled = canListen)
            }
            Spacer(Modifier.height(Sizes.gapSmall))
            // Not while the phrase is still loading: a skip landing then would finish a phrase whose
            // own sung model has not even been looked up yet.
            QuietButton("Παράλειψη", onClick = vm::skip, enabled = !s.loading)
        },
    ) {
        // Five stages, a row of syllables and up to three buttons do not always fit a small screen
        // at his text size: scrolling is better than a button he cannot reach.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(
                SingStage.label(s.stage) + if (s.stage == SingStage.FADING) " (${s.repetition + 1}/${SingStage.FADING_REPS})" else "",
                style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Βήμα ${s.stage} από ${SingStage.SPEAK}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The icon half of "icon + sound + haptic": every finished step leaves a tick behind,
                // so a success he did not hear is still a success he can see.
                repeat(s.stage - SingStage.LISTEN) {
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.CheckCircle, contentDescription = if (it == 0) "Ολοκληρωμένα βήματα" else null,
                        tint = MaterialTheme.colorScheme.tertiary, modifier = Modifier.size(28.dp),
                    )
                }
            }
            Spacer(Modifier.height(Sizes.gap))
            // High syllables sit higher than low ones, so the melody is visible as well as audible.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                s.notes.forEachIndexed { i, n ->
                    val lit = i == s.lit
                    Box(
                        Modifier.background(if (lit) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                            .padding(horizontal = 14.dp, vertical = if (n.pitch == Pitch.HIGH) 4.dp else 18.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            n.syllable, style = MaterialTheme.typography.headlineMedium,
                            color = if (lit) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Spacer(Modifier.height(Sizes.gap))
            // Listening moved to the bottom row, where his thumb is and where it is on every screen
            // of every module: there is exactly one «Άκου» here now.
            QuietButton(
                if (s.isRecording) "Στοπ" else "Ηχογράφηση",
                onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                icon = if (s.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                // Refused while the model is playing, rather than silently killing it. A running
                // take leaves `playing` false, so «Στοπ» is always reachable.
                enabled = !s.playing,
            )
            if (s.selfRecordingPath != null && !s.isRecording) {
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton("Σύγκριση", onClick = vm::playComparison, icon = Icons.Rounded.Compare)
            }
            // Only once the lookup has landed: `hasSungModel` starts false, so without the guard the
            // line saying there is no sung voice flashes on every phrase, the ones that have one too.
            if (!s.loading && !s.hasSungModel) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(
                    "Δεν υπάρχει τραγουδισμένη φωνή για αυτή τη φράση, ακούς τη μελωδία.",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (s.error != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(Modifier.height(Sizes.gap))
        }
    }
}
