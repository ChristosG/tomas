package gr.dimitris.app.modules.sentences

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ShortText
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.Module

object SentencesModule : Module {
    override val id = ModuleId.SENTENCES
    override val titleGreek = "Προτάσεις"
    override val icon: ImageVector = Icons.Rounded.ShortText

    /**
     * Sentences are generated, not item-based: the returned list only sizes the session, so it is
     * always the full eight transient placeholders. It deliberately asks the database nothing — the
     * words a sentence is made of are ordinary talk-board cards a caregiver may delete or add to,
     * and counting them would size the session by a vocabulary that has nothing to do with how many
     * sentences he can do in one sitting.
     * [gr.dimitris.app.today.SessionBudget] truncates this list; the screen runs what is left.
     */
    override suspend fun planFor(graph: AppGraph): List<Item> =
        List(SentenceTemplates.SENTENCES_PER_SESSION) { Item(text = titleGreek, kind = ItemKind.WORD, category = Category.CUSTOM) }

    /**
     * [items] are placeholders, but their number is the session's budget for this module: it runs
     * one sentence per item it was given, which is the contract every module owes the session runner.
     */
    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        SentencesScreen(items.size, sessionId, onDone, onLeave)
}
