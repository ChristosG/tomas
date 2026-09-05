package gr.dimitris.app.caregiver.content

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.theme.Sizes
import java.io.File

@Composable
fun ItemListScreen(onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val graph = LocalAppGraph.current
    val all by graph.items.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
    var query by remember { mutableStateOf("") }
    val shown = ItemSearch.filter(all, query)
    val grouped = shown.groupBy { it.category }.toSortedMap(compareBy { it.ordinal })   // declaration order: Γρήγορα, Φαγητό, ...

    DimitrisScreen(
        title = "Λέξεις και εικόνες",
        onBack = onBack,
        bottom = { BigButton("Νέα λέξη", onClick = { onEdit(null) }, icon = Icons.Rounded.Add) },
    ) {
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            placeholder = { Text("Αναζήτηση") }, textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.padding(Sizes.gapSmall))
        if (all.isEmpty()) {
            Text("Δεν υπάρχουν λέξεις ακόμα. Πάτα «Νέα λέξη».", style = MaterialTheme.typography.bodyLarge)
        }
        LazyColumn {
            grouped.forEach { (category, items) ->
                item(key = "h-${category.name}") {
                    Text(category.greek, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
                }
                items(items, key = { it.id }) { item -> ItemRow(item, onClick = { onEdit(item.id) }) }
            }
        }
    }
}

@Composable
private fun ItemRow(item: Item, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin).clickable(onClick = onClick).padding(vertical = 4.dp),
    ) {
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            if (item.imagePath != null) AsyncImage(model = File(item.imagePath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.size(56.dp))
            else Icon(Icons.Rounded.Image, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(Sizes.gap))
        Column(Modifier.weight(1f)) {
            Text(item.text, style = MaterialTheme.typography.bodyLarge)
            Text(if (item.kind == ItemKind.PHRASE) "Φράση" else "Λέξη",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (item.modelRecordingId != null) Icon(Icons.Rounded.Mic, contentDescription = "Έχει φωνή", tint = MaterialTheme.colorScheme.tertiary)
    }
}
