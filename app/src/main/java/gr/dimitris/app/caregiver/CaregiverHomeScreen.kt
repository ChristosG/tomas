package gr.dimitris.app.caregiver

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backup
import androidx.compose.material.icons.rounded.BugReport
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.Routes
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes

data class CaregiverEntry(val title: String, val icon: ImageVector, val route: String)

val caregiverEntries: List<CaregiverEntry> = listOf(
    CaregiverEntry("Λέξεις και εικόνες", Icons.Rounded.Image, Routes.ITEMS),
    CaregiverEntry("Διάλογοι", Icons.Rounded.Chat, Routes.SCRIPTS),
    CaregiverEntry("Ρυθμίσεις", Icons.Rounded.Settings, Routes.SETTINGS),
    CaregiverEntry("Σφάλματα", Icons.Rounded.BugReport, Routes.ERRORS),
    CaregiverEntry("Αντίγραφο ασφαλείας", Icons.Rounded.Backup, Routes.BACKUP),
)

@Composable
fun CaregiverHomeScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    DimitrisScreen(
        title = "Φροντιστής",
        bottom = { QuietButton("Πίσω στον Δημήτρη", onClick = onBack, icon = Icons.Rounded.Person) },
    ) {
        // Scrolls, so later phases can keep adding entries without pushing any off a small screen.
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("Εδώ προσθέτεις λέξεις, φωτογραφίες και φωνές, και βλέπεις πώς πάει.", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(Sizes.gap))
            caregiverEntries.forEach { entry ->
                BigButton(entry.title, onClick = { onOpen(entry.route) }, icon = entry.icon)
                Spacer(Modifier.height(Sizes.gapSmall))
            }
        }
    }
}
