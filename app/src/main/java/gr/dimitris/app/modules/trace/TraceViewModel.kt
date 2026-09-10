package gr.dimitris.app.modules.trace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Adapt
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.scheduler.LevelProgression
import gr.dimitris.app.core.settings.Settings
import gr.dimitris.app.modules.sentences.Sentence
import gr.dimitris.app.modules.sentences.SentenceTemplates
import gr.dimitris.app.modules.sentences.TypedCheck
import gr.dimitris.app.modules.sentences.Variant
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One finished letter or word, as the row will carry it.
 *
 * Free of the ViewModel so that what is written down can be argued with in a unit test rather than
 * on a phone: see `TelemetryTest`. Everything down to `inkRatio` is what phase 6 already wrote, in
 * the order it always had; the three at the end are what `docs/ADAPTATION.md` would move the
 * strictness from — how long the letter took him, how many separate strokes it took, and how big
 * the thing on the paper actually was, without which a distance in pixels means nothing.
 */
internal fun traceDetail(s: TraceState, score: TraceScore?, ms: Long): String = Adapt.detail {
    // His own vocabulary, and it was on these rows before this helper existed.
    kept("text", s.text)
    put("level", s.level)
    put("tries", s.tries)
    // Which hand he was told to use. Nothing else records it, the rows are append-only,
    // and "was this his good hand?" is the first question anyone will ask of them.
    put("hand", s.hand)
    // A word written one letter at a time has no single distance: the numbers on that row are the
    // letters' own, averaged, and the letters themselves are below. Keeping the last letter's mean
    // here would read as "how far off the word he was", which is not what it would be.
    kept("meanDistance", if (s.dictation != null) null else score?.meanDistance?.takeIf { it != Float.MAX_VALUE })
    // The two numbers the marking is actually made of, and the line they were held to:
    // "he passed" a year from now is unreadable without them. A skipped letter has no
    // score, and a null is left out altogether: the three keys are simply absent on those
    // rows rather than present and null.
    kept("coverage", s.dictation?.coverage ?: score?.coverage)
    kept("precision", s.dictation?.precision ?: score?.precision)
    put("strictness", s.strictness)
    // Letter by letter, because that is how it was marked and how it will be read: a
    // word he passes with one weak letter is a letter to practise, not a word.
    //
    // `ink` is how much line he drew on that letter against how long the letter is —
    // the number the per-letter budget is applied to. It is here so the budget can be
    // set from his own hand instead of from synthetic traces: what these rows show is
    // the *margin* a real trace leaves, since a trace that fails the budget is refused
    // and, by design, writes no row at all (a refusal is a nudge and another go, and
    // nothing is recorded until a letter is passed or passed on). If the margins here
    // ever crowd 2.5, the budget is too tight for the hand writing them.
    //
    // A dictated word's letters come from the dictation rather than from one score — each was
    // marked on its own, at its own slot, and each carries the one thing a traced letter cannot
    // say: whether it had to be shown to him before he could write it.
    kept(
        "letters",
        s.dictation?.detail
            ?: score?.letters?.map { mapOf("c" to it.text, "coverage" to it.coverage, "precision" to it.precision, "ink" to it.ink) },
    )
    // The same for the whole word. Kept on every row so the budget that refuses
    // colouring-in can be set from real hands instead of guesses.
    kept("inkRatio", s.dictation?.ink ?: score?.inkRatio?.takeIf { it > 0f })
    put("ms", ms)
    // The strokes of the try that was marked, not of everything still on the paper: a «Δ» drawn in
    // one stroke and a «Δ» drawn in five are different hands, and only this tells them apart. A
    // letter he passed on was never marked, so there is no such try: the count of whatever ink he
    // had left on the paper would read as the shape of a letter nobody looked at, next to a
    // coverage and a precision that are correctly absent.
    put("strokes", s.fresh.size.takeIf { score != null })
    // How tall the letter came out on this phone. Every distance above is in these pixels, so
    // without it none of them can be compared between a tablet and a phone. A row with no paper on
    // it at all — the typed level — has no such height, and says nothing rather than zero.
    put("templateHeightPx", s.templateHeight.takeIf { it > 0f })
    // Everything from here down is phase 13, and every one of them is absent on the levels that
    // existed before it: the rows of a finger tracing a letter are byte-identical to the ones this
    // module has always written, which is what `docs/ADAPTATION.md`'s readers are written against.
    //
    // How the row was asked — spoken and written letter by letter, or typed as a whole sentence.
    // Absent means the finger on the paper, which is what every row before phase 13 was.
    put("variant", s.variant.detail)
    // Whether the word was written with nothing to follow. Only the word level can say yes, and
    // only after he has earned it inside the sitting — see [TraceViewModel.earnedRecall].
    put("fromMemory", true.takeIf { s.recall && !s.templateVisible })
    // How many times he asked to hear the word again. It is not help and it costs nothing: a
    // dictated word he cannot hear is not an exercise. Kept because a word he asked for four times
    // is a word he could not hold, which is a fact about the word and not about his hand.
    put("listened", s.listens.takeIf { it > 0 })
    // The typed level was asked for and «Έλεγχος με Claude» could not answer, so what he actually
    // did was the word level's work. Without this a level-5 row with no `variant` on it would be
    // unreadable: it is the one row that says 5 and was not typed.
    put("noJudge", true.takeIf { s.noJudge })
    // What the judge made of the sentence he typed, in the one shape every module writes it in.
    put("judge", s.judge)
}

/**
 * Which of the three ways «Γράψε» asks him to write, at the level he is on.
 *
 * [detail] is what the attempt row calls it, and [FINGER] deliberately has none: the levels that
 * trace a letter or a word with a finger are every level this module had before phase 13, and their
 * rows stay exactly as they were.
 */
enum class TraceVariant(val detail: String? = null) {
    /** Levels 1–3: the letter or the word is on the paper, and he writes over it with his finger. */
    FINGER,

    /** Level 4, «Υπαγόρευση»: the word is spoken and never shown, and he writes it letter by letter. */
    DICTATION("dictation"),

    /** Level 5, «Γράψε την πρόταση»: a picture, the keyboard, and a whole sentence of his own. */
    TYPED("typed"),
}

/**
 * One thing to write: the word or letter itself, the card it came from where there is one, and how
 * he is being asked for it.
 *
 * [item] is the vocabulary row behind a word, and it is what lets the dictation say the word in a
 * caregiver's own recorded voice instead of in the phone's ([gr.dimitris.app.core.speech.ItemSpeaker.speak]).
 * [sentence] is the typed level's board: the picture he is writing about and the sentence
 * [gr.dimitris.app.modules.sentences.SentenceTemplates] built for it.
 */
data class TraceTarget(
    val text: String,
    val itemId: String? = null,
    val item: Item? = null,
    val sentence: Sentence? = null,
    val variant: TraceVariant = TraceVariant.FINGER,
) {
    /** The word as the dictation level will walk it, letter by letter, or null at every other level. */
    fun dictation(): Dictation? = if (variant == TraceVariant.DICTATION) Dictation(text) else null
}

data class TraceState(
    val level: Int = TraceViewModel.MIN_LEVEL,
    /**
     * The 1..5 he set on the first screen, which in «Γράψε» *is* the level: the five levels were
     * already five difficulties. See [gr.dimitris.app.core.difficulty.Difficulty.trace].
     */
    val difficulty: Int = Difficulty.DEFAULT,
    val hand: String = Settings.HAND_LEFT,
    val index: Int = 0,
    val total: Int = TraceModule.TARGETS_PER_SESSION,
    /** Empty only while the level and the vocabulary are being read; after that, what he is writing. */
    /**
     * What he is writing: the letter, the word, or — at the typed level — the word the sentence he
     * is writing has to be about. Empty only while the level and the vocabulary are being read.
     */
    val text: String = "",
    val itemId: String? = null,
    /** How he is being asked for it: with his finger, from hearing, or on the keyboard. */
    val variant: TraceVariant = TraceVariant.FINGER,
    /**
     * False in the two stretches where the point is that there is nothing to follow: after «Το είδα»
     * on a word he is writing from memory, and at every fresh slot of a dictated word until a miss
     * puts the letter on the paper for him.
     */
    val templateVisible: Boolean = true,
    /**
     * True when this word is asked for from memory: the word level's own progression, earned inside
     * the sitting by writing the word before it without help. Until he has, the word stays on the
     * paper and «Το είδα» is not offered.
     */
    val recall: Boolean = false,
    /** The dictated word, letter by letter, at the dictation level and nowhere else. */
    val dictation: Dictation? = null,
    /** The sentence the typed level asks for, and the picture it is about. Null everywhere else. */
    val sentence: Sentence? = null,
    /** What he has written on the keyboard, as it stands. */
    val typed: String = "",
    /** The judge is reading his sentence right now: «Έτοιμο» is off for exactly as long as that lasts. */
    val checking: Boolean = false,
    /**
     * The whole sentence, left on the screen after a typed answer that did not land — the model's
     * expansion where there was one, the board's own sentence where there was not. Null while there
     * is nothing to copy.
     */
    val whole: String? = null,
    /** The judge's one warm Greek line about what he wrote, when it had one. */
    val feedback: String? = null,
    /** How many times he asked to hear the word again. It costs him nothing; the row keeps it. */
    val listens: Int = 0,
    /**
     * The typed level was asked for and «Έλεγχος με Claude» will not answer, so this sitting is the
     * word level's work instead. Said once, on the screen, because a man who set the hardest dot and
     * was quietly given easier work would have no way of knowing why.
     */
    val noJudge: Boolean = false,
    /** What the judge decided about the sentence just finished, for the attempt row. */
    val judge: Map<String, Any?> = emptyMap(),
    /** The letter or word he is writing, in pieces, letter by letter. See [TraceScorer.score]. */
    val target: Target = Target(emptyList(), emptyList()),
    /** How tall the letter came out, in canvas pixels. Nothing is marked by it any more; it is what
     * says how big the thing he was asked to write actually was, which is the first question of
     * anyone reading these sittings back. */
    val templateHeight: Float = 0f,
    /** How hard he is marked, as a caregiver set it. Read once, when the sitting is loaded. */
    val strictness: TraceStrictness = TraceStrictness.DEFAULT,
    val strokes: List<List<Pt>> = emptyList(),
    /**
     * How many of [strokes] have already been judged. They stay on the paper, faded, so he can see
     * where he went — and they are not marked again: what he writes after a nudge is a new attempt,
     * not an addition to the one that missed. Without this, writing the letter perfectly over a
     * wrong first try is refused, because half the ink on the paper is still the wrong try.
     */
    val judged: Int = 0,
    /** Null until he says he is done, and again the moment he starts writing over a poor try. */
    val score: TraceScore? = null,
    /**
     * True from the moment this letter, word or sentence is finished until «Επόμενο» takes him off it.
     *
     * Its own flag and not `score?.passed`, because the typed level has no score: what the judge
     * accepted is a sentence, not a shape, and the screen needs one answer to "is he done with this
     * one?" whichever of the three ways he was asked.
     */
    val finished: Boolean = false,
    val tries: Int = 0,
    val done: Boolean = false,
    val levelChanged: Int? = null,
    /** Said on the screen when the phone said nothing out loud. */
    val error: String? = null,
) {
    /** The strokes of the try he is on now: everything since the last «Έτοιμο». */
    val fresh: List<List<Pt>> get() = if (judged <= 0) strokes else strokes.drop(judged)

    /** Which letters of the word he has to look at again, by their place in [target]. */
    val failedLetters: Set<Int> get() {
        val marks = score?.letters ?: return emptySet()
        if (score.passed) return emptySet()
        return marks.indices.filter { !marks[it].passed }.toSet()
    }
}

/**
 * One sitting of writing: six letters, words or sentences.
 *
 * There is no wrong here, only "not yet". A trace that misses gets the nudge, the word «Ξανά», and
 * the template back under his strokes, as often as he likes; what the mark costs him is only the
 * difference between CORRECT (first pass) and ASSISTED (with the letter shown again), which is what
 * moves the level. Skipping is evidence too — a level he skips his way through has to be steppable
 * down — and nothing here writes a [gr.dimitris.app.core.scheduler.Scheduler] row: how well he can
 * draw the shape of «ψωμί» says nothing about whether he can find the word tomorrow.
 *
 * Phase 13 put two levels on top of the three that trace a shape, because Dimitris told us in
 * September that the app was too easy and he knows every one of his letters:
 *
 *  * **4, «Υπαγόρευση»** — the word is *said* and never shown. He writes it letter by letter on one
 *    paper, each letter marked on its own against the letter that belongs at that slot; a letter he
 *    misses is put on the paper for him to trace over, and the word goes on. See [Dictation].
 *  * **5, «Γράψε την πρόταση»** — a picture, the keyboard, and a whole sentence of his own about it.
 *    It is the only thing in this module that is not a shape at all, and the only one that cannot be
 *    judged on the phone: [gr.dimitris.app.modules.sentences.TypedCheck] asks Claude, exactly as the
 *    sentence builder's own typed boards do, and with the judge off the level is not offered at all.
 *
 * Writing from memory — level 5 before this phase — did not go: it moved *inside* the word level as
 * that level's own progression. A word he writes with no help earns him the next one with nothing to
 * follow, and a word he needs help with gives the template back. See [TraceState.recall].
 */
class TraceViewModel(
    private val graph: AppGraph,
    private val sessionId: String?,
    /** One target per item the session handed the module; free practice asks for the full six. */
    count: Int = TraceModule.TARGETS_PER_SESSION,
) : ViewModel() {
    private val wanted = count.coerceIn(1, TraceModule.TARGETS_PER_SESSION)

    // Seeded with the count, so the title says "1/3" while it loads instead of flashing "1/6".
    private val _state = MutableStateFlow(TraceState(total = wanted))
    val state: StateFlow<TraceState> = _state.asStateFlow()

    private var targets: List<TraceTarget> = emptyList()
    private var startedAt = now()
    private val results = mutableListOf<Boolean>()

    /** The typed level's boards are built here, out of his own vocabulary, exactly as «Προτάσεις» builds its own. */
    private val templates = SentenceTemplates()

    /**
     * The check for the typed board he is on, or null on a board that is not one. One per board,
     * because [gr.dimitris.app.modules.sentences.TypedCheck.nudged] is what separates a sentence he
     * wrote himself from one he copied off the screen, and that is a fact about this board and no
     * other.
     */
    private var typing: TypedCheck? = null

    /**
     * Whether «Έλεγχος με Claude» answered when the sitting was planned. Read once, because it
     * decides which exercise this sitting *is*: only the judge can read a sentence he typed.
     */
    private var judged = false

    /**
     * True when the next word of the word level is to be asked from memory: the word level's own
     * progression, inside the sitting.
     *
     * Earned by writing a word with no letter shown and no help asked for, and lost by needing
     * either. It is the whole of what phase 11 kept a separate level for — and as a level it was
     * unreachable from the day the dots arrived, because a dot is one level and nobody sets the dot
     * to 5 to write «ψωμί» from memory when the same dot now writes a sentence.
     */
    private var earnedRecall = false

    /** The canvas in pixels, as the screen last measured it. Zero until it has been laid out once. */
    private var boxWidth = 0f
    private var boxHeight = 0f

    /** Screen pixels per dp: what marks him is the size of his fingertip, not of the glyph. */
    private val density = graph.app.resources.displayMetrics.density

    /** The settings and vocabulary read. Cancelled on the way out, so nothing lands on the next screen. */
    private var loadJob: Job? = null

    /**
     * The attempt write of the letter just finished. It runs on the app scope, so the end of the
     * sitting and the back arrow both join it first: the session counts rows, and a row still in
     * flight is not one.
     */
    private var lastWrite: Job? = null

    /** Set the moment the sitting starts winding down, so two taps cannot end it twice. */
    private var ending = false

    /**
     * True from the moment a letter is passed or skipped until the next one starts. It carries both
     * guards: one attempt per letter, and one advance per finished letter.
     */
    private var finishing = false

    /** True while one «Έτοιμο» is being marked off the main thread, so a second tap cannot start another. */
    private var judging = false

    /**
     * The marking in flight. Cancelled by [leave]: a judgement that comes back after «Πίσω» would
     * say «Μπράβο» to an empty screen, speak over the silence the back arrow asked for, and write a
     * row behind the session that has already counted them.
     */
    private var judgeJob: Job? = null

    /**
     * Whatever this screen is saying out loud: the dictated word, the word he has just written, or
     * the sentence he is being shown to copy. Cancelled by [leave] and replaced by the next sound,
     * so «Άκου» pressed twice says the word once more rather than twice over itself.
     */
    private var speakJob: Job? = null

    init { load() }

    /** He moved the dots. «Γράψε» starts again on what the new level asks him to write. */
    fun reload() {
        if (ending || finishing || judging) return
        loadJob?.cancel()
        judgeJob?.cancel()
        graph.voice.quiet()
        load()
    }

    private fun load() {
        loadJob = viewModelScope.launch {
            val stored = runCatching { graph.settings.traceLevel.first() }
                .getOrElse { graph.errors.record("trace level read", it); MIN_LEVEL }
            val difficulty = runCatching { graph.settings.difficulty(ModuleId.TRACE).first() }
                .getOrElse { graph.errors.record("trace difficulty read", it); Difficulty.DEFAULT }
            // In «Γράψε» the dot *is* the level, and the store keeps them equal from both directions
            // ([gr.dimitris.app.core.settings.Settings.setTraceLevel]). This is the same clamp the
            // other two levelled modules do at load: what he writes is what the row he is looking at
            // says he writes — and, like them, the clamp is written back.
            //
            // Writing back is not decoration here. `finishSitting` cannot heal a disagreement, because
            // a band of one level makes `newLevel == level` and nothing is saved; so a `trace_level`
            // that had drifted from `difficulty_TRACE` — reachable when a caregiver's bound refuses
            // the derived dot, since the migration deliberately moves no level — would have left him
            // writing at one level while the store and her stepper showed another, for ever.
            val level = Difficulty.levelAtLoad(stored, Difficulty.trace(difficulty))
            if (level != stored) {
                runCatching { graph.settings.setTraceLevel(level) }
                    .onFailure { graph.errors.record("trace level clamp", it) }
            }
            val hand = runCatching { graph.settings.traceHand.first() }
                .getOrElse { graph.errors.record("trace hand read", it); Settings.HAND_LEFT }
            val strictness = runCatching { graph.settings.traceStrictness.first() }
                .getOrElse { graph.errors.record("trace strictness read", it); TraceStrictness.DEFAULT }
            val words = if (level >= WORD_LEVEL) {
                runCatching { graph.db.items().activeOfKinds(listOf(ItemKind.WORD)) }
                    .getOrElse { graph.errors.record("trace words", it); emptyList() }
            } else emptyList()
            // The judge decides which exercise this sitting *is*, so it is asked here, once, before
            // a single board is built — never per target. `available()` opens the encrypted key file.
            judged = if (level == TYPED_LEVEL) {
                runCatching { graph.judge.available() }
                    .onFailure { graph.errors.record(TypedCheck.WHERE, it) }
                    .getOrDefault(false)
            } else false
            // A new screen is a new run: the judge writes one row per failure class per run, and a
            // sitting on an offline phone must not fill «Σφάλματα» with one row per sentence.
            if (judged) graph.judge.newRun()
            val typed = if (judged) sentences(words, difficulty) else emptyList()
            // Nothing to type: either the judge will not answer, or his vocabulary cannot fill the
            // shape. Both fall back to the word level's own work — six words to write with a finger
            // is a writing exercise, and an empty screen is not — and only the first of them is
            // something he is told about, because only the first is something a caregiver can fix.
            targets = when {
                level != TYPED_LEVEL -> plan(level, words, difficulty)
                typed.isNotEmpty() -> typed
                else -> plan(WORD_LEVEL, words, difficulty)
            }
            // Not before the settings read: their wait is not his writing time.
            startedAt = now()
            val first = targets.first()
            results.clear()
            earnedRecall = false
            typing = typedCheck(first)
            _state.value = TraceState(
                level = level, difficulty = difficulty, hand = hand, strictness = strictness,
                total = targets.size, text = first.text, itemId = first.itemId,
                variant = first.variant, sentence = first.sentence, dictation = first.dictation(),
                // A dictated word starts on an empty slot: there is nothing to follow, which is the
                // exercise. Everywhere else the thing he is writing is on the paper from the start.
                templateVisible = first.variant != TraceVariant.DICTATION,
                noJudge = level == TYPED_LEVEL && !judged,
            )
            // The canvas is usually laid out before this read comes back, so the template it asked
            // for has to be built now that there is finally something to build it from.
            rebuildTemplate()
            // The one thing a dictation cannot wait for a tap to do: say the word.
            say(first)
        }
    }

    /**
     * The six things he will write, all at one level. Levels 1 and 2 are single letters, level 3 is
     * the shortest words on the device — the shortest, because a word he can finish is worth more
     * than a long one he abandons halfway — and level 4 is the same words, said rather than shown.
     *
     * A device with no words at all falls back to his own name rather than to an empty screen:
     * «Γράψε» is the one module that always has something to offer, since the alphabet is not
     * content anyone can delete, and his name is not either.
     */
    private fun plan(level: Int, words: List<Item>, difficulty: Int): List<TraceTarget> = when (level) {
        1 -> CAPITALS.shuffled().take(wanted).map { TraceTarget(it) }
        2 -> SMALL.shuffled().take(wanted).map { TraceTarget(it) }
        DICTATION_LEVEL -> dictated(words, difficulty)
        else -> traced(words, difficulty)
    }

    /**
     * The words of the word level: twice as many short words as the sitting needs, then shuffled.
     *
     * The six shortest words on the device, in the same order, every sitting for the rest of his life
     * is not practice, it is a rut — and the pool is still short words, which is the point.
     */
    private fun traced(words: List<Item>, difficulty: Int): List<TraceTarget> {
        val shortest = shortest(pool(words, difficulty))
        if (shortest.isEmpty()) return name(TraceVariant.FINGER)
        return List(wanted) { i ->
            val word = shortest[i % shortest.size]
            // The row belongs to the word itself: at this level and the next, the word *is* the
            // exercise, and «ψωμί» is a card the caregiver's screens can look up.
            TraceTarget(word.text, word.id, word)
        }
    }

    /**
     * The words of the dictation level, in the order he will meet them: shortest first.
     *
     * Shortest first in the sitting as well as in the pool, which is the one place this differs from
     * [traced]. Writing a word from hearing, letter by letter, with nothing on the paper is the
     * hardest thing this module asks; a sitting that opens with «λογαριασμός» is a sitting he stops
     * doing, and one that opens with «ψωμί» is one he finishes.
     *
     * One word and never a phrase: a space is nothing he can write on the paper, and a two-word card
     * dictated letter by letter is a dozen slots on one sheet of paper.
     */
    private fun dictated(words: List<Item>, difficulty: Int): List<TraceTarget> {
        val shortest = shortest(pool(words, difficulty).filter { it.text.none(Char::isWhitespace) })
            .sortedBy { it.text.length }
        if (shortest.isEmpty()) return name(TraceVariant.DICTATION)
        return List(wanted) { i ->
            val word = shortest[i % shortest.size]
            TraceTarget(word.text, word.id, word, variant = TraceVariant.DICTATION)
        }
    }

    /**
     * The typed level's boards: one sentence per target, built from his own vocabulary at
     * [SentenceTemplates.ARTICLE_LEVEL] — the articles shape, «ο μπαμπάς πίνει τον καφέ», which is
     * the one whose object is a card he has a picture of and so the one a picture can ask for.
     *
     * Comes back short, or empty, rather than looping: a vocabulary that cannot fill the shape — no
     * named person, no readable gender — is one the caller falls back off, exactly as «Προτάσεις»
     * drops a level and asks again.
     */
    private fun sentences(words: List<Item>, difficulty: Int): List<TraceTarget> {
        val pool = pool(words, difficulty)
        val out = mutableListOf<TraceTarget>()
        var tries = 0
        while (out.size < wanted && tries < wanted * TRIES_PER_SENTENCE) {
            tries++
            val sentence = templates.generate(SentenceTemplates.ARTICLE_LEVEL, pool, Variant.TYPED) ?: continue
            if (sentence.variant != Variant.TYPED) continue
            val word = sentence.picture?.item ?: continue
            // The row is about the level and not about the word: the picture is what the sentence is
            // *about*, not a card he is practising the writing of.
            out += TraceTarget(word.text, itemId = null, item = word, sentence = sentence, variant = TraceVariant.TYPED)
        }
        return out
    }

    /**
     * His own vocabulary, as hard as this dot asks for. A word nobody has graded is tier 1 and is in
     * reach from every dot — see [Difficulty.admitsTier] — so a phone whose words predate the tiers
     * offers exactly what it always did.
     */
    private fun pool(words: List<Item>, difficulty: Int): List<Item> =
        words.filter { it.text.isNotBlank() && Difficulty.admitsTier(it.tier, difficulty) }

    /** The short end of the pool, shuffled: twice what the sitting needs, so it is never the same six. */
    private fun shortest(pool: List<Item>): List<Item> = pool
        .sortedWith(compareBy({ it.text.length }, { it.text }))
        .take(wanted * SHORT_POOL)
        .shuffled()

    /** The fallback for a device with no words: his own name, which nobody can delete. */
    private fun name(variant: TraceVariant): List<TraceTarget> =
        List(wanted) { i -> TraceTarget(NAME[i % NAME.size], variant = variant) }

    /**
     * The check for one typed board, or null on a board that is not one. The judge is asked through
     * the same [TypedCheck] the sentence builder's typed boards use, because it is the same exercise
     * asked of the same man — what differs is only what the board shows him first.
     */
    private fun typedCheck(target: TraceTarget): TypedCheck? =
        if (target.variant != TraceVariant.TYPED) null
        else TypedCheck(
            judged = { judged },
            difficulty = { _state.value.difficulty },
            askJudge = { ask -> graph.judge.judge(ask) },
            record = { where, e -> graph.errors.record(where, e) },
        )

    /**
     * The canvas has been measured. The template is rebuilt only when the box really changed —
     * layout reports the same size on every recomposition, and rebuilding an outline under his
     * finger would move the letter he is halfway through tracing.
     */
    fun setCanvasSize(width: Float, height: Float) {
        if (width <= 0f || height <= 0f) return
        if (width == boxWidth && height == boxHeight) return
        // The paper does change size within one letter — a line of «Ξανά» above it, or level 5's
        // «Το είδα» below — and what he has already drawn has to move with it. Otherwise the ink
        // stays where his finger was and the letter is rebuilt somewhere else, and the screen shows
        // him a miss he did not make.
        val wasSized = boxWidth > 0f && boxHeight > 0f
        val scaleX = if (wasSized) width / boxWidth else 1f
        val scaleY = if (wasSized) height / boxHeight else 1f
        boxWidth = width
        boxHeight = height
        if (scaleX != 1f || scaleY != 1f) {
            _state.update { s ->
                s.copy(strokes = s.strokes.map { stroke -> stroke.map { Pt(it.x * scaleX, it.y * scaleY) } })
            }
        }
        rebuildTemplate()
    }

    /**
     * The glyph on the paper, rebuilt for whatever the paper is showing now.
     *
     * At the dictation level that is the **one letter** the slot he is on wants — built whether or
     * not it is being drawn, because the scorer marks his strokes against it either way, and the
     * reveal after a miss only decides whether he can see it. The typed level has no paper at all.
     */
    private fun rebuildTemplate() {
        val s = _state.value
        val text = when (s.variant) {
            TraceVariant.DICTATION -> s.dictation?.expected.orEmpty()
            TraceVariant.TYPED -> ""
            TraceVariant.FINGER -> s.text
        }
        if (text.isEmpty() || boxWidth <= 0f || boxHeight <= 0f) return
        val glyph = runCatching { Glyphs.template(text, boxWidth, boxHeight) }
            .getOrElse { graph.errors.record("trace template", it); GlyphTemplate(Target(emptyList(), emptyList()), 0f) }
        _state.update { it.copy(target = glyph.target, templateHeight = glyph.height) }
    }

    /** One finished stroke. It answers the nudge as well: «Ξανά» goes when he starts writing again. */
    fun addStroke(points: List<Pt>) {
        if (finishing || ending || points.isEmpty()) return
        _state.update { it.copy(strokes = it.strokes + listOf(points), score = null) }
    }

    /**
     * «Καθάρισε»: the strokes go and the nudge with them, and the letter stays. It is the only way
     * back from a poor try — the brief calls that `retry`, but wiping the paper is the same act
     * whether he does it because he missed or because he changed his mind, so it is one function.
     */
    fun clear() {
        if (finishing || ending) return
        // Everything, the faded ink of the earlier tries included: «Καθάρισε» means a clean page.
        _state.update { it.copy(strokes = emptyList(), judged = 0, score = null) }
    }

    /**
     * «Το είδα», on a word he has earned from memory: the word goes away and he writes it himself.
     *
     * The paper goes with it. Otherwise the cheapest way through a recall word is to trace it while
     * it is still on the screen, press «Το είδα», and hand in a fully traced word to be marked under
     * the kinder recall line — easier than tracing it for doing less, and the recall exercise never
     * happens. Taking the word away has to mean taking it away.
     */
    fun hide() {
        if (finishing || ending) return
        if (!_state.value.recall) return
        _state.update { it.copy(templateVisible = false, strokes = emptyList(), judged = 0, score = null) }
    }

    /**
     * True once he is writing from memory: a recall word, with the word hidden by «Το είδα». It is
     * the hidden template and not the level that makes the exercise the harder one, so it is also
     * what earns the kinder marking — otherwise the cheapest way through a recall word would be to
     * ignore «Το είδα», trace the word that is still on the screen, and be marked more gently than
     * the word before it for doing less.
     */
    private fun writingFromMemory(s: TraceState): Boolean = s.recall && !s.templateVisible

    /**
     * «Έτοιμο». A pass is said out loud and written down; a miss is a nudge, the template back under
     * his strokes, and another go — never a fail state, and never a letter that cannot be finished.
     */
    fun check() {
        val s = _state.value
        if (finishing || ending || judging || s.text.isEmpty()) return
        // An empty canvas is not a poor attempt: it would nudge him, spend his first try, and on a
        // recall word give away the word he was about to write. The button is disabled too. What
        // counts as empty is the try he is on: the faded ink of the one that missed is not an answer.
        val fresh = s.fresh
        if (fresh.isEmpty()) return
        // A recall word is not begun until he has taken the word away.
        if (s.recall && s.templateVisible) return
        // The typed level has no paper: its «Έτοιμο» is [submitTyped].
        if (s.variant == TraceVariant.TYPED) return
        if (s.variant == TraceVariant.DICTATION) { checkLetter(s, fresh); return }

        val fromMemory = writingFromMemory(s)
        judging = true
        // The letters he is writing, kept for the nudge: the state may have moved on by the time
        // the marking comes back.
        // Off the main thread: it is one distance per point of his against every point of the
        // letter, which on a whole word is millions of them, and the one button he presses must not
        // be the one that stutters. Nothing else touches the state until the answer comes back —
        // `judging` holds the door — and the answer is applied on the main thread as usual.
        judgeJob = viewModelScope.launch {
            // In fingertips, and never wider than a piece of the letter: a tolerance of a tenth of
            // the height was half the paper on a capital, and a fixed 12 dp is a quarter of a letter
            // in a word of eight. That is how a «Κ» drawn over an «Η» came to pass, at both scales.
            // Every stroke is judged on its own, so lifting his finger is never counted as a line.
            val score = withContext(Dispatchers.Default) {
                TraceScorer.score(
                    strokes = fresh,
                    target = s.target,
                    level = s.strictness,
                    density = density,
                    recall = fromMemory,
                )
            }
            judging = false
            // He wiped the paper, or left, while it was being marked: that answer is about ink that
            // is no longer there.
            if (ending || finishing || _state.value.strokes.size != s.strokes.size) return@launch
            if (score.passed) {
                graph.feedback.success()
                finishing = true
                // The letter comes back under his own writing, so he can see what he made of it — on a
                // recall word that is the answer to what he was remembering, and he has earned the look.
                _state.update { it.copy(score = score, templateVisible = true, finished = true) }
                // What he has just written, said: the point of writing it is that it is a word.
                launch { report(graph.speaker.speakText(s.text)) }
                record(s, score, firstTry = s.tries == 0)
            } else {
                graph.feedback.nudge()
                // His strokes stay where they are and the letter comes back over them, so he can see
                // where he went — faded, and out of the marking, so what he writes next is judged on
                // its own. At level 5 that is also the answer to what he was trying to remember. The
                // letters that missed are named and marked on the paper: «Ξανά» over a word of eight
                // letters is not an instruction anybody can follow.
                _state.update {
                    it.copy(score = score, tries = it.tries + 1, templateVisible = true, judged = it.strokes.size)
                }
            }
        }
    }

    /**
     * «Έτοιμο» at the dictation level: **one letter** of the word he heard, marked against the letter
     * that belongs at the slot he is on.
     *
     * The same scorer, the same strictness and the same two lines a traced letter is held to — the
     * only difference is what he had to go on. An empty slot is writing from memory, so it is marked
     * under [Strictness.RECALL_ALLOWANCE]; a letter that has been revealed to him after a miss is
     * there to be traced, and is marked as a trace.
     *
     * A miss is not a wrong answer and not the end of the word: the letter is put on the paper, his
     * own ink fades out of the marking (so the next try is judged on its own, exactly as it is at
     * every other level), and he traces it and goes on. What it costs is the word's mark.
     */
    private fun checkLetter(s: TraceState, fresh: List<List<Pt>>) {
        val dictation = s.dictation ?: return
        if (dictation.expected == null) return
        // Whether the letter was on the paper for him, read *now*: it is what the letter's own row
        // will say about it, and the state may have moved on by the time the marking comes back.
        val shown = s.templateVisible
        judging = true
        judgeJob = viewModelScope.launch {
            val score = withContext(Dispatchers.Default) {
                TraceScorer.score(
                    strokes = fresh, target = s.target, level = s.strictness,
                    density = density, recall = !shown,
                )
            }
            judging = false
            // He wiped the paper, or left, while it was being marked.
            if (ending || finishing || _state.value.strokes.size != s.strokes.size) return@launch
            val written = dictation.judged(score, shown = shown)
            if (written == null) {
                graph.feedback.nudge()
                // The letter, revealed. From here the word is assisted work whatever else he does
                // with it — which is recorded on the letter itself, when he passes it.
                _state.update {
                    it.copy(score = score, tries = it.tries + 1, templateVisible = true, judged = it.strokes.size)
                }
                return@launch
            }
            if (!written.done) {
                graph.feedback.success()
                // The next slot, empty, on a clean sheet: the letters he has got are in the row above
                // it, and nothing of the letter just finished is left to be marked again.
                _state.update {
                    it.copy(
                        dictation = written, templateVisible = false, score = null,
                        strokes = emptyList(), judged = 0,
                    )
                }
                rebuildTemplate()
                return@launch
            }
            graph.feedback.success()
            finishing = true
            // The word is finished: the last letter stays on the paper under his own writing, and the
            // word he has just written is said — the point of writing it is that it is a word.
            val done = s.copy(dictation = written)
            _state.update { it.copy(dictation = written, score = score, templateVisible = true, finished = true) }
            say(text = s.text, item = targets.getOrNull(s.index)?.item)
            record(done, score, firstTry = !written.helped)
        }
    }

    /** One keystroke on the typed level. Nothing is judged until he says he is done. */
    fun onTypedChange(text: String) {
        if (finishing || ending) return
        if (_state.value.variant != TraceVariant.TYPED) return
        _state.update { it.copy(typed = text) }
    }

    /**
     * «Έτοιμο» at the typed level: the sentence he wrote, read by the judge.
     *
     * Accepted is his own sentence — CORRECT the first time, ASSISTED once the whole form has been on
     * the screen. Refused is never a wall: the whole form is shown and said, the keyboard stays, and
     * he may write it again as often as he likes or press «Το έγραψα» when he has copied it. It is
     * the sentence builder's own typed board, to the letter, because it is the same exercise.
     */
    fun submitTyped() {
        val s = _state.value
        val sentence = s.sentence ?: return
        val check = typing ?: return
        if (finishing || ending || s.checking) return
        if (s.variant != TraceVariant.TYPED) return
        val said = s.typed.trim()
        if (said.isEmpty()) return
        _state.update { it.copy(checking = true) }
        viewModelScope.launch {
            val written = try {
                // The word under the picture is the prompt, and [WRITE_A_SENTENCE] is the intent: what
                // a good answer has to *do*. Without the intent the model is told to accept only a
                // meaning that agrees with the target, and «ο μπαμπάς πίνει τον καφέ» is not the only
                // correct Greek sentence about a cup of coffee — being marked down for writing one of
                // the others is a correction he could not have avoided.
                check.weigh(said, prompt = s.text, target = sentence.text, intent = WRITE_A_SENTENCE)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                // Nothing in [TypedCheck] throws; if anything ever does, the keyboard comes back
                // rather than the board dying under it.
                graph.errors.record(TypedCheck.WHERE, e)
                _state.update { it.copy(checking = false) }
                return@launch
            }
            if (ending) return@launch
            if (written.accepted) {
                graph.feedback.success()
                finishing = true
                _state.update {
                    it.copy(
                        checking = false, finished = true, whole = null,
                        feedback = written.feedback, judge = written.judge,
                    )
                }
                // **His** sentence, said — not the one the app had in mind. With an intent the judge
                // accepts any correct sentence about the word, so the target is one of many and
                // reading it out over a different correct sentence of his would be a correction he
                // had not earned. The point of writing it is that it is a sentence.
                say(text = said, item = null)
                record(_state.value, score = null, firstTry = written.firstTry)
            } else {
                graph.feedback.nudge()
                _state.update {
                    it.copy(
                        checking = false, tries = it.tries + 1, whole = written.whole,
                        feedback = written.feedback, judge = written.judge,
                    )
                }
                // The whole sentence, said as well as shown: «Σωστά: …», the same words the sentence
                // builder answers with, because it is the same gesture.
                written.whole?.let { whole -> say(text = "$CORRECTION $whole", item = null) }
            }
        }
    }

    /**
     * «Το έγραψα»: he has copied the sentence off the screen and says so.
     *
     * Assisted work, on the same footing as a letter he traced after the nudge — the form was in front
     * of him — and the only way off a typed board that is not another go at the keyboard or
     * «Παράλειψη».
     */
    fun confirmTyped() {
        val s = _state.value
        if (finishing || ending || s.checking) return
        if (s.variant != TraceVariant.TYPED || s.whole == null) return
        graph.feedback.success()
        finishing = true
        _state.update { it.copy(finished = true) }
        record(s, score = null, firstTry = false)
    }

    /**
     * «Άκου»: the word again.
     *
     * At the dictation level it is the exercise itself — the word is never written down, so a man who
     * cannot hear it has nothing to write — and at the typed level it is the word the sentence has to
     * be about, which is on the screen under the picture as well. Neither costs him the mark: the row
     * counts the presses ([TraceState.listens]) because a word he asked for four times is a word he
     * could not hold, and that is a fact about the word.
     *
     * It never says the sentence he is being asked to produce. That would be the answer, and the one
     * place this module hands the answer over is a refusal, where it has been earned.
     */
    fun listen() {
        val s = _state.value
        if (ending || s.text.isEmpty()) return
        if (s.variant == TraceVariant.FINGER) return
        _state.update { it.copy(listens = it.listens + 1) }
        say(text = s.text, item = targets.getOrNull(s.index)?.item)
    }

    /**
     * The dictated word, said the moment the slot opens: at that level the word is only ever sound,
     * and nothing else on the screen says what he is writing. Every other level shows him what it
     * wants and says nothing.
     */
    private fun say(target: TraceTarget) {
        if (target.variant != TraceVariant.DICTATION) return
        say(text = target.text, item = target.item)
    }

    /**
     * One utterance of this screen, and only one: a new sound replaces whatever was sounding, because
     * two voices over each other is worse than either. [item] is the card where there is one, so a
     * caregiver's own recording of «ψωμί» is what he hears instead of the phone's voice.
     */
    private fun say(text: String, item: Item?) {
        speakJob?.cancel()
        graph.voice.quiet()
        speakJob = viewModelScope.launch {
            report(if (item != null) graph.speaker.speak(item) else graph.speaker.speakText(text))
        }
    }

    fun skip() {
        val s = _state.value
        // One skip per letter: the button is still there for a frame, and a second tap would pass on
        // the letter that has not been shown yet.
        if (finishing || ending || s.text.isEmpty()) return
        finishing = true
        graph.feedback.nudge()
        // No numbers on a skipped row: the last failed try's mean and coverage belong to a trace he
        // has since wiped, and reading them back later as "how he did on this word" would be a lie.
        //
        // A dictated word he passes on is the one exception, and it is not one: the letters already on
        // the row are letters he *finished* and had accepted, each with its own marks. «Three letters
        // of «ψωμί», then he gave up» is exactly what a skipped dictation should read as.
        record(s, null, firstTry = false, skipped = true)
        advance()
    }

    fun next() {
        // Only a finished letter moves on. A second tap on «Επόμενο» — the button is still there for
        // a frame after the first — would otherwise skip the letter that just arrived, and leave the
        // session counting one he was never shown.
        if (!finishing) return
        advance()
    }

    private fun advance() {
        val s = _state.value
        // Nothing to advance past: still loading, or the sitting is already over.
        if (s.text.isEmpty()) return
        finishing = false
        val i = s.index + 1
        if (i >= targets.size) { finishSitting(); return }
        val target = targets[i]
        startedAt = now()
        typing = typedCheck(target)
        // Whatever was being said belonged to the word he has just finished: he taps the green
        // «Επόμενο» the instant the tick appears, and the word is still being read out. A dictated
        // word that opened under it would be the one word on the screen he never heard.
        speakJob?.cancel()
        graph.voice.quiet()
        _state.update {
            it.copy(
                index = i, text = target.text, itemId = target.itemId, variant = target.variant,
                sentence = target.sentence, dictation = target.dictation(),
                // The word level's own progression: a word he wrote without help is a word he has
                // earned the right to write from memory. Everywhere else the thing to write is there.
                recall = target.variant == TraceVariant.FINGER && earnedRecall,
                templateVisible = target.variant != TraceVariant.DICTATION,
                target = Target(emptyList(), emptyList()), templateHeight = 0f,
                strokes = emptyList(), judged = 0, score = null, finished = false, tries = 0,
                typed = "", checking = false, whole = null, feedback = null, listens = 0,
                judge = emptyMap(),
            )
        }
        rebuildTemplate()
        say(target)
    }

    private fun finishSitting() {
        if (ending) return
        ending = true
        // The session counts attempt rows as soon as it is told the module is done, so the last
        // letter's write has to be in the database before "done" ever reaches the screen.
        val write = lastWrite
        viewModelScope.launch {
            write?.join()
            val level = _state.value.level
            // Inside the band the dots ask for, which in «Γράψε» is the one level they name: from
            // phase 12 on, what he writes is his own choice and a good morning no longer moves him
            // off it. See [gr.dimitris.app.core.difficulty.Difficulty.trace].
            val band = Difficulty.trace(_state.value.difficulty)
            val newLevel = LevelProgression.next(level, results, band.first, band.last)
            // A settings write that fails must not strand him on a screen that never says "done".
            val moved = newLevel != level && runCatching { graph.settings.setTraceLevel(newLevel) }
                .onFailure { graph.errors.record("trace level write", it) }.isSuccess
            _state.update { it.copy(done = true, levelChanged = newLevel.takeIf { moved }) }
        }
    }

    /**
     * The user pressed back. Whatever was being said stops here, and [then] waits for the last
     * letter's write: the session counts rows the moment it is told, so leaving before the row lands
     * would lose the letter he had just written.
     */
    fun leave(then: () -> Unit) {
        // Nothing more is to happen on this screen: the guard inside the marking reads this too, so
        // a judgement that is already past its cancellation point still throws its answer away
        // rather than saying «Μπράβο» and writing a row after he has gone.
        ending = true
        judgeJob?.cancel()
        loadJob?.cancel()
        speakJob?.cancel()
        graph.voice.quiet()
        val write = lastWrite
        viewModelScope.launch { write?.join(); then() }
    }

    /**
     * Records the outcome of one spoken word. Silence is the one failure Dimitris cannot diagnose
     * himself, so it is said on the screen and cleared by the next sound that comes out.
     */
    private fun report(result: Result<*>) = result.fold(
        onSuccess = { _state.update { if (it.error == SPEECH_FAILED) it.copy(error = null) else it } },
        onFailure = { e -> graph.errors.record("trace speak", e); _state.update { it.copy(error = SPEECH_FAILED) } },
    )

    private fun record(s: TraceState, score: TraceScore?, firstTry: Boolean, skipped: Boolean = false) {
        val outcome = when { skipped -> Outcome.SKIPPED; firstTry -> Outcome.CORRECT; else -> Outcome.ASSISTED }
        // Every finished letter is evidence, a skip included: passing on a letter is not neutral, it
        // is one he could not write.
        results += (outcome == Outcome.CORRECT)
        // The word level's own progression, decided by the word he has just finished: written with no
        // help at all, and the next one comes with nothing to follow; helped or passed on, and the
        // template comes back. A single letter is never asked for from memory — the letter *is* the
        // prompt, so there would be nothing on the screen to write at all.
        if (s.variant == TraceVariant.FINGER && s.text.length > 1) earnedRecall = outcome == Outcome.CORRECT
        // Read eagerly: the clock is restarted the moment the next letter arrives.
        val began = startedAt
        val detail = traceDetail(s, score, ms = now() - began)
        // The word's own row only where the word was the exercise; everywhere else the level is what
        // the row is about, because a random capital is not an item anything can look up.
        val itemId = s.itemId ?: "$ITEM_PREFIX${s.level}"
        // Chained, because the app scope runs on a pool with no ordering: whoever joins the last
        // write must be joining every write, or a row can land after the session has counted.
        val prev = lastWrite
        // The app scope, not this screen's: pressing back must not lose the letter he just wrote.
        lastWrite = graph.scope.launch {
            prev?.join()
            runCatching {
                graph.db.attempts().insert(
                    Attempt(
                        itemId = itemId, module = ModuleId.TRACE, sessionId = sessionId,
                        startedAt = began, durationMs = now() - began, outcome = outcome, cueLevel = null, detail = detail,
                    )
                )
            }.onFailure { graph.errors.record("trace record", it) }
        }
    }

    companion object {
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 5

        /**
         * From here the letters come from his own vocabulary instead of the alphabet, and this is the
         * level the two above it fall back to: six words to write with a finger is always a writing
         * exercise. Recall — the word shown once and taken away — lives inside it, as that level's own
         * progression rather than as a level of its own. See [TraceState.recall].
         */
        const val WORD_LEVEL = 3

        /** «Υπαγόρευση»: the word is said and never shown, and written letter by letter. See [Dictation]. */
        const val DICTATION_LEVEL = 4

        /** «Γράψε την πρόταση»: a picture, the keyboard, and a whole sentence of his own about it. */
        const val TYPED_LEVEL = 5

        /** How deep into the short words the word levels draw before shuffling: six of twelve. */
        const val SHORT_POOL = 2

        /** Enough goes at one sentence shape to fill a sitting, few enough that a thin pool gives up. */
        const val TRIES_PER_SENTENCE = 4

        /**
         * What a good answer at the typed level has to *do*, as the judge is told it
         * ([gr.dimitris.app.core.judge.Ask.intent]).
         *
         * Without it the board is unanswerable except by guessing which sentence the app had in mind:
         * the SENTENCE contract tells the model to accept only a meaning that agrees with the target,
         * and «ο μπαμπάς πίνει τον καφέ» is one correct Greek sentence about a cup of coffee out of
         * dozens. A man with agrammatism who produces «πίνω τον καφέ το πρωί» has done the exercise,
         * and being answered with «Σχεδόν.» for it would teach him nothing — the same principle as the
         * level-4 distractor rule in «Προτάσεις», one level of the sentence up.
         */
        const val WRITE_A_SENTENCE = "γράφει μια σωστή πρόταση με τη λέξη"

        /** The line above the picture at the typed level: the one instruction in «Γράψε» that asks for a keyboard. */
        const val WRITE_IT = "Γράψε την πρόταση για:"

        /** He has copied the sentence off the screen. Assisted work, and the way on. */
        const val I_WROTE_IT = "Το έγραψα"

        /** What comes before the sentence he is being shown to copy, on the screen and out loud. */
        const val CORRECTION = "Σωστά:"

        /**
         * Said once, on the first screen, when the typed level was asked for and «Έλεγχος με Claude»
         * is off or has no key: this sitting is the word level's work instead.
         *
         * He is told rather than quietly given easier work, because the dot he set is the hardest one
         * and nothing else on the screen would explain why it looks like the one below it. It names
         * what has to change and who can change it, which is the only kind of message worth showing a
         * man who cannot ask.
         */
        const val NEEDS_JUDGE = "Χρειάζεται τον έλεγχο με Claude."

        /** The empty slot of a dictated word: where the letter he is writing will go. */
        const val SLOT = "_"

        /** What a poor trace says on the screen. Never "λάθος": there is nothing to fail here. */
        const val TRY_AGAIN = "Ξανά"

        /**
         * The two halves of the ink refusal, kept apart so the letter can be named between them:
         * «Πολύ μελάνι στο «η». Ξανά, πιο απλά.»
         */
        private const val TOO_MUCH_INK_HEAD = "Πολύ μελάνι"
        private const val TOO_MUCH_INK_TAIL = "Ξανά, πιο απλά."

        /**
         * What too much ink says instead. Colouring the letter in touches every piece of it without
         * ever writing it, so it is refused — and "do less" is different advice from "look at the
         * shape", so it is a different sentence.
         */
        const val TOO_MUCH_INK = "$TOO_MUCH_INK_HEAD. $TOO_MUCH_INK_TAIL"

        /**
         * The nudge, and where to look. «Ξανά» over a word of eight letters says nothing a man with
         * aphasia can act on; the letter that missed is named, in the shape it is written on the
         * screen, and the paper marks it too.
         *
         * One letter is named, two are named, and more than two are not: a list of five letters is
         * a page of text, and the marked letters on the paper say it better. The ink refusal names
         * its letters the same way: the budget is per letter, so «Πολύ μελάνι» can say *which*
         * letter was drowned exactly as «Ξανά» says which was the wrong shape.
         */
        fun tryAgain(score: TraceScore?): String {
            // A single letter he was asked for: there is nothing to point at but the letter itself.
            val named = if (score?.letters.orEmpty().size < 2) emptyList() else score?.failed.orEmpty()
            if (score?.tooMuchInk == true) return when (named.size) {
                1 -> "$TOO_MUCH_INK_HEAD στο «${named[0].text}». $TOO_MUCH_INK_TAIL"
                2 -> "$TOO_MUCH_INK_HEAD στο «${named[0].text}» και στο «${named[1].text}». $TOO_MUCH_INK_TAIL"
                else -> TOO_MUCH_INK
            }
            if (named.isEmpty()) return TRY_AGAIN
            return when (named.size) {
                1 -> "$TRY_AGAIN — δες το «${named[0].text}»."
                2 -> "$TRY_AGAIN — δες το «${named[0].text}» και το «${named[1].text}»."
                else -> "$TRY_AGAIN — δες τα γράμματα."
            }
        }

        /** Said on the screen when the letter he passed made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

        /** Attempt rows that belong to a level rather than to a word on the device. */
        const val ITEM_PREFIX = "trace:level:"

        /**
         * What the word levels fall back on when the device has no vocabulary at all: his own name,
         * twice as it is written and once in capitals. It was level 3 itself until phase 13 moved the
         * words down into that level, and it is still the one word nobody can delete.
         */
        val NAME = listOf("Δημήτρης", "Δημήτρης", "ΔΗΜΗΤΡΗΣ")

        val CAPITALS = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ".map { it.toString() }
        val SMALL = "αβγδεζηθικλμνξοπρστυφχψω".map { it.toString() }
    }
}
