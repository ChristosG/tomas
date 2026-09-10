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
     * The dots pick which tier of the vocabulary he is asked to retrieve: single words at 1, words
     * and phrases from 2 up ([Difficulty.wordCoachKinds]). Phrases are the harder retrieval — more to
     * hold, more to articulate — and the seed has no grading finer than that, so 3, 4 and 5 ask for
     * the same tier as 2 until phase 13 ships a graded vocabulary.
     *
     * Everything else about the plan is untouched: the Leitner boxes still decide what is due, the
     * focus still keeps its places, the sandwich still orders the sitting. The dots only narrow what
     * the boxes may choose from.
     */
    override suspend fun planFor(graph: AppGraph, difficulty: Int): List<Item> =
        SessionBuilder(graph.db.items(), graph.db.schedules(), focus = graph.activeFocus())
            .plan(id, Difficulty.wordCoachKinds(difficulty))

    override suspend fun practiceFor(graph: AppGraph, difficulty: Int): List<Item> {
        val tier = Difficulty.wordCoachKinds(difficulty)
        return planFor(graph, difficulty).ifEmpty { graph.db.items().activeOfKinds(tier).shuffled().take(8) }
    }

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        WordCoachScreen(items, sessionId, onDone, onLeave)
}
