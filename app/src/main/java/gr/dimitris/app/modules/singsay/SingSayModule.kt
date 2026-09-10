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
import gr.dimitris.app.core.difficulty.Difficulty
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

    override suspend fun planFor(graph: AppGraph): List<Item> = planFor(graph, Difficulty.DEFAULT)

    override suspend fun practiceFor(graph: AppGraph): List<Item> = practiceFor(graph, Difficulty.DEFAULT)

    override suspend fun planFor(graph: AppGraph, difficulty: Int): List<Item> =
        plan(graph.db.items(), graph.db.schedules(), MAX_PER_SESSION, graph.activeFocus(), difficulty)

    override suspend fun practiceFor(graph: AppGraph, difficulty: Int): List<Item> =
        plan(graph.db.items(), graph.db.schedules(), MAX_PER_PRACTICE, graph.activeFocus(), difficulty)
            .ifEmpty { graph.db.items().activeOfKinds(kinds).shuffled().take(MAX_PER_PRACTICE) }

    /**
     * The DAOs rather than the graph, so a test can watch the cap hold over fakes.
     *
     * [difficulty] is how long a phrase he asked for, in syllables — a **ceiling**
     * ([Difficulty.syllableCeiling]), so everything shorter stays in the pool. A syllable is one
     * tapped beat of the melody, so two more of them is two more beats to hold; a dot says how much
     * he is willing to be asked for, not which half of his vocabulary he is allowed to see.
     *
     * The first cut of this was a window, and it was a quiet disaster: at the default dot «Ναι»,
     * «Όχι» and «Ξανά» stopped being offered, their Leitner rows went overdue and stayed overdue for
     * ever, and no dot brought them back. Short phrases are also what the sandwich in
     * [SessionBuilder] opens and closes a sitting with.
     *
     * A ceiling no phrase is under still falls back to the unfiltered plan rather than to an empty
     * module: a device whose whole vocabulary is long phrases must not answer the easiest dot with
     * «Δεν υπάρχει υλικό ακόμα».
     */
    internal suspend fun plan(
        items: ItemDao,
        schedules: ScheduleDao,
        maxItems: Int,
        focus: Focus? = null,
        difficulty: Int = Difficulty.DEFAULT,
    ): List<Item> {
        val ceiling = Difficulty.syllableCeiling(difficulty)
        val wanted = build(items, schedules, maxItems, focus) { Difficulty.syllablesOf(it.text) <= ceiling }
        return wanted.ifEmpty { build(items, schedules, maxItems, focus) { true } }
    }

    private suspend fun build(
        items: ItemDao,
        schedules: ScheduleDao,
        maxItems: Int,
        focus: Focus?,
        filter: (Item) -> Boolean,
    ): List<Item> =
        SessionBuilder(items, schedules, newPerDay = NEW_PER_DAY, maxItems = maxItems, focus = focus, filter = filter)
            .plan(id, kinds)

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        SingSayScreen(items, sessionId, onDone, onLeave)
}
