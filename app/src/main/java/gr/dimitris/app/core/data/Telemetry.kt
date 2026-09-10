package gr.dimitris.app.core.data

import com.google.gson.Gson
import com.google.gson.JsonElement
import kotlin.math.roundToInt

/**
 * What an exercise writes into [Attempt.detail] so that it could be adapted later.
 *
 * Chris' rule: keep the data now, wherever adaptation could help him later, so tempo, difficulty and
 * timing are tuned from Dimitris' own behaviour instead of guessed at. Nothing in the app reads these
 * keys yet — `docs/ADAPTATION.md` is the list of rules we would try first, and every one of them
 * needs months of rows before it can be argued with. Writing them costs a few dozen bytes a row;
 * *not* writing them costs a year.
 *
 * There is no schema change here and there never needs to be one: [Attempt.detail] is JSON, so a new
 * question is a new key rather than a migration. What this file buys is that the keys are written the
 * same way everywhere, and that the same rule is enforced in one place instead of in eight view
 * models: **a detail carries plain numbers, booleans and short words, and nothing else.**
 *
 * That rule is not tidiness. An attempt row leaves the phone twice — it syncs to the father's server
 * and it goes to Claude inside the journey report — so a file path, a recording id or a whole
 * sentence of his written in here would leave with it. Item ids and the script id are the exception,
 * and only because they were already on these rows before this file existed: [Detail.kept] is the
 * one door for them, and it is named so that a reviewer can see every one of them at a glance.
 *
 * Usage, from a view model:
 * ```
 * val detail = Adapt.detail {
 *     put("listened", listens)
 *     put("ms", now() - startedAt)
 * }
 * ```
 * Nulls are dropped rather than written, which is what the trace module already relied on Gson for:
 * a key that has nothing to say is absent, never present and null. Insertion order is the written
 * order, so a module that puts its old keys first keeps its rows byte-identical to the ones before
 * this file, and every reader written against them still works.
 */
object Adapt {
    /**
     * How much of a string may go into a detail. Long enough for what a recogniser makes of one
     * spoken phrase, short enough that nobody can start keeping notes in here.
     */
    const val MAX_TEXT = 200

    /** How many decimals a measurement keeps. A thousandth of a dp is not a fact about his hand. */
    const val DECIMALS = 1000f

    /** Configured exactly as every module's own `Gson()` was, so old keys serialise as they always did. */
    private val gson = Gson()

    /** Builds one `detail` value. `"{}"` when nothing was put in it, which is [Attempt.detail]'s own default. */
    fun detail(build: Detail.() -> Unit): String = Detail().apply(build).json()

    /**
     * The middle value of [xs], or null when there is nothing to take a middle of.
     *
     * The median and not the mean, because these are lists of tries by a man whose hand tires: one
     * target he took forty seconds over would drag a mean into saying the whole game was too small.
     */
    fun medianMs(xs: List<Long>): Long? {
        if (xs.isEmpty()) return null
        val s = xs.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2
    }

    /** The same for a measurement. See [medianMs]. */
    fun medianDp(xs: List<Float>): Float? {
        val finite = xs.filter { it.isFinite() }
        if (finite.isEmpty()) return null
        val s = finite.sorted()
        val mid = s.size / 2
        return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2f
    }

    /** One detail under construction. Not thread-safe: a detail is built by the writer that finishes an item. */
    class Detail internal constructor() {
        private val values = LinkedHashMap<String, Any>()

        fun put(key: String, v: Int?) { if (v != null) values[key] = v }

        fun put(key: String, v: Long?) { if (v != null) values[key] = v }

        fun put(key: String, v: Boolean?) { if (v != null) values[key] = v }

        /** Rounded to [DECIMALS]; a non-finite measurement is not a measurement and is dropped. */
        fun put(key: String, v: Float?) { if (v != null && v.isFinite()) values[key] = round(v) }

        /** Trimmed to [MAX_TEXT]. Blank is nothing to say, and nothing to say is written as absence. */
        fun put(key: String, v: String?) { if (!v.isNullOrBlank()) values[key] = v.take(MAX_TEXT) }

        /** A setting in force, by its own name — `SLOW`, `LOOSE`, `RIGHT`. */
        fun put(key: String, v: Enum<*>?) { if (v != null) values[key] = v.name }

        /** A list of counts, one per stage or per round. Empty is absent. */
        fun counts(key: String, v: List<Int>?) { if (!v.isNullOrEmpty()) values[key] = v.toList() }

        /** A list of times in milliseconds, one per stage or per round. Empty is absent. */
        fun times(key: String, v: List<Long>?) { if (!v.isNullOrEmpty()) values[key] = v.toList() }

        /** A list of short words — module names, tile labels. Blanks are dropped, each is trimmed. */
        fun words(key: String, v: List<String>?) {
            val kept = v.orEmpty().filter { it.isNotBlank() }.map { it.take(MAX_TEXT) }
            if (kept.isNotEmpty()) values[key] = kept
        }

        /** Milliseconds against a name — the session summary's time per module. Empty is absent. */
        fun times(key: String, v: Map<String, Long>?) { if (!v.isNullOrEmpty()) values[key] = LinkedHashMap(v) }

        /**
         * One small object under one key: the shape
         * [gr.dimitris.app.core.judge.Verdict.detail] hands over, and the only nested value a *new*
         * caller may write.
         *
         * [kept] is the door for the keys that were on these rows before this file existed, and it
         * says that nothing new goes through it. Phase 12 needs exactly one new nested value, and it
         * needs it in three modules: what the turn judge decided about a turn, as
         * `{source, accept, ms, expanded?}`. Giving it a name of its own is what keeps [kept]'s
         * promise true — a reviewer reading `kept` still sees only the old keys — and it is written
         * *through* [kept] so that the finite guard which protects every other number protects these
         * too: a NaN anywhere inside drops the whole object rather than throwing out of the builder
         * and taking the attempt row with it.
         *
         * The same absence rule as [put], one level down and one level up: a key inside with nothing
         * to say is dropped, and an object with nothing left in it is absent rather than `{}`.
         *
         * What it does **not** do is cap the strings inside it at [MAX_TEXT], the way `put(String)`
         * caps its own — the value is written as it was handed over, exactly as [kept] writes one.
         * That cap is the caller's, and for the one shape this exists for it is already applied
         * where the shape is built: see [gr.dimitris.app.core.judge.Verdict.detail], which takes
         * `expanded` down to [MAX_TEXT] before it ever reaches here. Anything new that writes a
         * nested object owes the row the same.
         */
        fun put(key: String, v: Map<String, Any?>?) {
            val said = v?.filterValues { it != null }
            if (!said.isNullOrEmpty()) kept(key, LinkedHashMap(said))
        }

        /**
         * A key a module already wrote before this file existed, put back exactly as it was.
         *
         * This is the only way anything that is not a plain number, boolean or short word reaches a
         * detail — the trace module's per-letter marks, the numbers module's exercise, the item id a
         * dialogue line belongs to — and it exists so that no reader written against those rows has
         * to be changed. Nothing new goes through here.
         *
         * It keeps the value as it is, but not at any price: a non-finite number anywhere inside it
         * drops the whole value, exactly as [put] drops one of its own. See [finite].
         */
        fun kept(key: String, v: Any?) { if (v != null && finite(v)) values[key] = v }

        /**
         * The same guarantee [put] gives every other number, for the shapes [kept] lets through: no
         * NaN and no infinity reaches the row, wherever it is hiding.
         *
         * Gson throws on a non-finite float, and that exception would come out of the builder
         * itself — before the `runCatching` that wraps the write — and take the attempt with it. The
         * callers' arithmetic is careful today; the type that owns the guarantee should not be
         * relying on that.
         *
         * The question is answered by Gson rather than by a hand-written walk over the types this
         * file happens to know about: the value is serialised to a [com.google.gson.JsonElement] —
         * exactly what [json] will do to it later — and every number in the tree is checked. So an
         * object's fields, an array, a list of maps of lists, and anything a future caller invents
         * are all covered by construction; the previous version knew only about maps and iterables,
         * and its one non-collection caller (the numbers module's exercise) was precisely the shape
         * it could not see into. If the serialisation itself fails — Gson refuses a non-finite
         * number as it writes it — the value is dropped for the same reason, because [json] would
         * have thrown on it too.
         *
         * A structure with one bad number in it is dropped whole: an array of per-letter marks
         * missing one letter would be read as a word with fewer letters.
         */
        private fun finite(v: Any?): Boolean =
            runCatching { allFinite(gson.toJsonTree(v)) }.getOrDefault(false)

        private fun allFinite(e: JsonElement): Boolean = when {
            e.isJsonPrimitive -> !e.asJsonPrimitive.isNumber || e.asJsonPrimitive.asDouble.isFinite()
            e.isJsonArray -> e.asJsonArray.all { allFinite(it) }
            e.isJsonObject -> e.asJsonObject.entrySet().all { allFinite(it.value) }
            else -> true
        }

        internal fun json(): String = gson.toJson(values)

        private fun round(v: Float): Float = (v * DECIMALS).roundToInt() / DECIMALS
    }
}
