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
 */
interface Module {
    val id: ModuleId
    val titleGreek: String
    val icon: ImageVector

    /** Items this module wants in today's mixed session. Empty means "nothing today". */
    suspend fun planFor(graph: AppGraph): List<Item>

    /** Items for free practice from the Today grid. Defaults to the session plan; modules may fall back to random items. */
    suspend fun practiceFor(graph: AppGraph): List<Item> = planFor(graph)

    /** The exercise over [items] (never empty). Calls [onDone] when all are finished, [onLeave] on back. */
    @Composable
    fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit)
}
