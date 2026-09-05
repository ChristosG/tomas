package gr.dimitris.app.caregiver

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Person
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

/** One entry per caregiver screen. Tasks 12–14 add theirs here as the screens appear. */
val caregiverEntries: List<CaregiverEntry> = listOf(
    CaregiverEntry("Λέξεις και εικόνες", Icons.Rounded.Image, Routes.ITEMS),
)

@Composable
fun CaregiverHomeScreen(onBack: () -> Unit, onOpen: (String) -> Unit) {
    DimitrisScreen(
        title = "Φροντιστής",
        bottom = { QuietButton("Πίσω στον Δημήτρη", onClick = onBack, icon = Icons.Rounded.Person) },
    ) {
        Text("Εδώ προσθέτεις λέξεις, φωτογραφίες και φωνές, και βλέπεις πώς πάει.", style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(Sizes.gap))
        caregiverEntries.forEach { entry ->
            BigButton(entry.title, onClick = { onOpen(entry.route) }, icon = entry.icon)
            Spacer(Modifier.height(Sizes.gapSmall))
        }
    }
}
