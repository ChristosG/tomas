package gr.dimitris.app.modules.sql

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.TableChart
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.Module

/**
 * «SQL» — the tile Chris asked for in phase 13.
 *
 * Dimitris was a programmer, his father runs a software company, and he still does very basic SQL
 * exercises. Everything else in this app is speech therapy that a programmer happens to be doing;
 * this is the one tile that is *his own work*, in the one language his aphasia does not touch. The
 * Greek is the instruction and the tables about his own life; the SQL is SQL.
 *
 * On for everyone by default: it is not in [gr.dimitris.app.core.settings.Settings.DEFAULT_OFF], so
 * it is on the grid the day this build lands, on a phone that has been in use for months as much as
 * on a new one. He asked for it.
 */
object SqlModule : Module {
    override val id = ModuleId.SQL
    override val titleGreek = "SQL"
    override val icon: ImageVector = Icons.Rounded.TableChart

    /**
     * Six, where «Αριθμοί» has ten and «Προτάσεις» eight. A query is a slower thing to answer than a
     * sum: at level 4 he is typing SQL on a phone keyboard with one hand, and a sitting long enough
     * to be a chore is a sitting he stops finishing.
     */
    const val PUZZLES_PER_SESSION = 6

    /**
     * The puzzles are generated, not item-based: the returned list only sizes the session, so it is
     * always the full six transient placeholders. It deliberately asks the database nothing here —
     * the tables the puzzles are about are built when the screen opens ([SqlTables.load]), and how
     * many words happen to be on the phone has nothing to do with how many queries he can write in
     * one sitting. [gr.dimitris.app.today.SessionBudget] truncates this list; the screen runs what
     * is left.
     */
    override suspend fun planFor(graph: AppGraph): List<Item> =
        List(PUZZLES_PER_SESSION) { Item(text = titleGreek, kind = ItemKind.WORD, category = Category.CUSTOM) }

    /**
     * [items] are placeholders, but their number is the session's budget for this module: it runs one
     * puzzle per item it was given, which is the contract every module owes the session runner.
     */
    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        SqlScreen(items.size, sessionId, onDone, onLeave)
}
