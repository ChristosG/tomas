package gr.dimitris.app.modules.wordcoach

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.scheduler.SessionBuilder
import gr.dimitris.app.modules.Module

object WordCoachModule : Module {
    override val id = ModuleId.WORDCOACH
    override val titleGreek = "Λέξεις"
    override val icon: ImageVector = Icons.Rounded.RecordVoiceOver

    override suspend fun planFor(graph: AppGraph): List<Item> = planFor(graph, Difficulty.DEFAULT)

    override suspend fun practiceFor(graph: AppGraph): List<Item> = practiceFor(graph, Difficulty.DEFAULT)

    /**
     * The dots pick which tier of the vocabulary he is asked to retrieve: dot n takes every word of
     * tier n and below ([Difficulty.wordCoachTier]), single words at 1 and phrases from 2 up
     * ([Difficulty.wordCoachKinds]).
     *
     * Two cuts and not one, because they are two different things. The *kind* is how much there is
     * to say — a phrase is more to hold and more to articulate than a word — and it is a column
     * SQLite can filter on. The *tier* is how hard the word itself is, and since phase 13 the seed
     * grades it: «νερό» is tier 1 and «ελευθερία» is tier 5, and before that grading existed dots 3,
     * 4 and 5 all handed him the same two hundred everyday words. That is the half of "the app is
     * too easy" this module was guilty of.
     *
     * Cumulative, so nothing ever leaves the pool: a word the caregiver typed is tier 1 and is in
     * reach from every dot, and the easy words are what the sandwich starts and ends a sitting with.
     *
     * Everything else about the plan is untouched: the Leitner boxes still decide what is due, the
     * focus still keeps its places, the sandwich still orders the sitting. The dots only narrow what
     * the boxes may choose from.
     */
    override suspend fun planFor(graph: AppGraph, difficulty: Int): List<Item> =
        SessionBuilder(
            graph.db.items(), graph.db.schedules(), focus = graph.activeFocus(),
            filter = { Difficulty.admitsTier(it.tier, difficulty) },
        ).plan(id, Difficulty.wordCoachKinds(difficulty))

    /**
     * Free practice: the same two cuts. The plan is what the Leitner boxes are due for; this is what
     * is left when he presses on past it, and a word too hard for the dot he set is no more his to
     * meet here than it was there.
     */
    override suspend fun practiceFor(graph: AppGraph, difficulty: Int): List<Item> {
        val kinds = Difficulty.wordCoachKinds(difficulty)
        return planFor(graph, difficulty).ifEmpty {
            graph.db.items().activeOfKinds(kinds)
                .filter { Difficulty.admitsTier(it.tier, difficulty) }
                .shuffled().take(8)
        }
    }

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        WordCoachScreen(items, sessionId, onDone, onLeave)
}
