package gr.dimitris.app.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import kotlinx.coroutines.flow.first

@Composable
fun SessionScreen(onDone: () -> Unit) {
    val graph = LocalAppGraph.current
    val message = if (graph.modules.isEmpty()) "Δεν υπάρχει άσκηση ακόμα. Έρχεται σύντομα!" else "Ξεκινάμε."

    LaunchedEffect(message) { graph.voice.speak(message, graph.settings.speechRate.first()) }
    // Leaving the session stops whatever it was saying or playing.
    DisposableEffect(Unit) { onDispose { graph.voice.quiet() } }

    DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
        Text(message, style = MaterialTheme.typography.headlineMedium)
    }
}
