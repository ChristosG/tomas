package gr.dimitris.app.modules.numbers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.Module

object NumbersModule : Module {
    override val id = ModuleId.NUMBERS
    override val titleGreek = "Αριθμοί"
    override val icon: ImageVector = Icons.Rounded.Calculate
    const val EXERCISES_PER_SESSION = 10

    /** Number exercises are generated, not item-based; the returned list only sizes the session. */
    override suspend fun planFor(graph: AppGraph): List<Item> =
        graph.db.items().activeOfKinds(listOf(ItemKind.NUMBER)).take(EXERCISES_PER_SESSION)
            .ifEmpty { List(EXERCISES_PER_SESSION) { Item(text = "Αριθμοί", kind = ItemKind.NUMBER, category = Category.NUMBERS) } }

    /**
     * [items] are placeholders, but their number is the session's budget for this module: it runs one
     * exercise per item it was given, which is the contract every module owes the session runner.
     */
    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        NumbersScreen(exercisesFor(items.size), sessionId, onDone, onLeave)
}
