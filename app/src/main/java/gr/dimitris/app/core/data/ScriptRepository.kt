package gr.dimitris.app.core.data

import kotlinx.coroutines.flow.Flow
import java.io.File

/** One turn as the caregiver typed it, before it becomes an item and a row. */
data class LineDraft(val speaker: Speaker, val text: String, val recordingFile: File? = null, val recordingMs: Long = 0)

/** A script with its live lines, each paired with the item that carries its text, picture and voice. */
data class ScriptWithLines(val script: Script, val lines: List<Pair<ScriptLine, Item>>)

/**
 * All writes to scripts go through here, so a line is always an [Item] as well as a row: that is
 * what gives a dialogue turn a model voice, a first sound and a Leitner box for free.
 */
class ScriptRepository(
    private val scripts: ScriptDao,
    private val items: ItemRepository,
    /**
     * How a group of writes becomes one commit. The app passes Room's withTransaction; JVM tests
     * pass a runner they can make fail. It stays before [clock] so `ScriptRepository(dao, items) { now }`
     * still binds its trailing lambda to the clock.
     */
    private val inTransaction: suspend (suspend () -> Unit) -> Unit = { block -> block() },
    private val clock: () -> Long = ::now,
) {
    fun observeAll(): Flow<List<Script>> = scripts.observeScripts()

    suspend fun load(id: String): ScriptWithLines? {
        val script = scripts.get(id)?.takeIf { !it.deleted } ?: return null
        val lines = scripts.linesFor(id).mapNotNull { line -> items.get(line.itemId)?.let { line to it } }
        return ScriptWithLines(script, lines)
    }

    /**
     * The script a session item came from, or null when the item is an ordinary word. Resolved from
     * the item alone so nothing has to carry "which script are we in" between screens.
     */
    suspend fun scriptOf(itemId: String): ScriptWithLines? = scripts.lineOfItem(itemId)?.let { load(it.scriptId) }

    /**
     * Creates or replaces a script.
     *
     * A turn whose text *and* speaker are unchanged keeps everything it had: its [ScriptLine] row,
     * its [Item], and every recording pointing at that item. Only what she actually changed — a
     * reworded line, a speaker flipped, a turn added — becomes a new item. Rebuilding every line on
     * every save was harmless to look at and unbounded underneath: ten passes over an eight-line
     * dialogue left eighty items nothing pointed at, in the database, in the backup and in the
     * seed importer's "already here" set. Reordering alone changes nothing but positions.
     *
     * The item of a line she really did change is *not* deleted: the attempt history points at it,
     * and that is what keeps her earlier work readable.
     *
     * Two stages, in this order for a reason. The items and their recordings are built first: that
     * is the slow part (file I/O), and it is additive — an item nothing points at is invisible, so
     * a crash halfway through costs nothing. Only then does one transaction swap the lines over, so
     * there is never a moment where the old lines are gone and the new ones have not landed. A
     * process death used to be able to leave the caregiver's whole dialogue empty and say nothing.
     *
     * A line that keeps a recording it already had passes the same [File] back in. That is safe:
     * [ItemRepository.addRecording] only writes a row and relativizes the path — it never copies,
     * moves or deletes the file — and it recognises the take it already holds, so no second row is
     * written for it.
     */
    suspend fun save(id: String?, title: String, lines: List<LineDraft>, source: Source = Source.CAREGIVER): Script {
        val t = clock()
        val existing = id?.let { scripts.get(it) }
        val script = (existing ?: Script(title = title.trim(), source = source, createdAt = t)).copy(title = title.trim(), updatedAt = t)
        // The turns as they stand, so an unchanged one can be recognised and kept. Each is claimed
        // at most once: two identical turns in one dialogue keep one row each, not the same row.
        val reusable = if (existing == null) mutableListOf() else scripts.linesFor(script.id)
            .mapNotNull { line -> items.get(line.itemId)?.let { line to it } }
            .toMutableList()
        val rows = lines.filter { it.text.isNotBlank() }.mapIndexed { i, d ->
            val text = d.text.trim()
            val kept = reusable.firstOrNull { (line, item) -> line.speaker == d.speaker && item.text == text }
            val itemId = if (kept != null) {
                reusable.remove(kept)
                kept.second.id
            } else {
                items.save(Item(text = text, kind = ItemKind.SCRIPT_LINE, category = Category.CUSTOM, source = source)).id
            }
            if (d.recordingFile != null) items.addRecording(itemId, d.recordingFile, d.recordingMs, Who.CAREGIVER)
            // The kept row's own id, so nothing that pointed at the turn has to be rewritten; the
            // soft-delete below is undone by this very upsert, inside the same transaction.
            kept?.first?.copy(position = i, updatedAt = t, deleted = false)
                ?: ScriptLine(scriptId = script.id, position = i, speaker = d.speaker, itemId = itemId, createdAt = t, updatedAt = t)
        }
        inTransaction {
            scripts.upsertScript(script)
            if (existing != null) scripts.softDeleteLinesOf(script.id, t)
            scripts.upsertLines(rows)
        }
        return script
    }

    /**
     * Soft, like everything else: a script the caregiver removes is still in the backup and the
     * sync log. One transaction too, so a script is never left visible with its lines already gone.
     */
    suspend fun delete(id: String) {
        val t = clock()
        inTransaction {
            scripts.softDeleteLinesOf(id, t)
            scripts.softDeleteScript(id, t)
        }
    }
}
