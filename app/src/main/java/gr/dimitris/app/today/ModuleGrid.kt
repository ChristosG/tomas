package gr.dimitris.app.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.dimitris.app.modules.Module
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun ModuleGrid(modules: List<Module>, onOpen: (Module) -> Unit, modifier: Modifier = Modifier) {
    val feedback = LocalFeedback.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
        verticalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(modules, key = { it.id.name }) { m ->
            Card(
                onClick = { feedback.tap(); onOpen(m) },
                shape = RoundedCornerShape(Sizes.corner),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.heightIn(min = 120.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Icon(m.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Text(m.titleGreek, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
