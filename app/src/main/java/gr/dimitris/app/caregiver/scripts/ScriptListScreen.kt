package gr.dimitris.app.caregiver.scripts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Script
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.theme.Sizes

/** The caregiver's dialogues, newest wording first tap away. [onEdit] gets null for a new one. */
@Composable
fun ScriptListScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val graph = LocalAppGraph.current
    // Re-subscribe after a backup import: the old database's flow never emits again.
    val generation by graph.dbGeneration.collectAsStateWithLifecycle()
    val flow = remember(graph, generation) { graph.scripts.observeAll() }
    val scripts by flow.collectAsStateWithLifecycle(initialValue = emptyList())

    DimitrisScreen(
        title = "Διάλογοι",
        onBack = onBack,
        bottom = { BigButton("Νέος διάλογος", onClick = { onEdit(null) }, icon = Icons.Rounded.Add) },
    ) {
        Text(
            "Οι διάλογοι που εξασκεί ο Δημήτρης. Ηχογράφησε τη φωνή σου σε κάθε γραμμή για να την ακούει.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.padding(Sizes.gapSmall))
        if (scripts.isEmpty()) {
            Text("Δεν υπάρχουν διάλογοι ακόμα. Πάτα «Νέος διάλογος».", style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn {
            items(scripts, key = { it.id }) { script -> ScriptRow(script, onClick = { onEdit(script.id) }) }
        }
    }
}

@Composable
private fun ScriptRow(script: Script, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin).clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Icon(Icons.Rounded.Chat, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(Sizes.icon))
        Spacer(Modifier.padding(horizontal = Sizes.gapSmall))
        Column(Modifier.weight(1f)) {
            Text(script.title, style = MaterialTheme.typography.titleLarge)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
