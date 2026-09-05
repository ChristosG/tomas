package gr.dimitris.app.modules.talkboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import gr.dimitris.app.ui.components.PictureCard
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun TalkBoardScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: TalkBoardViewModel = viewModel { TalkBoardViewModel(graph) }
    val strip by vm.strip.items.collectAsStateWithLifecycle()
    val stripFull by vm.stripFull.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val quick by vm.quick.collectAsStateWithLifecycle()
    val shown by vm.shown.collectAsStateWithLifecycle()
    val speechError by vm.speechError.collectAsStateWithLifecycle()

    DimitrisScreen(
        title = "Μίλα",
        onBack = onBack,
        talkButton = false,
        bottom = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BigButton("Πες το", onClick = vm::speakStrip, icon = Icons.Rounded.VolumeUp, tone = ButtonTone.Secondary,
                    enabled = strip.isNotEmpty(), modifier = Modifier.weight(2f))
                Spacer(Modifier.width(Sizes.gapSmall))
                QuietButton("", onClick = vm::undo, icon = Icons.AutoMirrored.Rounded.Backspace, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Sizes.gapSmall))
                QuietButton("", onClick = vm::clear, icon = Icons.Rounded.Clear, modifier = Modifier.weight(1f))
            }
        },
    ) {
        StripRow(strip, stripFull)
        speechError?.let {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(Sizes.gapSmall))

        if (quick.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
                items(quick, key = { it.id }) { item -> QuickChip(item.text) { vm.tapQuick(item) } }
            }
            Spacer(Modifier.height(Sizes.gapSmall))
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
            items(tabs, key = { it.label }) { t ->
                FilterChip(selected = t == tab, onClick = { vm.selectTab(t) },
                    label = { Text(t.label, style = MaterialTheme.typography.bodyLarge) }, modifier = Modifier.heightIn(min = 56.dp))
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
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(shown, key = { it.id }) { item ->
                PictureCard(imageFile = item.imagePath?.let { graph.files.resolve(it) }, label = item.text, onClick = { vm.tap(item) })
            }
        }
    }
}

@Composable
private fun StripRow(items: List<Item>, full: Boolean) {
    Surface(shape = RoundedCornerShape(Sizes.corner), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (items.isEmpty()) {
                Text("Πάτα εικόνες για να φτιάξεις πρόταση.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column {
                    Text(items.joinToString(" ") { it.text }, style = MaterialTheme.typography.headlineMedium)
                    if (full) Text("Γεμάτο. Πες το ή σβήσε.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun QuickChip(text: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(text, style = MaterialTheme.typography.labelLarge) },
        modifier = Modifier.heightIn(min = Sizes.touchMin))
}
