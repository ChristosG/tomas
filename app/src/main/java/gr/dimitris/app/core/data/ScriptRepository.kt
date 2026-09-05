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
     * Creates or replaces a script. Old lines are soft-deleted and fresh ones are written, so a
     * re-ordered dialogue never leaves two rows claiming the same position; the old lines' items
     * stay behind because the attempt history still points at them.
     *
     * A line that keeps a recording it already had passes the same [File] back in. That is safe:
     * [ItemRepository.addRecording] only writes a row and relativizes the path — it never copies,
     * moves or deletes the file — so a take already sitting in the recordings dir is simply
     * pointed at a second time, and the previous line's row keeps working too.
     */
    suspend fun save(id: String?, title: String, lines: List<LineDraft>, source: Source = Source.CAREGIVER): Script {
        val t = clock()
        val existing = id?.let { scripts.get(it) }
        val script = (existing ?: Script(title = title.trim(), source = source, createdAt = t)).copy(title = title.trim(), updatedAt = t)
        scripts.upsertScript(script)
        if (existing != null) scripts.softDeleteLinesOf(script.id, t)
        val rows = lines.filter { it.text.isNotBlank() }.mapIndexed { i, d ->
            val item = items.save(Item(text = d.text, kind = ItemKind.SCRIPT_LINE, category = Category.CUSTOM, source = source))
            if (d.recordingFile != null) items.addRecording(item.id, d.recordingFile, d.recordingMs, Who.CAREGIVER)
            ScriptLine(scriptId = script.id, position = i, speaker = d.speaker, itemId = item.id, createdAt = t, updatedAt = t)
        }
        scripts.upsertLines(rows)
        return script
    }

    /** Soft, like everything else: a script the caregiver removes is still in the backup and the sync log. */
    suspend fun delete(id: String) {
        val t = clock()
        scripts.softDeleteLinesOf(id, t)
        scripts.softDeleteScript(id, t)
    }
}
