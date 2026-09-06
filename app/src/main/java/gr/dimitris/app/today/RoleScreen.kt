package gr.dimitris.app.today

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Face
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.settings.DeviceRole
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.theme.Sizes
import kotlinx.coroutines.launch

/**
 * The first thing the app ever asks, once: whose phone is this?
 *
 * The answer decides where the app opens — his own screen, or the caregiver's — and nothing else.
 * Both phones sync, both phones can practise; a caregiver simply does not want to land on «Ξεκίνα»
 * every morning, and Dimitris must never land on a settings list.
 *
 * Two choices, no back arrow and no third option. It can be changed later in the caregiver
 * settings, which is where somebody who tapped the wrong one will be taken to fix it.
 */
@Composable
fun RoleScreen(onChosen: (DeviceRole) -> Unit) {
    val graph = LocalAppGraph.current
    val scope = rememberCoroutineScope()

    fun choose(role: DeviceRole) {
        scope.launch {
            graph.settings.setDeviceRole(role)
            onChosen(role)
        }
    }

    DimitrisScreen(talkButton = false) {
        Text(
            "Τίνος είναι αυτό το τηλέφωνο;",
            style = MaterialTheme.typography.displaySmall,
            modifier = Modifier.semantics { testTag = "roleTitle" },
        )
        Spacer(Modifier.height(Sizes.gap))
        Text(
            "Ρώτησέ το μία φορά. Μπορεί να αλλάξει αργότερα στις ρυθμίσεις.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(Sizes.gap))
        BigButton("Του Δημήτρη", onClick = { choose(DeviceRole.DIMITRIS) }, icon = Icons.Rounded.Favorite)
        Spacer(Modifier.height(Sizes.gapSmall))
        BigButton("Φροντιστή", onClick = { choose(DeviceRole.CAREGIVER) }, icon = Icons.Rounded.Face, tone = ButtonTone.Secondary)
    }
}
