package gr.dimitris.app.modules

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId

/**
 * One therapy module. It gets items, shows a full-screen exercise, and writes its own Attempts.
 *
 * The contract with the session runner:
 * - The items [planFor] returns may be placeholders. They only size the session — a module is free
 *   to build its own exercises from them, as long as it runs as many exercises as it was given items.
 * - A module owes the session one [gr.dimitris.app.core.data.Attempt] per completed exercise,
 *   carrying the `sessionId` it was handed. Those rows are what the session counts, so a module that
 *   writes none is a module he did nothing in.
 * - [Screen] is never called with an empty list. A module with nothing to do today is left out of
 *   the session, so no exercise screen has to render "nothing here".
 * - [Screen] ends exactly one of two ways: [onDone] when every exercise is finished, [onLeave] when
 *   the user pressed back. Finished and wanting out are different events and the session runner
 *   treats them differently.
 * - `onDone` and `onLeave` are both invoked only after the module's Attempt rows for finished
 *   exercises have landed. The session counts those rows as soon as it is told, so a module that
 *   calls back while a write is still in flight reports him as having done less than he did.
 */
interface Module {
    val id: ModuleId
    val titleGreek: String
    val icon: ImageVector

    /**
     * True when the module runs its whole list or nothing: a conversation cannot be cut in half.
     * The session budget then leaves this module's plan alone — which is what keeps
     * `plannedItemCount` honest, because a truncated list the module ignores would have the session
     * row promising fewer exercises than the module goes on to run.
     */
    val atomic: Boolean get() = false

    /**
     * How many of this module's planned items one exercise is worth, so the session's budget is shared
     * out in whole exercises.
     *
     * One for everything except «Βήματα», where a task is **two** items — he orders the steps and then
     * he tells them, and it writes a row for each ([gr.dimitris.app.modules.steps.StepsModule.ITEMS_PER_TASK]).
     * A budget of three then bought one task and two rows against a promise of three, so every mixed
     * sitting with that tile in it left a session row disagreeing with itself by one. Rounded **down**
     * to a whole exercise, and never below one of them.
     */
    val granularity: Int get() = 1

    /** Items this module wants in today's mixed session. Empty means "nothing today". */
    suspend fun planFor(graph: AppGraph): List<Item>

    /** Items for free practice from the Today grid. Defaults to the session plan; modules may fall back to random items. */
    suspend fun practiceFor(graph: AppGraph): List<Item> = planFor(graph)

    /**
     * The same plan, at the 1..5 difficulty he set on this module's first screen (spec §13). The
     * default ignores it, which is the honest answer for a module whose difficulty is not a matter of
     * *which items* it asks for: «Αριθμοί», «Προτάσεις», «Γράψε» and «Δεξί χέρι» all generate their
     * own content from a level or a target size, so the dots reach them through
     * [gr.dimitris.app.core.settings.Settings] and their plan stays a list of placeholders.
     *
     * Both callers — [gr.dimitris.app.today.SessionViewModel] and
     * [gr.dimitris.app.today.PracticeViewModel] — read [gr.dimitris.app.core.settings.Settings.difficulty]
     * and pass it here, so a module never has to read the setting itself to know what it was asked
     * for. See [gr.dimitris.app.core.difficulty.Difficulty] for what each number means per module.
     */
    suspend fun planFor(graph: AppGraph, difficulty: Int): List<Item> = planFor(graph)

    /** Free practice at [difficulty]. Defaults to the difficulty-blind [practiceFor]. */
    suspend fun practiceFor(graph: AppGraph, difficulty: Int): List<Item> = practiceFor(graph)

    /** The exercise over [items] (never empty). Calls [onDone] when all are finished, [onLeave] on back. */
    @Composable
    fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit)
}
