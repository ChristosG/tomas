package gr.dimitris.app.caregiver

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.now
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ErrorListScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()
    val logs by graph.db.errorLogs().observeRecent(200).collectAsStateWithLifecycle(initialValue = emptyList())
    var expanded by remember { mutableStateOf<String?>(null) }
    val format = remember { DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale("el")) }

    DimitrisScreen(
        title = "Σφάλματα",
        onBack = onBack,
        bottom = {
            QuietButton("Καθαρισμός", onClick = { scope.launch { graph.db.errorLogs().clearAll(now()) } }, icon = Icons.Rounded.DeleteSweep)
        },
    ) {
        if (logs.isEmpty()) Text("Κανένα σφάλμα. Ωραία.", style = MaterialTheme.typography.bodyLarge)
        LazyColumn {
            items(logs, key = { it.id }) { log ->
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { expanded = if (expanded == log.id) null else log.id }
                        .padding(vertical = 8.dp)
                ) {
                    Text("${format.format(Date(log.at))} · ${log.where_}", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(log.message, style = MaterialTheme.typography.bodyLarge)
                    if (expanded == log.id) {
                        Text(log.stack, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}
