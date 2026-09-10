package gr.dimitris.app.modules.steps

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.Module

/**
 * «Βήματα» — ordering and telling the steps of an everyday task (spec §13).
 *
 * This is the tile for the one thing Dimitris says about himself in so many words: a task that needs
 * steps is where he is «καμένος». Not a word he cannot retrieve and not a sound he cannot make —
 * *the order*. So the exercise is the order, twice over: first he puts the steps of «Φτιάχνω καφέ»
 * into the strip, and then he tells them, which is where «πρώτα… μετά… τέλος» come from. Those three
 * connectors are the other half of why this exists: a man who has the nouns and the verbs but not
 * the connectors cannot tell anybody how anything is done.
 *
 * On for everyone by default: it is not in [gr.dimitris.app.core.settings.Settings.DEFAULT_OFF], so
 * it is on the grid the day this build lands, on a phone that has been in use for months as much as
 * on a new one.
 */
object StepsModule : Module {
    override val id = ModuleId.STEPS
    override val titleGreek = "Βήματα"
    override val icon: ImageVector = Icons.Rounded.FormatListNumbered

    /**
     * Four tasks, where «Αριθμοί» has ten and «SQL» six — because a task here is **two** exercises.
     * He orders the steps and then he tells them, and the telling is the slowest thing in the app:
     * a six-step task spoken whole is a sentence of thirty words for a man whose articulation costs
     * him. Four of those is already a long sitting.
     */
    const val TASKS_PER_SESSION = 4

    /**
     * The tasks are content, not items ([StepTasks]), so the returned list only *sizes* the session:
     * four transient placeholders, and the screen runs one task per item it is handed.
     *
     * It asks the database nothing. Which words happen to be on the phone has nothing to do with how
     * many tasks he can sequence in one sitting, and the seed is read when the screen opens.
     *
     * One thing a reader of `sessions` should know: a task writes **two** attempt rows — the ordering
     * and the telling — so a sitting of four tasks leaves eight rows where `plannedItemCount` says
     * four. The planned count is tasks, the rows are exercises, and the summary he is shown counts
     * the rows, which is the honest number: he really did do eight things.
     */
    override suspend fun planFor(graph: AppGraph): List<Item> =
        List(TASKS_PER_SESSION) { Item(text = titleGreek, kind = ItemKind.WORD, category = Category.CUSTOM) }

    /**
     * [items] are placeholders, but their number is the session's budget for this module: it runs one
     * task per item it was given, which is the contract every module owes the session runner.
     */
    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        StepsScreen(items.size, sessionId, onDone, onLeave)
}
