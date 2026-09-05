package gr.dimitris.app.caregiver

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FileDownload
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.caregiver.content.FILE_AUTHORITY
import gr.dimitris.app.core.backup.Backup
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(onBack: () -> Unit, onImported: () -> Unit) {
    val graph = LocalAppGraph.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val backup = remember { Backup(graph) }
    var pending by remember { mutableStateOf<Uri?>(null) }
    var status by remember { mutableStateOf<String?>(null) }

    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> pending = uri }

    DimitrisScreen(title = "Αντίγραφο ασφαλείας", onBack = onBack) {
        Text("Το αντίγραφο έχει τις λέξεις, τις φωτογραφίες, τις φωνές και όλο το ιστορικό.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Sizes.gap))
        BigButton("Εξαγωγή και αποστολή", icon = Icons.Rounded.Share, onClick = {
            scope.launch {
                runCatching { backup.export() }
                    .onSuccess { file ->
                        val uri = FileProvider.getUriForFile(context, FILE_AUTHORITY, file)
                        val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        context.startActivity(Intent.createChooser(send, "Αποστολή αντιγράφου"))
                    }
                    .onFailure { graph.errors.record("backup export", it); status = "Η εξαγωγή απέτυχε" }
            }
        })
        Spacer(Modifier.height(Sizes.gapSmall))
        QuietButton("Εισαγωγή από αρχείο", icon = Icons.Rounded.FileDownload, onClick = { pickZip.launch(arrayOf("application/zip", "application/octet-stream")) })
        if (status != null) {
            Spacer(Modifier.height(Sizes.gap))
            Text(status!!, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
        }
    }

    pending?.let { uri ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = { Text("Αντικατάσταση όλων;") },
            text = { Text("Ό,τι υπάρχει τώρα στο τηλέφωνο θα αντικατασταθεί από το αρχείο.") },
            confirmButton = {
                TextButton(onClick = {
                    pending = null
                    scope.launch {
                        runCatching { backup.import(uri) }
                            .onSuccess { onImported() }
                            .onFailure { graph.errors.record("backup import", it); status = it.message ?: "Η εισαγωγή απέτυχε" }
                    }
                }) { Text("Ναι, αντικατάσταση", style = MaterialTheme.typography.labelLarge) }
            },
            dismissButton = { TextButton(onClick = { pending = null }) { Text("Άκυρο", style = MaterialTheme.typography.labelLarge) } },
        )
    }
}
