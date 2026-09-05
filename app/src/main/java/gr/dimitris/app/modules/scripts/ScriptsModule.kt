package gr.dimitris.app.modules.scripts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.modules.Module

/**
 * Rehearsed dialogues: the phone plays the other side and waits for his turn, with the cue ladder
 * under it.
 *
 * The unit here is a whole dialogue, not a line. A conversation cannot be cut in half, so the module
 * offers **one** script per visit and returns its own turns as the items — they are what produce
 * Attempt rows, so the session's planned count is the number of exercises it will really see. The
 * session budget may still shorten that list; the screen runs the dialogue whole regardless.
 *
 * Nothing anywhere remembers "the script we are practising". The screen resolves it from the first
 * item it is handed, through the line that item belongs to, so the plan and the screen can never
 * disagree about which dialogue this is.
 */
object ScriptsModule : Module {
    override val id = ModuleId.SCRIPTS
    override val titleGreek = "Διάλογοι"
    override val icon: ImageVector = Icons.Rounded.Chat

    /** The dialogue that is due, else the one he has gone longest without. */
    override suspend fun planFor(graph: AppGraph): List<Item> {
        val ready = practisable(graph)
        if (ready.isEmpty()) return emptyList()
        val due = graph.scheduler.due(id).map { it.itemId }.toSet()
        return turnsOf(graph, ready.firstOrNull { it in due } ?: leastRecentlyPractised(graph, ready))
    }

    /** Free practice is his own choice of sitting: any dialogue, not the one the boxes picked. */
    override suspend fun practiceFor(graph: AppGraph): List<Item> =
        practisable(graph).randomOrNull()?.let { turnsOf(graph, it) } ?: emptyList()

    /**
     * The dialogues worth opening: the live ones that give him a turn. A script of nothing but the
     * other person's lines is somebody's half-finished edit — it would produce no Attempt, so the
     * session would count it as work he never did.
     *
     * Reads the lines rather than the whole [gr.dimitris.app.core.data.ScriptRepository.load], so
     * planning a session costs one query per script instead of one per line.
     */
    private suspend fun practisable(graph: AppGraph): List<String> {
        val dao = graph.db.scripts()
        return dao.activeScripts()
            .filter { script -> dao.linesFor(script.id).any { it.speaker == Speaker.DIMITRIS } }
            .map { it.id }
    }

    /** A dialogue nobody has ever opened has no schedule row at all, and goes first. */
    private suspend fun leastRecentlyPractised(graph: AppGraph, ids: List<String>): String {
        val seen = graph.db.schedules().all(id).associate { it.itemId to (it.lastSeenAt ?: 0L) }
        return ids.minByOrNull { seen[it] ?: NEVER } ?: ids.first()
    }

    private suspend fun turnsOf(graph: AppGraph, scriptId: String): List<Item> =
        graph.scripts.load(scriptId)?.lines.orEmpty()
            .filter { (line, _) -> line.speaker == Speaker.DIMITRIS }
            .map { (_, item) -> item }

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        ScriptsScreen(items, sessionId, onDone, onLeave)

    /** Older than any timestamp, so "never practised" sorts ahead of "practised at the epoch". */
    private const val NEVER = -1L
}
