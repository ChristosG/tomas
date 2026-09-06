package gr.dimitris.app.core.sync

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ErrorLog
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Recording
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Script
import gr.dimitris.app.core.data.ScriptLine
import gr.dimitris.app.core.data.Session

/** One row on the wire: the server's table name and the row as plain JSON values. */
data class SyncRow(val table: String, val row: Map<String, Any?>)

/**
 * What the app knows about one synced table.
 *
 * [appendOnly] is the merge rule the server also holds: `attempts` and `error_logs` are immutable
 * facts, so the first row stored for an id wins and a second one is dropped on both sides.
 * Everything else is last-write-wins on `updatedAt`, ties keeping what is already there.
 *
 * [mediaFields] maps a column that holds a file path to the extension its bytes get when they are
 * downloaded from the server, where files are named by their sha-256 and carry no name of their own.
 */
data class TableSpec(
    val name: String,
    val type: Class<*>,
    val appendOnly: Boolean,
    val mediaFields: Map<String, String> = emptyMap(),
    /**
     * The row's id for the server. Every table but `schedules` has an `id` column; a schedule is
     * keyed on (itemId, module), which travels as `"$itemId:$module"`.
     */
    val idOf: (Map<String, Any?>) -> String?,
    /** The row as the server must see it: [idOf] added where the table has no `id` column of its own. */
    val withId: (Map<String, Any?>) -> Map<String, Any?> = { it },
)

/**
 * The eight tables that sync. Listed with items before the rows that point at them, which is how
 * they are *read*; what goes on the wire is sorted by `updatedAt` across all of them, because the
 * push watermark is one number and a failed batch must only hold back rows at least as new as
 * itself ([SyncEngine]). Nothing depends on either order — there are no foreign keys and every row
 * is merged on its own.
 */
object Tables {
    const val ITEMS = "items"
    const val RECORDINGS = "recordings"
    const val ATTEMPTS = "attempts"
    const val SCHEDULES = "schedules"
    const val SESSIONS = "sessions"
    const val ERROR_LOGS = "error_logs"
    const val SCRIPTS = "scripts"
    const val SCRIPT_LINES = "script_lines"

    /** What a photo is called once it is content-addressed. Coil sniffs the bytes, not the name. */
    const val PHOTO_EXT = "jpg"

    /** Matches [gr.dimitris.app.core.audio.MediaFiles.newRecordingFile]. */
    const val RECORDING_EXT = "m4a"

    val all: List<TableSpec> = listOf(
        TableSpec(ITEMS, Item::class.java, appendOnly = false, mediaFields = mapOf("imagePath" to PHOTO_EXT), idOf = ::plainId),
        TableSpec(SCRIPTS, Script::class.java, appendOnly = false, idOf = ::plainId),
        TableSpec(SCRIPT_LINES, ScriptLine::class.java, appendOnly = false, idOf = ::plainId),
        TableSpec(RECORDINGS, Recording::class.java, appendOnly = false, mediaFields = mapOf("path" to RECORDING_EXT), idOf = ::plainId),
        TableSpec(SESSIONS, Session::class.java, appendOnly = false, idOf = ::plainId),
        TableSpec(
            SCHEDULES, Schedule::class.java, appendOnly = false,
            idOf = { row -> scheduleId(row) },
            withId = { row -> scheduleId(row)?.let { row + ("id" to it) } ?: row },
        ),
        TableSpec(ATTEMPTS, Attempt::class.java, appendOnly = true, idOf = ::plainId),
        TableSpec(ERROR_LOGS, ErrorLog::class.java, appendOnly = true, idOf = ::plainId),
    )

    private val byName: Map<String, TableSpec> = all.associateBy { it.name }

    fun of(name: String): TableSpec? = byName[name]

    private fun plainId(row: Map<String, Any?>): String? = row["id"] as? String

    private fun scheduleId(row: Map<String, Any?>): String? {
        val itemId = row["itemId"] as? String ?: return null
        val module = row["module"] as? String ?: return null
        return "$itemId:$module"
    }
}

/**
 * Rows as maps, and the two conversions to and from the entities.
 *
 * Gson is told to write nulls: the entities are Kotlin data classes with default arguments, which
 * Gson cannot see (it allocates the object and fills fields), so a field left out of the JSON comes
 * back as a JVM zero rather than as the default the class declares. Writing every field, null ones
 * included, is what makes a round trip give back the row that went in.
 *
 * Whole numbers stay [Long]. Gson's own map deserialization turns every number into a [Double], and
 * `updatedAt` — the one value the whole merge turns on — must not arrive as `1.757E12`.
 */
object Rows {
    val gson: Gson = GsonBuilder().serializeNulls().create()

    fun of(entity: Any): Map<String, Any?> = fromJson(gson.toJsonTree(entity).asJsonObject)

    fun <T> to(row: Map<String, Any?>, type: Class<T>): T = gson.fromJson(toJson(row), type)

    fun updatedAt(row: Map<String, Any?>): Long = (row["updatedAt"] as? Number)?.toLong() ?: 0L

    fun toJson(row: Map<String, Any?>): JsonObject {
        val out = JsonObject()
        for ((key, value) in row) out.add(key, element(value))
        return out
    }

    fun fromJson(obj: JsonObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(obj.size())
        for ((key, value) in obj.entrySet()) out[key] = value(value)
        return out
    }

    private fun element(value: Any?): JsonElement = when (value) {
        null -> JsonNull.INSTANCE
        is JsonElement -> value
        is Number -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> {
            val obj = JsonObject()
            for ((k, v) in value) obj.add(k.toString(), element(v))
            obj
        }
        is Iterable<*> -> JsonArray().apply { value.forEach { add(element(it)) } }
        else -> JsonPrimitive(value.toString())
    }

    private fun value(element: JsonElement): Any? = when {
        element.isJsonNull -> null
        element.isJsonObject -> fromJson(element.asJsonObject)
        element.isJsonArray -> element.asJsonArray.map { value(it) }
        else -> {
            val primitive = element.asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asString.toLongOrNull() ?: primitive.asDouble
                else -> primitive.asString
            }
        }
    }
}
