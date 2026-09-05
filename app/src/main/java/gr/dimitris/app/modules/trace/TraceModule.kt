package gr.dimitris.app.modules.trace

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Draw
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.Module

object TraceModule : Module {
    override val id = ModuleId.TRACE
    override val titleGreek = "Γράψε"
    override val icon: ImageVector = Icons.Rounded.Draw

    /** One sitting of writing: six letters or words, which is about as long as one hand lasts. */
    const val TARGETS_PER_SESSION = 6

    /**
     * What he writes is chosen by level, not by what is due, so the returned list only sizes the
     * session: always six transient placeholders. It deliberately asks the database nothing — the
     * words behind levels 4 and 5 are ordinary talk-board cards a caregiver may delete, and counting
     * them would silently shorten a session of capitals that needs no vocabulary at all.
     * [gr.dimitris.app.today.SessionBudget] truncates this list; the screen runs what is left.
     */
    override suspend fun planFor(graph: AppGraph): List<Item> =
        List(TARGETS_PER_SESSION) { Item(text = titleGreek, kind = ItemKind.WORD, category = Category.CUSTOM) }

    /**
     * [items] are placeholders, but their number is the session's budget for this module: it runs one
     * letter per item it was given, which is the contract every module owes the session runner.
     */
    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        TraceScreen(items.size, sessionId, onDone, onLeave)
}
