package gr.dimitris.app.modules.arcade

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BackHand
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.Module

/**
 * The four games, in the order they are always played. Tap first because it is the one movement
 * every hand still has, pinch last because it is the hardest; between them the two that ask the arm
 * to travel.
 *
 * [id] is what goes on the attempt row — `arcade:tap` and the rest — so a physio reading the
 * database months from now can tell which exercise a number belongs to. [prompt] is the one line of
 * instruction on the screen, and what the phone says out loud when the game begins.
 */
enum class ArcadeGame(val id: String, val prompt: String) {
    TAP("tap", "Πάτα τον κύκλο"),
    TRACE("trace", "Ακολούθησε τη γραμμή"),
    DRAG("drag", "Σύρε τη μπάλα στο σπίτι της"),
    PINCH("pinch", "Άνοιξε με δύο δάχτυλα"),
}

/**
 * The right hand, as four short games.
 *
 * It is the one module that is off until someone switches it on: the hand it is for is the one the
 * stroke took, and nobody but his physio gets to decide that pushing it is a good idea. See
 * [gr.dimitris.app.core.settings.Settings.DEFAULT_OFF] and the note under the switch.
 *
 * Nothing here is a word, so the items [planFor] returns are placeholders that only size the
 * session: one per game, and the screen runs one game per item it is handed.
 */
object ArcadeModule : Module {
    override val id = ModuleId.ARCADE
    override val titleGreek = "Δεξί χέρι"
    override val icon: ImageVector = Icons.Rounded.BackHand

    /** One sitting: the four games once each. */
    val GAMES: List<ArcadeGame> = ArcadeGame.entries

    /**
     * Four transient placeholders, one per game. It asks the database nothing — the games are made
     * of shapes, not of vocabulary, and a device with no words at all still has a right hand.
     * [gr.dimitris.app.today.SessionBudget] may cut this list; the screen then runs what is left, in
     * the same order, which is the contract every module owes the session runner.
     */
    override suspend fun planFor(graph: AppGraph): List<Item> =
        GAMES.map { Item(text = titleGreek, kind = ItemKind.WORD, category = Category.CUSTOM) }

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        ArcadeScreen(items.size, sessionId, onDone, onLeave)
}
