package gr.dimitris.app.modules.wordcoach

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.scheduler.SessionBuilder
import gr.dimitris.app.modules.Module

object WordCoachModule : Module {
    override val id = ModuleId.WORDCOACH
    override val titleGreek = "Λέξεις"
    override val icon: ImageVector = Icons.Rounded.RecordVoiceOver
    private val kinds = listOf(ItemKind.WORD, ItemKind.PHRASE)

    override suspend fun planFor(graph: AppGraph): List<Item> =
        SessionBuilder(graph.db.items(), graph.db.schedules()).plan(id, kinds)

    override suspend fun practiceFor(graph: AppGraph): List<Item> =
        planFor(graph).ifEmpty { graph.db.items().activeOfKinds(kinds).shuffled().take(8) }

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        WordCoachScreen(items, sessionId, onDone, onLeave)
}
