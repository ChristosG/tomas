package gr.dimitris.app.modules.trace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.scheduler.LevelProgression
import gr.dimitris.app.core.settings.Settings
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** One thing to write, and the card it came from when it came from one (levels 4 only). */
data class TraceTarget(val text: String, val itemId: String? = null)

data class TraceState(
    val level: Int = TraceViewModel.MIN_LEVEL,
    val hand: String = Settings.HAND_LEFT,
    val index: Int = 0,
    val total: Int = TraceModule.TARGETS_PER_SESSION,
    /** Empty only while the level and the vocabulary are being read; after that, what he is writing. */
    val text: String = "",
    val itemId: String? = null,
    /** False only at level 5, after «Το είδα»: from there he is writing it from memory. */
    val templateVisible: Boolean = true,
    val template: List<Pt> = emptyList(),
    val templateHeight: Float = 0f,
    /** The ink of the letter: true where a point is on it. See [Glyphs] and [TraceScorer]. */
    val inside: (Pt) -> Boolean = { false },
    val strokes: List<List<Pt>> = emptyList(),
    /** Null until he says he is done, and again the moment he starts writing over a poor try. */
    val score: TraceScore? = null,
    val tries: Int = 0,
    val done: Boolean = false,
    val levelChanged: Int? = null,
    /** Said on the screen when the phone said nothing out loud. */
    val error: String? = null,
)

/**
 * One sitting of writing: six letters or words traced with a finger.
 *
 * There is no wrong here, only "not yet". A trace that misses gets the nudge, the word «Ξανά», and
 * the template back under his strokes, as often as he likes; what the mark costs him is only the
 * difference between CORRECT (first pass) and ASSISTED (with the letter shown again), which is what
 * moves the level. Skipping is evidence too — a level he skips his way through has to be steppable
 * down — and nothing here writes a [gr.dimitris.app.core.scheduler.Scheduler] row: how well he can
 * draw the shape of «ψωμί» says nothing about whether he can find the word tomorrow.
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
    private val gson = Gson()

    /** The canvas in pixels, as the screen last measured it. Zero until it has been laid out once. */
    private var boxWidth = 0f
    private var boxHeight = 0f

    /** Screen pixels per dp: the floors below are about the size of his fingertip, not of the glyph. */
    private val density = graph.app.resources.displayMetrics.density

    private fun dp(value: Float): Float = value * density

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

    init { load() }

    private fun load() {
        loadJob = viewModelScope.launch {
            val level = runCatching { graph.settings.traceLevel.first() }
                .getOrElse { graph.errors.record("trace level read", it); MIN_LEVEL }
            val hand = runCatching { graph.settings.traceHand.first() }
                .getOrElse { graph.errors.record("trace hand read", it); Settings.HAND_LEFT }
            val words = if (level >= WORDS_FROM_LEVEL) {
                runCatching { graph.db.items().activeOfKinds(listOf(ItemKind.WORD)) }
                    .getOrElse { graph.errors.record("trace words", it); emptyList() }
            } else emptyList()
            targets = plan(level, words)
            // Not before the settings read: their wait is not his writing time.
            startedAt = now()
            val first = targets.first()
            _state.value = TraceState(level = level, hand = hand, total = targets.size, text = first.text, itemId = first.itemId)
            // The canvas is usually laid out before this read comes back, so the template it asked
            // for has to be built now that there is finally something to build it from.
            rebuildTemplate()
        }
    }

    /**
     * The six things he will write, all at one level. Levels 1 and 2 are single letters, level 3 is
     * his own name in both cases, and levels 4 and 5 are the shortest words on the device — the
     * shortest, because a word he can finish is worth more than a long one he abandons halfway.
     *
     * A device with no words at all falls back to his name rather than to an empty screen: «Γράψε»
     * is the one module that always has something to offer, since the alphabet is not content anyone
     * can delete.
     */
    private fun plan(level: Int, words: List<Item>): List<TraceTarget> = when (level) {
        1 -> CAPITALS.shuffled().take(wanted).map { TraceTarget(it) }
        2 -> SMALL.shuffled().take(wanted).map { TraceTarget(it) }
        3 -> List(wanted) { i -> TraceTarget(NAME[i % NAME.size]) }
        else -> {
            // Twice as many short words as the sitting needs, then shuffled: the six shortest words
            // on the device, in the same order, every sitting for the rest of his life is not
            // practice, it is a rut — and the pool is still short words, which is the point.
            val shortest = words.filter { it.text.isNotBlank() }
                .sortedWith(compareBy({ it.text.length }, { it.text }))
                .take(wanted * SHORT_POOL)
                .shuffled()
            if (shortest.isEmpty()) List(wanted) { i -> TraceTarget(NAME[i % NAME.size]) }
            // The item id only where it means something: at level 5 the word is a prompt for
            // recall, not a card he is practising, so that row is about the level like 1..3 are.
            else List(wanted) { i ->
                val word = shortest[i % shortest.size]
                TraceTarget(word.text, if (level < RECALL_LEVEL) word.id else null)
            }
        }
    }

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

    private fun rebuildTemplate() {
        val text = _state.value.text
        if (text.isEmpty() || boxWidth <= 0f || boxHeight <= 0f) return
        val glyph = runCatching { Glyphs.template(text, boxWidth, boxHeight) }
            .getOrElse { graph.errors.record("trace template", it); GlyphTemplate(emptyList(), 0f) { false } }
        _state.update { it.copy(template = glyph.points, templateHeight = glyph.height, inside = glyph.inside) }
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
        _state.update { it.copy(strokes = emptyList(), score = null) }
    }

    /**
     * «Το είδα», level 5 only: the letter goes away and he writes it from memory.
     *
     * The paper goes with it. Otherwise the cheapest way through level 5 is to trace the word while
     * it is still on the screen, press «Το είδα», and hand in a fully traced word to be marked under
     * the kinder recall line — easier than level 4 for doing less, and the recall exercise never
     * happens. Taking the letter away has to mean taking it away.
     */
    fun hide() {
        if (finishing || ending) return
        if (_state.value.level < RECALL_LEVEL) return
        _state.update { it.copy(templateVisible = false, strokes = emptyList(), score = null) }
    }

    /**
     * True once he is writing from memory: level 5, with the letter hidden by «Το είδα». It is the
     * hidden template and not the level that makes the exercise the harder one, so it is also what
     * earns the kinder marking — otherwise the cheapest way through level 5 would be to ignore
     * «Το είδα», trace the letter that is still on the screen, and be marked more gently than at
     * level 4 for doing less.
     */
    private fun writingFromMemory(s: TraceState): Boolean = s.level >= RECALL_LEVEL && !s.templateVisible

    /**
     * «Έτοιμο». A pass is said out loud and written down; a miss is a nudge, the template back under
     * his strokes, and another go — never a fail state, and never a letter that cannot be finished.
     */
    fun check() {
        val s = _state.value
        if (finishing || ending || s.text.isEmpty()) return
        // An empty canvas is not a poor attempt: it would nudge him, spend his first try, and at
        // level 5 give away the word he was about to write. The button is disabled too.
        if (s.strokes.isEmpty()) return
        // Level 5 is not begun until he has taken the letter away.
        if (s.level >= RECALL_LEVEL && s.templateVisible) return

        val fromMemory = writingFromMemory(s)
        val h = s.templateHeight
        // In pixels, and never smaller than a fingertip: 10 % of the height is half a stem on a
        // capital and a hair's breadth on a word of eight letters, and his hand is the same size for
        // both. Every stroke is judged on its own, so lifting his finger is never counted as a line.
        val score = TraceScorer.scoreStrokes(
            strokes = s.strokes,
            template = s.template,
            templateHeight = h,
            inside = s.inside,
            tolerancePx = maxOf((if (fromMemory) RECALL_TOLERANCE else TOLERANCE) * h, dp(MIN_TOLERANCE_DP)),
            coverageRadiusPx = maxOf(COVERAGE_RADIUS * h, dp(MIN_COVERAGE_RADIUS_DP)),
            minCoverage = if (fromMemory) RECALL_MIN_COVERAGE else MIN_COVERAGE,
        )
        if (score.passed) {
            graph.feedback.success()
            finishing = true
            // The letter comes back under his own writing, so he can see what he made of it — at
            // level 5 that is the answer to what he was remembering, and he has earned the look.
            _state.update { it.copy(score = score, templateVisible = true) }
            // What he has just written, said: the point of writing it is that it is a word.
            viewModelScope.launch { report(graph.speaker.speakText(s.text)) }
            record(s, score, firstTry = s.tries == 0)
        } else {
            graph.feedback.nudge()
            // His strokes stay where they are and the letter comes back over them, so he can see
            // where he went. At level 5 that is also the answer to what he was trying to remember.
            _state.update { it.copy(score = score, tries = it.tries + 1, templateVisible = true) }
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
        _state.update {
            it.copy(
                index = i, text = target.text, itemId = target.itemId, templateVisible = true,
                template = emptyList(), templateHeight = 0f, inside = { false },
                strokes = emptyList(), score = null, tries = 0,
            )
        }
        rebuildTemplate()
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
            val newLevel = LevelProgression.next(level, results, MIN_LEVEL, MAX_LEVEL)
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
        loadJob?.cancel()
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
        val detail = gson.toJson(
            mapOf(
                "text" to s.text,
                "level" to s.level,
                "tries" to s.tries,
                // Which hand he was told to use. Nothing else records it, the rows are append-only,
                // and "was this his good hand?" is the first question anyone will ask of them.
                "hand" to s.hand,
                "meanDistance" to score?.meanDistance?.takeIf { it != Float.MAX_VALUE },
                "coverage" to score?.coverage,
            )
        )
        // Read eagerly: the clock is restarted the moment the next letter arrives.
        val began = startedAt
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

        /** From here the letters come from his own vocabulary instead of the alphabet. */
        const val WORDS_FROM_LEVEL = 4

        /** Writing from memory: the template is shown once, then taken away. */
        const val RECALL_LEVEL = 5

        /**
         * How far off the letter he may be on average, as a fraction of its height, and how near a
         * point of the outline counts as gone over. Under [MIN_TOLERANCE_DP] and
         * [MIN_COVERAGE_RADIUS_DP] they stop being fractions: a word of eight letters is a tenth as
         * tall as a capital, and his hand does not shrink with it.
         */
        const val TOLERANCE = 0.10f
        const val COVERAGE_RADIUS = 0.15f
        const val MIN_TOLERANCE_DP = 10f
        const val MIN_COVERAGE_RADIUS_DP = 14f
        const val MIN_COVERAGE = 0.6f

        /** Level 5 once the letter is hidden: looser, because there is nothing left to follow. */
        const val RECALL_TOLERANCE = 0.14f
        const val RECALL_MIN_COVERAGE = 0.4f

        /** How deep into the short words levels 4 and 5 draw before shuffling: six of twelve. */
        const val SHORT_POOL = 2

        /** What a poor trace says on the screen. Never "λάθος": there is nothing to fail here. */
        const val TRY_AGAIN = "Ξανά"

        /** Said on the screen when the letter he passed made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

        /** Attempt rows that belong to a level rather than to a word on the device. */
        const val ITEM_PREFIX = "trace:level:"

        /** Level 3: his own name, twice as it is written and once in capitals. */
        val NAME = listOf("Δημήτρης", "Δημήτρης", "ΔΗΜΗΤΡΗΣ")

        val CAPITALS = "ΑΒΓΔΕΖΗΘΙΚΛΜΝΞΟΠΡΣΤΥΦΧΨΩ".map { it.toString() }
        val SMALL = "αβγδεζηθικλμνξοπρστυφχψω".map { it.toString() }
    }
}
