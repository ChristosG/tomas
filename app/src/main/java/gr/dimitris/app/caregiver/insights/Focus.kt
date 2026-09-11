package gr.dimitris.app.caregiver.insights

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.greek.Greek
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates
import gr.dimitris.app.modules.sql.SqlPuzzles
import gr.dimitris.app.modules.trace.TraceViewModel
import gr.dimitris.app.core.data.Advice as AdviceRow

/**
 * What Claude asked the app to work on, and the one part of an answer the app *acts* on.
 *
 * The rest of an advice is words for people to read. This is the machine-readable tail of it — the
 * `## Εστίαση` object — and it is what makes the loop close: the advisor reads his whole journey,
 * names a handful of words and first sounds, and the next morning's session puts those first. The
 * caregivers never have to copy anything across by hand, which is the difference between advice and
 * advice that happens.
 *
 * Everything about reading it is deliberately forgiving, because the other end is a language model
 * and a strict parser here would mean a silently ignored answer:
 *
 * * A missing key is an empty list, never a failure.
 * * A word that is not in the vocabulary **today** is dropped — matched on normalised, unaccented
 *   text, so «Καφές» finds `καφές`. The raw JSON is what is stored, not this, so a word a caregiver
 *   adds next week starts being honoured then.
 * * A level outside a module's range is clamped into it. A model that says «trace: 9» means "move
 *   him up", and the app knows what its own ceiling is.
 * * Anything that is not an object at all — prose, an array, half a sentence — reads as [EMPTY].
 *
 * [at] is when the advice was given; a focus older than [WINDOW_MS] is not applied. A week is the
 * horizon the prompt itself asks the model to advise over, and a plan nobody has refreshed in a
 * fortnight should not still be steering his mornings.
 */
data class Focus(
    val items: List<String> = emptyList(),
    val sounds: List<String> = emptyList(),
    val modules: List<ModuleId> = emptyList(),
    /** Keyed by [NUMBERS], [SENTENCES], [TRACE], [SQL], [STEPS]; clamped to each module's own range. */
    val levels: Map<String, Int> = emptyMap(),
    val why: String = "",
    val at: Long = 0L,
) {
    /** Nothing to act on. An advice may perfectly well name no words at all. */
    val isEmpty: Boolean get() = items.isEmpty() && sounds.isEmpty() && modules.isEmpty() && levels.isEmpty()

    /** Still worth steering by: within [WINDOW_MS] of [at], and not from a clock in the future. */
    fun isLive(now: Long): Boolean = at > 0 && now - at in 0..WINDOW_MS

    /**
     * Whether this word is one the focus asks for — by name, or by the sound it starts with.
     *
     * The sound half is what makes a focus worth more than a list: «δούλεψε τα «π»» is a piece of
     * speech-therapy advice, and it reaches every word beginning with π, including the ones the
     * caregiver adds tomorrow.
     */
    fun matches(item: Item): Boolean =
        key(item.text) in itemKeys || (item.firstSound.isNotBlank() && key(item.firstSound) in soundKeys)

    private val itemKeys: Set<String> by lazy { items.map(::key).toSet() }
    private val soundKeys: Set<String> by lazy { sounds.map(::key).toSet() }

    companion object {
        val EMPTY = Focus()

        /** The level keys, the same names the JSON uses and the settings setters take. */
        const val NUMBERS = "numbers"
        const val SENTENCES = "sentences"
        const val TRACE = "trace"

        /**
         * Phase 13's two, because the prompt tells the model that «levels» moves a dot and those two
         * tiles have dots like everything else.
         *
         * In both of them the number **is** the dot: «SQL»'s level and its dot are one number
         * ([Difficulty.sqlDot]), and «Βήματα» has no stored level at all — the dot is the whole of its
         * difficulty ([Difficulty.stepsTier]). So 1..5 for each, which is also what
         * `Settings.setSqlLevel` and `Settings.setDifficulty` clamp to.
         */
        const val SQL = "sql"
        const val STEPS = "steps"

        /** A week: the horizon the prompt asks the model to advise over. */
        const val WINDOW_MS = 7L * 24 * 60 * 60 * 1000

        /** More than this is not a focus, it is a vocabulary. */
        const val MAX_ITEMS = 40
        const val MAX_SOUNDS = 8
        const val MAX_MODULES = 8
        const val MAX_WHY = 400

        private val RANGES: Map<String, IntRange> = mapOf(
            NUMBERS to NumberProgression.MIN_LEVEL..NumberProgression.MAX_LEVEL,
            SENTENCES to SentenceTemplates.MIN_LEVEL..SentenceTemplates.MAX_LEVEL,
            TRACE to TraceViewModel.MIN_LEVEL..TraceViewModel.MAX_LEVEL,
            SQL to SqlPuzzles.MIN_LEVEL..SqlPuzzles.MAX_LEVEL,
            STEPS to Difficulty.MIN..Difficulty.MAX,
        )

        /**
         * Reads one `## Εστίαση` object. [known] is the vocabulary as it stands now — the words a
         * focus is allowed to name; anything else the model invented is dropped rather than shown,
         * because a chip a caregiver taps has to be a word that exists, and the text it is given
         * back as is the vocabulary's own rather than the model's.
         *
         * `null` means "do not filter": the session builder matches on normalised text against its
         * own pool anyway, so a word that is not there simply matches nothing, and the extra query
         * to find that out twice would be work for no answer.
         */
        fun parse(raw: String, known: Collection<String>? = null, at: Long = 0L): Focus {
            val obj = objectOf(raw) ?: return Focus(at = at)
            val canonical = known?.associateBy(::key)
            val words = strings(obj, "items")
                .mapNotNull { if (canonical == null) it else canonical[key(it)] }
                .distinct().take(MAX_ITEMS)
            return Focus(
                items = words,
                sounds = strings(obj, "sounds").map { Greek.stripAccents(Greek.normalize(it)) }
                    .filter { it.isNotBlank() }.distinct().take(MAX_SOUNDS),
                modules = strings(obj, "modules")
                    .mapNotNull { name -> runCatching { ModuleId.valueOf(name.trim().uppercase()) }.getOrNull() }
                    .distinct().take(MAX_MODULES),
                levels = levels(obj),
                why = string(obj, "why").take(MAX_WHY),
                at = at,
            )
        }

        /** One stored advice as a focus, or null when it carried none. */
        fun of(advice: AdviceRow?, known: Collection<String>? = null): Focus? {
            if (advice == null || advice.deleted || advice.focusJson.isBlank()) return null
            val focus = parse(advice.focusJson, known, advice.at)
            return if (focus.isEmpty) null else focus
        }

        /**
         * The focus the app should be steering by right now: the newest advice, if it is not older
         * than [WINDOW_MS]. Null means "plan the session exactly as it was planned before" — which
         * is the state the app is in until somebody asks Claude for the first time.
         */
        fun active(previous: List<AdviceRow>, known: Collection<String>?, now: Long): Focus? =
            previous.filter { !it.deleted }.maxByOrNull { it.at }
                ?.let { of(it, known) }
                ?.takeIf { it.isLive(now) }

        /** Lower-case, unaccented: «Καφές», «καφες» and «ΚΑΦΈΣ» are the same word to a model. */
        private fun key(text: String): String = Greek.stripAccents(Greek.normalize(text))

        /** The first JSON object in the text, however much prose the model wrapped it in. */
        private fun objectOf(raw: String): JsonObject? {
            val start = raw.indexOf('{')
            val end = raw.lastIndexOf('}')
            if (start < 0 || end <= start) return null
            return runCatching { JsonParser.parseString(raw.substring(start, end + 1)) }
                .getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject
        }

        /**
         * A list of strings under [name]. A bare string where an array was asked for is taken as a
         * list of one — models do that, and refusing it would throw away real advice.
         */
        private fun strings(obj: JsonObject, name: String): List<String> {
            val element: JsonElement = obj.get(name) ?: return emptyList()
            val raw = when {
                element.isJsonArray -> element.asJsonArray.mapNotNull { primitive(it) }
                else -> listOfNotNull(primitive(element))
            }
            return raw.map { it.trim() }.filter { it.isNotBlank() }
        }

        private fun string(obj: JsonObject, name: String): String =
            obj.get(name)?.let { primitive(it) }?.trim().orEmpty()

        private fun primitive(element: JsonElement): String? =
            if (element.isJsonPrimitive) element.asString else null

        /** Only the five the app owns, only as whole numbers, only inside their own ranges. */
        private fun levels(obj: JsonObject): Map<String, Int> {
            val levels = obj.get("levels")?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
            val out = linkedMapOf<String, Int>()
            for ((name, range) in RANGES) {
                val value = levels.get(name)?.takeIf { it.isJsonPrimitive }
                    ?.let { runCatching { it.asInt }.getOrNull() } ?: continue
                out[name] = value.coerceIn(range)
            }
            return out
        }
    }
}
