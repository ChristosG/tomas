package gr.dimitris.app.modules

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId

/** One therapy module. It gets items, shows a full-screen exercise, and writes its own Attempts. */
interface Module {
    val id: ModuleId
    val titleGreek: String
    val icon: ImageVector

    /** Items this module wants in today's mixed session. Empty means "nothing today". */
    suspend fun planFor(graph: AppGraph): List<Item>

    /** Items for free practice from the Today grid. Defaults to the session plan; modules may fall back to random items. */
    suspend fun practiceFor(graph: AppGraph): List<Item> = planFor(graph)

    /** The exercise over [items]. Must call [onDone] when finished. */
    @Composable
    fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit)
}
