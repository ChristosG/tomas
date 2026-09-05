package gr.dimitris.app.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeScriptDao : ScriptDao {
    val scripts = MutableStateFlow<Map<String, Script>>(emptyMap())
    val lines = mutableMapOf<String, ScriptLine>()
    override suspend fun upsertScript(script: Script) { scripts.value = scripts.value + (script.id to script) }
    override suspend fun upsertLines(lines: List<ScriptLine>) { lines.forEach { this.lines[it.id] = it } }
    override fun observeScripts(): Flow<List<Script>> = scripts.map { m -> m.values.filter { !it.deleted }.sortedBy { it.title } }
    override suspend fun activeScripts(): List<Script> = scripts.value.values.filter { !it.deleted }.sortedBy { it.title }
    override suspend fun get(id: String): Script? = scripts.value[id]
    override suspend fun linesFor(scriptId: String): List<ScriptLine> = lines.values.filter { it.scriptId == scriptId && !it.deleted }.sortedBy { it.position }
    override suspend fun lineOfItem(itemId: String): ScriptLine? = lines.values.filter { it.itemId == itemId && !it.deleted }.maxByOrNull { it.updatedAt }
    override suspend fun softDeleteScript(id: String, now: Long) { scripts.value[id]?.let { upsertScript(it.copy(deleted = true, updatedAt = now)) } }
    override suspend fun softDeleteLinesOf(scriptId: String, now: Long) { lines.values.filter { it.scriptId == scriptId }.forEach { lines[it.id] = it.copy(deleted = true, updatedAt = now) } }
}
