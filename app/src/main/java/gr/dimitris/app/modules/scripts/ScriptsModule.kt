package gr.dimitris.app.modules.scripts

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Chat
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.ScheduleDao
import gr.dimitris.app.core.data.ScriptDao
import gr.dimitris.app.core.data.ScriptLine
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.modules.Module

/**
 * Rehearsed dialogues: the phone plays the other side and waits for his turn, with the cue ladder
 * under it.
 *
 * The unit here is a whole dialogue, not a line. A conversation cannot be cut in half, so the module
 * offers **one** script per visit and returns its own turns as the items — they are what produce
 * Attempt rows, so the session's planned count is the number of exercises it will really see. The
 * dialogue is [atomic]: the session budget shortens every other module's list, never this one, so
 * the count the session row records is the count the module will really run.
 *
 * Nothing anywhere remembers "the script we are practising". The screen resolves it from the first
 * item it is handed, through the line that item belongs to, so the plan and the screen can never
 * disagree about which dialogue this is.
 */
object ScriptsModule : Module {
    override val id = ModuleId.SCRIPTS
    override val titleGreek = "Διάλογοι"
    override val icon: ImageVector = Icons.Rounded.Chat

    /** A conversation is one exercise however many turns it has: it is never cut to fit. */
    override val atomic = true

    /** The dialogue that is due, else the one he has gone longest without. */
    override suspend fun planFor(graph: AppGraph): List<Item> = planFor(graph, Difficulty.DEFAULT)

    /** Free practice is his own choice of sitting: any dialogue, not the one the boxes picked. */
    override suspend fun practiceFor(graph: AppGraph): List<Item> = practiceFor(graph, Difficulty.DEFAULT)

    override suspend fun planFor(graph: AppGraph, difficulty: Int): List<Item> =
        choose(graph.db.scripts(), graph.db.schedules(), now(), difficulty)?.let { turnsOf(graph, it) } ?: emptyList()

    override suspend fun practiceFor(graph: AppGraph, difficulty: Int): List<Item> =
        practisable(graph.db.scripts(), difficulty).randomOrNull()?.let { turnsOf(graph, it) } ?: emptyList()

    /**
     * Today's dialogue, or null when there is none worth opening.
     *
     * Among the dialogues the boxes have brought round, the one he has gone longest without —
     * *not* the alphabetically first. Reading the due rows through a `Set` was the defect this
     * replaces: it threw away the DAO's ordering and left the choice to `activeScripts()`, which is
     * `ORDER BY title`, so one dialogue stuck in a low box was handed to him every single day and
     * the other five were never seen again. The DAO's own order (soonest due first) is kept, so it
     * still breaks a tie between two rows last seen at the same moment.
     *
     * The DAOs rather than the graph, so a test can watch the choice over fakes.
     */
    internal suspend fun choose(scripts: ScriptDao, schedules: ScheduleDao, now: Long, difficulty: Int = Difficulty.DEFAULT): String? {
        val ready = practisable(scripts, difficulty)
        if (ready.isEmpty()) return null
        val readySet = ready.toSet()
        val overdue = schedules.due(id, now).filter { it.itemId in readySet }.minByOrNull { it.lastSeenAt ?: NEVER }
        return overdue?.itemId ?: leastRecentlyPractised(schedules, ready)
    }

    /**
     * The dialogues worth opening: the live ones that give him a turn. A script of nothing but the
     * other person's lines is somebody's half-finished edit — it would produce no Attempt, so the
     * session would count it as work he never did.
     *
     * Reads the lines rather than the whole [gr.dimitris.app.core.data.ScriptRepository.load], so
     * planning a session costs one query per script instead of one per line.
     *
     * [difficulty] caps how hard a dialogue may be ([Difficulty.scriptTier]): dot n takes every
     * dialogue of tier n and below. A dialogue is as hard as its hardest line ([tierOf]), which is
     * what the seed writes and what the caregiver's editor sets for the whole conversation at once.
     *
     * A **ceiling** and not a window. An easier conversation than the dot asks for is still worth
     * having — the two-turn exchange at the bakery is a real errand — and dropping it would strand
     * whatever a caregiver had written and left its Leitner row overdue for ever. A ceiling no
     * dialogue is under still widens back to all of them, because a conversation *is* this module:
     * "nothing for you today" for having asked for harder work is not an answer.
     */
    internal suspend fun practisable(scripts: ScriptDao, difficulty: Int = Difficulty.DEFAULT): List<String> {
        val lines = scripts.activeScripts().associate { script -> script.id to scripts.linesFor(script.id) }
        // A dialogue of nothing but the other person's lines would produce no attempt at all.
        val ready = lines.filterValues { l -> l.any { it.speaker == Speaker.DIMITRIS } }
        val ceiling = Difficulty.scriptTier(difficulty)
        val wanted = ready.filterValues { tierOf(it) <= ceiling }
        // The map keeps `activeScripts()`'s order — soonest-due ties are broken by it downstream.
        return (if (wanted.isEmpty()) ready else wanted).keys.toList()
    }

    /**
     * How hard one dialogue is: the hardest of its turns.
     *
     * Held to 1..5 line by line, because a line can arrive over sync from a phone that predates the
     * column at all and carries a plain zero. A dialogue with no lines left is the easiest thing
     * there is, which keeps it in reach of every dot rather than hiding it at the top.
     */
    internal fun tierOf(lines: List<ScriptLine>): Int =
        lines.maxOfOrNull { Difficulty.clamp(it.tier) } ?: Difficulty.MIN

    /** A dialogue nobody has ever opened has no schedule row at all, and goes first. */
    private suspend fun leastRecentlyPractised(schedules: ScheduleDao, ids: List<String>): String {
        val seen = schedules.all(id).associate { it.itemId to (it.lastSeenAt ?: 0L) }
        return ids.minByOrNull { seen[it] ?: NEVER } ?: ids.first()
    }

    /**
     * The items one named dialogue is run from: his own turns, in order. Internal because
     * [gr.dimitris.app.today.PracticeViewModel] resolves «Παίξ' το» through it — the dialogue the
     * caregiver just wrote, not the one the boxes would have picked — and the screen reads the
     * script back out of the first of them, so both routes in must build the list the same way.
     */
    internal suspend fun turnsOf(graph: AppGraph, scriptId: String): List<Item> =
        graph.scripts.load(scriptId)?.lines.orEmpty()
            .filter { (line, _) -> line.speaker == Speaker.DIMITRIS }
            .map { (_, item) -> item }

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) =
        ScriptsScreen(items, sessionId, onDone, onLeave)

    /** Older than any timestamp, so "never practised" sorts ahead of "practised at the epoch". */
    private const val NEVER = -1L
}
