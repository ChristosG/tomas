package gr.dimitris.app.modules.singsay

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemDao
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.ScheduleDao
import gr.dimitris.app.caregiver.insights.Focus
import gr.dimitris.app.core.scheduler.SessionBuilder
import gr.dimitris.app.modules.Module

/**
 * Melodic intonation therapy: he sings the phrase he cannot say, then says it. Phrases only — a
 * single word has no melody worth the five stages.
 *
 * Fewer and slower than the word coach: five stages on one phrase is two to four minutes of work,
 * so the shared session budget — which counts exercises, not effort, and would hand this module five
 * of them — is capped here at [MAX_PER_SESSION]. Free practice is the module on its own and nothing
 * else is waiting behind it, so it may run to [MAX_PER_PRACTICE].
 */
object SingSayModule : Module {
    override val id = ModuleId.SINGSAY
    override val titleGreek = "Τραγούδα και πες το"
    override val icon: ImageVector = Icons.Rounded.MusicNote
    internal val kinds = listOf(ItemKind.PHRASE)

    /** Three five-stage phrases is already ten minutes; the rest were never marked done and stay due. */
    const val MAX_PER_SESSION = 3

    /** Free practice has the sitting to itself. */
    const val MAX_PER_PRACTICE = 5

    /** New phrases a day: a phrase he has never heard costs the full five stages. */
    const val NEW_PER_DAY = 3

    override suspend fun planFor(graph: AppGraph): List<Item> =
        plan(graph.db.items(), graph.db.schedules(), MAX_PER_SESSION, graph.activeFocus())

    override suspend fun practiceFor(graph: AppGraph): List<Item> =
        plan(graph.db.items(), graph.db.schedules(), MAX_PER_PRACTICE, graph.activeFocus())
            .ifEmpty { graph.db.items().activeOfKinds(kinds).shuffled().take(MAX_PER_PRACTICE) }

    /** The DAOs rather than the graph, so a test can watch the cap hold over fakes. */
    internal suspend fun plan(
        items: ItemDao,
        schedules: ScheduleDao,
        maxItems: Int,
        focus: Focus? = null,
    ): List<Item> =
        SessionBuilder(items, schedules, newPerDay = NEW_PER_DAY, maxItems = maxItems, focus = focus).plan(id, kinds)

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        SingSayScreen(items, sessionId, onDone, onLeave)
}
