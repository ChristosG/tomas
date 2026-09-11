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
     * How many of the session's items one task is worth: **two**, because a task is two exercises and
     * leaves two attempt rows — `steps:order:<task>` and `steps:tell:<task>`.
     *
     * The first cut planned one item per task, and then the session promised four exercises and
     * counted eight: `completedItemCount` came out at twice `plannedItemCount` on every sitting with
     * «Βήματα» in it. Nothing he saw was wrong — the end screen counts rows, and he really had done
     * eight things — but a session row that disagrees with itself is a row nobody can read a year
     * from now.
     */
    const val ITEMS_PER_TASK = 2

    /**
     * And the session's budget is shared out in whole tasks because of it: a share of three would buy
     * one task and write two rows, which is a session row that disagrees with itself by one on every
     * mixed sitting this tile is in. See [gr.dimitris.app.today.SessionBudget.share].
     */
    override val granularity = ITEMS_PER_TASK

    /**
     * The tasks are content, not items ([StepTasks]), so the returned list only *sizes* the session:
     * two transient placeholders per task, and the screen runs one task per pair it is handed.
     *
     * It asks the database nothing. Which words happen to be on the phone has nothing to do with how
     * many tasks he can sequence in one sitting, and the seed is read when the screen opens.
     */
    override suspend fun planFor(graph: AppGraph): List<Item> =
        List(TASKS_PER_SESSION * ITEMS_PER_TASK) { Item(text = titleGreek, kind = ItemKind.WORD, category = Category.CUSTOM) }

    /**
     * How many tasks a budget of [items] buys: two items each, and never fewer than one task — a
     * module the session opens at all owes it an exercise. Since the phase-13 fix wave the session
     * runner hands this module a multiple of [ITEMS_PER_TASK], so what it plans and what he completes
     * agree; the floor of one task is for any caller that does not.
     */
    fun tasksFor(items: Int): Int = (items / ITEMS_PER_TASK).coerceAtLeast(1)

    /**
     * [items] are placeholders, but their number is the session's budget for this module: it runs one
     * task per **two** of them, which is the contract every module owes the session runner.
     */
    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        StepsScreen(tasksFor(items.size), sessionId, onDone, onLeave)
}
