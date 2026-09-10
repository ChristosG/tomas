package gr.dimitris.app.modules.steps

import android.content.res.AssetManager
import com.google.gson.Gson
import gr.dimitris.app.core.difficulty.Difficulty
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * One step of one task: a short Greek phrase, and the drawing that goes over it.
 *
 * [en] is the English term the pictogram was searched with — «water» for «Βάζω νερό στο μπρίκι» —
 * and it is kept in the seed rather than thrown away after the fetch so that a drawing which turns
 * out to be the wrong sense can be re-picked by correcting one word (see `tools/seed/fetch-steps.mjs`).
 * It is never shown to him: this app has no English in it.
 *
 * [image] is the file's path inside `assets/seed/`, so `steps/2248.png`. Null is a step that ships
 * text-led, which the screen draws as the phrase alone — a picture that means something else is worse
 * than no picture, and a step is a whole phrase that ARASAAC often has no drawing for.
 */
data class Step(
    val text: String = "",
    val en: String? = null,
    val image: String? = null,
) {
    /**
     * What Coil is given for the drawing, or null for a text-led step.
     *
     * These pictograms are **not** copied out of the APK the way the vocabulary's are
     * ([gr.dimitris.app.core.seed.SeedImporter]): the vocabulary becomes `Item` rows a caregiver can
     * re-photograph, so its pictures have to live in `filesDir` where a photo can replace them.
     * A step is content, not an item — nobody edits it, nothing syncs it — so its drawing is read
     * straight out of the assets, which is one copy, no write and nothing to keep in step.
     */
    val asset: String? get() = image?.trim()?.takeIf { it.isNotEmpty() }?.let { "$ASSET_BASE$it" }

    private companion object {
        /** Coil reads this scheme through its own `AssetUriFetcher`; the path is the one in the APK. */
        const val ASSET_BASE = "file:///android_asset/seed/"
    }
}

/**
 * One everyday task, as its steps.
 *
 * [difficulty] is the 1–5 of [gr.dimitris.app.core.difficulty.Difficulty] and it follows the number
 * of steps — three steps is 1, six is 4, six with a [distractor] among the tiles is 5 — because for
 * this module the number of things to hold in order *is* the difficulty. A dot admits every task at
 * or below it ([Difficulty.stepsTier]), so «Φτιάχνω καφέ» stays in the pool on the day he asks for
 * «Βγάζω χρήματα από το ΑΤΜ»: the easy tasks are the easy ends of the sitting.
 *
 * [distractor] is a real step of **another** task, on the board with the rest and belonging in none
 * of the order. It is the hardest thing this module asks: not "put these in order" but "decide what
 * is part of this at all", which is the planning Dimitris says he has lost — «είμαι καμένος» about
 * anything that needs steps.
 */
data class StepTask(
    val id: String = "",
    val title: String = "",
    val difficulty: Int = Difficulty.MIN,
    val steps: List<Step> = emptyList(),
    val distractor: Step? = null,
) {
    /** The steps in the order they have to go, as plain text. What the strip is checked against. */
    val order: List<String> get() = steps.map { it.text }

    /**
     * The telling: «Πρώτα βάζω νερό στο μπρίκι, μετά ρίχνω καφέ και ζάχαρη, τέλος το βάζω στη φωτιά.»
     *
     * This is what stage 2 is for and the reason this tile exists beside «Προτάσεις»: the connectors.
     * «πρώτα… μετά… τέλος» are the words that hold a sequence together in Greek, and a man who has
     * the nouns and the verbs but not the connectors cannot tell anybody how anything is done.
     *
     * It is the judge's `target` and the thing «Άκου» reads out in stage 2. The local fallback
     * compares against [joined] instead — see [StepTasks.LOCAL_TARGET].
     */
    val telling: String get() = tellingOf(order)

    /** The steps' own words, with no connectors: what a phone with no judge compares him against. */
    val joined: String get() = order.joinToString(" ")

    /**
     * The tiles as they are laid out: the steps and the distractor, shuffled.
     *
     * Plainly shuffled, so the board is sometimes in the answer's order — any rule that avoided that
     * would be a pattern he could learn instead of the task, which is the argument
     * [gr.dimitris.app.modules.sentences.SentencesViewModel] already makes about its own cards.
     */
    fun board(random: Random = Random.Default): List<Step> =
        (steps + listOfNotNull(distractor)).shuffled(random)

    companion object {
        /**
         * «Πρώτα …, μετά …, τέλος ….» built from the steps, with each step's first letter lowered:
         * the steps are written as sentences of their own («Βάζω νερό στο μπρίκι») and a capital in
         * the middle of the telling would be read out by the voice as a new sentence.
         */
        fun tellingOf(steps: List<String>): String {
            val said = steps.map { it.trim().trimEnd('.') }.filter { it.isNotEmpty() }.map(::lowerFirst)
            return when (said.size) {
                0 -> ""
                1 -> "${said.single().replaceFirstChar { it.uppercaseChar() }}."
                else -> buildString {
                    append(FIRST).append(' ').append(said.first())
                    for (middle in said.drop(1).dropLast(1)) append(", ").append(THEN).append(' ').append(middle)
                    append(", ").append(LAST).append(' ').append(said.last()).append('.')
                }
            }
        }

        private fun lowerFirst(s: String): String = s.replaceFirstChar { it.lowercaseChar() }

        /** The three connectors the whole stage is about. «τέλος» and not «στο τέλος»: it is shorter to say. */
        const val FIRST = "Πρώτα"
        const val THEN = "μετά"
        const val LAST = "τέλος"
    }
}

/**
 * The bundled tasks, as the phone reads them.
 *
 * **No database rows.** Everything else this app offers him is an `Item` — a word with a picture and
 * a voice a caregiver can replace — because everything else is his own vocabulary. A step is not: it
 * is content, written once, the same on every phone, and nobody will ever re-record «Ρίχνω καφέ και
 * ζάχαρη». Making them rows would have put ninety-six phrases into the talk board, the word coach's
 * pool and the sync log for nothing. So the seed is read from the assets when the screen opens and
 * that is the whole of its storage; what is written down is the attempt rows, which is the part that
 * is about him.
 */
class StepTasks(val tasks: List<StepTask>) {

    /**
     * The tasks one dot asks for: every task of that difficulty **and below** ([Difficulty.stepsTier]).
     *
     * Cumulative, like «Λέξεις» and «Διάλογοι» and for the same reason — see [Difficulty.stepsTier].
     */
    fun forDifficulty(dots: Int): List<StepTask> =
        tasks.filter { Difficulty.admitsSteps(it.difficulty, dots) }

    /**
     * [count] tasks for one sitting, hardest-first inside what the dot allows and then shuffled.
     *
     * A sitting is four tasks out of the twenty the dot admits, drawn without repeats while there are
     * enough to draw from: at dot 1 there are exactly four, so every sitting is all of them, and by
     * dot 3 there are twelve and two sittings in a row are different work. If the dot admits fewer
     * than [count] — nothing does today, and a hand-edited seed could — the list is filled by going
     * round again rather than coming up short, because a session that promised four exercises has to
     * run four.
     */
    fun plan(count: Int, dots: Int, random: Random = Random.Default): List<StepTask> {
        val pool = forDifficulty(dots).ifEmpty { tasks }
        if (pool.isEmpty() || count <= 0) return emptyList()
        val picked = mutableListOf<StepTask>()
        while (picked.size < count) picked += pool.shuffled(random).take(count - picked.size)
        return picked
    }

    companion object {
        /** Where the seed lives in the APK. */
        const val ASSET = "seed/steps.json"

        /**
         * The fewest steps a task may have. Two is not a sequence to put in order — «πρώτα» and
         * «τέλος» with nothing between them — so a hand-edited task with fewer is dropped rather than
         * shown as an exercise with no question in it.
         */
        const val MIN_STEPS = 3

        /**
         * The most. Six is what a man rebuilding sequencing can hold, and it is what difficulty 5
         * asks of him with a seventh tile on the board that belongs to another task entirely.
         */
        const val MAX_STEPS = 6

        /**
         * What the phone compares him against when the judge is off or could not be reached: the
         * steps' own words, run through
         * [gr.dimitris.app.core.speech.SpeechMatch.phraseMatches] — so «πρώτα νερό, μετά καφές» is a
         * telling and «θέλω καφέ» is not.
         *
         * Deliberately **not** [StepTask.telling]: the connectors are what the judge is asked about,
         * and a local matcher that demanded them would refuse every telling on a phone with no key.
         * Two thirds of the step words is the same [gr.dimitris.app.core.speech.SpeechMatch.OVERLAP]
         * every other module's phrase check uses.
         */
        fun localTarget(task: StepTask): String = task.joined

        /** Reads the seed off the assets. Never throws: a seed it cannot read is no tasks. */
        suspend fun load(assets: AssetManager): StepTasks = withContext(Dispatchers.IO) {
            runCatching { assets.open(ASSET).bufferedReader().use { parse(it.readText()) } }
                .getOrElse { StepTasks(emptyList()) }
        }

        /**
         * The seed as tasks, with anything unusable left out.
         *
         * Gson fills absent fields with nulls and zeros whatever the Kotlin defaults say, so every
         * field is checked here rather than trusted: a task with no steps, a step with no words, two
         * steps worded the same (the strip is checked by text, so a repeat would be unanswerable), or
         * a distractor that is one of the task's own steps. The file in the APK satisfies all of this
         * — `StepTasksTest` is what keeps it true — and this is what a bad hand edit runs into instead
         * of a screen he cannot finish.
         */
        fun parse(json: String): StepTasks = runCatching {
            val seed = Gson().fromJson(json, StepsSeed::class.java)
            StepTasks(seed?.tasks.orEmpty().mapNotNull(::cleaned).distinctBy { it.id })
        }.getOrElse { StepTasks(emptyList()) }

        private fun cleaned(task: StepTask?): StepTask? {
            if (task == null) return null
            val id = task.id.trim()
            val title = task.title.trim()
            if (id.isEmpty() || title.isEmpty()) return null
            // `orEmpty()` rather than a plain `map`, on a field Kotlin believes cannot be null: Gson
            // writes the field directly and a JSON object with no `steps` at all leaves a null in it,
            // whatever the declared type says.
            val steps = task.steps.orEmpty().mapNotNull(::cleanedStep)
            if (steps.size < MIN_STEPS || steps.size > MAX_STEPS) return null
            if (steps.distinctBy { it.text }.size != steps.size) return null
            val distractor = cleanedStep(task.distractor)?.takeIf { d -> steps.none { it.text == d.text } }
            return task.copy(
                id = id, title = title, difficulty = Difficulty.clamp(task.difficulty),
                steps = steps, distractor = distractor,
            )
        }

        private fun cleanedStep(step: Step?): Step? {
            val text = step?.text?.trim()?.takeIf { it.isNotEmpty() } ?: return null
            return step.copy(text = text, image = step.image?.trim()?.takeIf { it.isNotEmpty() })
        }
    }
}

/** The file itself: a version (for a later bump) and the tasks. */
internal data class StepsSeed(val version: Int = 0, val tasks: List<StepTask>? = null)
