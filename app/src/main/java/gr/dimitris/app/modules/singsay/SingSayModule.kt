package gr.dimitris.app.modules.singsay

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.scheduler.SessionBuilder
import gr.dimitris.app.modules.Module

/**
 * Melodic intonation therapy: he sings the phrase he cannot say, then says it. Phrases only — a
 * single word has no melody worth the five stages.
 *
 * Fewer and slower than the word coach: five stages on one phrase is several minutes of work, so
 * three new phrases a day and five in a sitting is already a long session.
 */
object SingSayModule : Module {
    override val id = ModuleId.SINGSAY
    override val titleGreek = "Τραγούδα και πες το"
    override val icon: ImageVector = Icons.Rounded.MusicNote
    private val kinds = listOf(ItemKind.PHRASE)

    override suspend fun planFor(graph: AppGraph): List<Item> =
        SessionBuilder(graph.db.items(), graph.db.schedules(), newPerDay = 3, maxItems = 5).plan(id, kinds)

    override suspend fun practiceFor(graph: AppGraph): List<Item> =
        planFor(graph).ifEmpty { graph.db.items().activeOfKinds(kinds).shuffled().take(4) }

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        SingSayScreen(items, sessionId, onDone, onLeave)
}
