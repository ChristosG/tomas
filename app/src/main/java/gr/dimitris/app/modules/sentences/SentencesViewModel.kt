package gr.dimitris.app.modules.sentences

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
import gr.dimitris.app.modules.wordcoach.CueLadder
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One finished sentence, as the row will carry it.
 *
 * Free of the ViewModel so that what is written down can be argued with in a unit test rather than
 * on a phone: see `TelemetryTest`. The first four keys are the ones phase 4 already wrote, in the
 * order they always had; the order he put the cards in is kept whole because *which* word went
 * where is the exercise, and a count of right and wrong throws exactly that away.
 */
internal fun sentencesDetail(
    tiles: List<String>,
    chosen: List<String>,
    firstTry: Boolean,
    listened: Int,
    level: Int,
    retries: Int,
    undo: Int,
    ms: Long,
): String = Adapt.detail {
    // Kept, not put: an empty list was written as `[]` before this helper existed and still is,
    // and the labels are his own vocabulary, which these rows already carried.
    kept("tiles", tiles)
    kept("chosen", chosen)
    put("firstTry", firstTry)
    put("listened", listened)
    put("level", level)
    put("retries", retries)
    put("undo", undo)
    put("ms", ms)
}

data class SentencesState(
    val level: Int = SentenceTemplates.MIN_LEVEL,
    /** The 1..5 he set on the first screen. It bounds which levels the progression may reach. */
    val difficulty: Int = Difficulty.DEFAULT,
    val index: Int = 0,
    val total: Int = SentenceTemplates.SENTENCES_PER_SESSION,
    /** Null while the vocabulary loads, and after that the sentence he is building. */
    val sentence: Sentence? = null,
    /** The cards on the board: the sentence's words and, from level 4, one that does not belong. */
    val shuffledTiles: List<Tile> = emptyList(),
    /** What he has tapped so far, in the order he tapped it. */
    val chosen: List<Tile> = emptyList(),
    /** Null until the last card of a sentence is down: there is no verdict on half a sentence. */
    val correct: Boolean? = null,
    val wrongTries: Int = 0,
    /** The sentence is being said right now: «Άκου» is off for exactly as long as that lasts. */
    val modelPlaying: Boolean = false,
    val done: Boolean = false,
    val levelChanged: Int? = null,
    /** Said on the screen when a tap made no sound at all. */
    val error: String? = null,
)

/**
 * One sitting of sentence building.
 *
 * The order is the exercise, so nothing is ever wrong for long: a wrong order says so, says the
 * sentence, leaves it written on the screen for him to copy, and hands the cards back. There is no
 * limit on the tries and no way to fail — only the difference between finding it himself (CORRECT)
 * and finding it with the answer in front of him (ASSISTED), which is what moves the level.
 */
class SentencesViewModel(
    private val graph: AppGraph,
    private val sessionId: String?,
    /** One sentence per item the session handed the module; free practice asks for the full eight. */
    count: Int = SentenceTemplates.SENTENCES_PER_SESSION,
) : ViewModel() {
    private val wanted = count.coerceIn(1, SentenceTemplates.SENTENCES_PER_SESSION)

    // Seeded with the count, so the title says "1/3" while it loads instead of flashing "1/8".
    private val _state = MutableStateFlow(SentencesState(total = wanted))
    val state: StateFlow<SentencesState> = _state.asStateFlow()

    private val templates = SentenceTemplates()
    private var sentences: List<Sentence> = emptyList()
    private var startedAt = now()
    private val results = mutableListOf<Boolean>()

    /** The vocabulary read. Cancelled on the way out, so nothing arrives to speak over the next screen. */
    private var loadJob: Job? = null

    /** How many times he asked to hear this sentence. It goes into the attempt's detail as it stands. */
    private var listens = 0

    /**
     * How many cards he took back off this sentence.
     *
     * Nothing is judged by it and nothing ever will be — «Αναίρεση» is there to be used. It is kept
     * because it is the one thing that separates a sentence he built straight through from one he
     * assembled by trial, and the two look identical in every other column of the row. See
     * `docs/ADAPTATION.md`.
     */
    private var undos = 0

    /** Whatever this screen is saying: a tapped word, the verdict, or the model sentence. */
    private var speakJob: Job? = null

    /**
     * Bumped by every new utterance. A cancelled job's `finally` can land after the next one has
     * started, and it must not put «Άκου» back for a sentence that is still being said.
     */
    private var speakToken = 0

    /**
     * The attempt write of the sentence just finished. It runs on the app scope, so the end of the
     * session and the back arrow both join it first: the session counts rows, and a row still in
     * flight is not one.
     */
    private var lastWrite: Job? = null

    /** Set the moment the sitting starts winding down, so two taps cannot end it twice. */
    private var ending = false

    /**
     * True from the moment a sentence is finished or skipped until the next one starts. It carries
     * both guards: one attempt per sentence, and one advance per finished sentence.
     */
    private var finishing = false

    init { load() }

    /** He moved the dots. The sitting is rebuilt at the level the new band starts from. */
    fun reload() {
        if (ending || finishing) return
        loadJob?.cancel()
        graph.voice.quiet()
        load()
    }

    private fun load() {
        loadJob = viewModelScope.launch {
            val stored = runCatching { graph.settings.sentencesLevel.first() }
                .getOrElse { graph.errors.record("sentences level read", it); SentenceTemplates.MIN_LEVEL }
            val difficulty = runCatching { graph.settings.difficulty(ModuleId.SENTENCES).first() }
                .getOrElse { graph.errors.record("sentences difficulty read", it); Difficulty.DEFAULT }
            // The sitting runs at a level inside the band the dots ask for, and the jump happens
            // *here*, before any work — so it is his own tap that caused it, the sentences are built
            // from it and the attempt rows record it. Clamping at the other end instead made a bad
            // morning a promotion; see [Difficulty.levelAtLoad].
            val level = Difficulty.levelAtLoad(stored, Difficulty.sentences(difficulty))
            if (level != stored) {
                runCatching { graph.settings.setSentencesLevel(level) }
                    .onFailure { graph.errors.record("sentences level clamp", it) }
            }
            val pool = runCatching { graph.db.items().activeOfKinds(listOf(ItemKind.WORD)) }
                .getOrElse { graph.errors.record("sentences pool", it); emptyList() }
            results.clear()
            sentences = plan(level, pool)
            // Not before the settings read and the vocabulary query: their wait is not his thinking time.
            startedAt = now()
            val first = sentences.firstOrNull()
            _state.value = SentencesState(
                level = level,
                difficulty = difficulty,
                // What was really built, not what was asked for: the title counts sentences he will
                // actually be shown, and the session's own count comes from the rows he leaves.
                total = sentences.size,
                sentence = first,
                shuffledTiles = first?.let(::board).orEmpty(),
                // A device with no vocabulary has nothing to build and says so instead of holding him.
                done = first == null,
            )
        }
    }

    /**
     * [wanted] sentences, all at one level: the highest level at or below his that the vocabulary
     * can actually fill. A talk board of four food words still gives him «θέλω καφέ» to build,
     * which is the exercise.
     *
     * One level and not a mixture, because the sitting is what the progression is judged on. A run
     * that quietly slid from level 3 to level 1 halfway down, and then promoted him on the easy
     * half, would pin him at a level he never plays.
     */
    private fun plan(level: Int, pool: List<Item>): List<Sentence> {
        var at = level.coerceAtMost(SentenceTemplates.MAX_LEVEL)
        while (at >= SentenceTemplates.MIN_LEVEL) {
            val made = templates.session(at, pool, wanted).toMutableList()
            if (made.isNotEmpty()) {
                // Short only because a shape came up empty by chance — ask again at the same level.
                while (made.size < wanted) {
                    val more = templates.session(at, pool, wanted - made.size)
                    if (more.isEmpty()) break
                    made += more
                }
                return made
            }
            at--
        }
        return emptyList()
    }

    /**
     * The cards as they are laid out. Plain shuffled, so at level 1 the board is in the answer's
     * order about half the time: any rule that avoided it would be the giveaway in the other
     * direction — "never the left one first" is a pattern he would learn instead of the sentence.
     */
    private fun board(sentence: Sentence): List<Tile> = (sentence.tiles + listOfNotNull(sentence.distractor)).shuffled()

    /**
     * One card tapped. It joins the sentence and says its own word — until it is the last card the
     * sentence has room for, when what is said is the verdict: a new utterance stops the one before
     * it, so saying both would cut the word off after a syllable.
     */
    fun tap(tile: Tile) {
        val s = _state.value
        val sentence = s.sentence ?: return
        // Finished (right, or waiting for «Επόμενο»), or the sitting is over: the board is closed.
        if (finishing || ending) return
        if (s.chosen.any { it.item.id == tile.item.id }) return
        if (s.chosen.size >= sentence.tiles.size) return
        val chosen = s.chosen + tile
        _state.update { it.copy(chosen = chosen) }
        if (chosen.size < sentence.tiles.size) {
            speaking { report(graph.speaker.speakText(tile.label)) }
            return
        }
        if (chosen.map { it.label } == sentence.tiles.map { it.label }) {
            graph.feedback.success()
            finishing = true
            _state.update { it.copy(correct = true) }
            speaking { report(graph.speaker.speakText(sentence.text)) }
            record(sentence, chosen, firstTry = s.wrongTries == 0, retries = s.wrongTries)
        } else {
            // Never a fail state: the sentence is said and left on the screen, the cards come back,
            // and he tries again as often as he likes. Only the first-try mark is spent.
            graph.feedback.nudge()
            _state.update { it.copy(chosen = emptyList(), correct = false, wrongTries = it.wrongTries + 1) }
            speaking { report(graph.speaker.speakText("$WRONG_ORDER ${sentence.text}")) }
        }
    }

    /** The last card back off the sentence. */
    fun undo() {
        if (finishing || ending) return
        if (_state.value.chosen.isEmpty()) return
        undos++
        _state.update { it.copy(chosen = it.chosen.dropLast(1)) }
    }

    /**
     * «Άκου»: the whole sentence, said. There is no cue ladder here — the model *is* the answer, in
     * the order he has to build it — and it is still never withheld (spec §12): a man who cannot
     * retrieve the word order is not taught by being made to guess wrong first.
     *
     * What it costs is the row. The attempt is written at [CueLadder.LISTENED] and as assisted work
     * rather than his own, so the level progression is judged on the sentences he built without
     * hearing them, and the caregiver's numbers do not quietly turn into a score for listening.
     */
    fun listenModel() {
        val s = _state.value
        val sentence = s.sentence ?: return
        if (ending || s.modelPlaying) return
        listens++
        speaking { report(graph.speaker.speakText(sentence.text)) }
    }

    /**
     * Runs one utterance of this screen, and only one: a new tap replaces whatever was sounding.
     * `quiet()` as well as cancelling, because a cancel only lands at the next suspension point and
     * the old voice would be heard under the new one.
     */
    private fun speaking(block: suspend () -> Unit) {
        speakJob?.cancel()
        graph.voice.quiet()
        val token = ++speakToken
        _state.update { it.copy(modelPlaying = true) }
        speakJob = viewModelScope.launch {
            try {
                block()
            } finally {
                if (speakToken == token) _state.update { it.copy(modelPlaying = false) }
            }
        }
    }

    /**
     * Stops whatever this screen was saying and takes «Άκου» out of its playing state. The token is
     * bumped so the cancelled job's `finally` cannot re-open the button for a sentence that is gone.
     */
    private fun silence() {
        speakJob?.cancel()
        speakToken++
        graph.voice.quiet()
        _state.update { it.copy(modelPlaying = false) }
    }

    fun skip() {
        val s = _state.value
        val sentence = s.sentence ?: return
        // One skip per sentence: the button is still there for a frame, and a second tap would pass
        // on the sentence that has not been shown yet.
        if (finishing || ending) return
        finishing = true
        graph.feedback.nudge()
        record(sentence, s.chosen, firstTry = false, retries = s.wrongTries, skipped = true)
        advance()
    }

    fun next() {
        // Only a finished sentence moves on. A second tap on «Επόμενο» — the button is still there
        // for a frame after the first — would otherwise skip the sentence that just arrived, and it
        // would leave the session counting a sentence he was never shown.
        if (!finishing) return
        advance()
    }

    private fun advance() {
        val s = _state.value
        // Nothing to advance past: still loading, or the sitting is already over.
        if (s.sentence == null) return
        finishing = false
        val i = s.index + 1
        if (i >= sentences.size) { finishSitting(); return }
        val sentence = sentences[i]
        startedAt = now()
        // The listens belonged to the sentence being left, and so does whatever was being said: he
        // taps the green «Επόμενο» the instant the mark appears, while the sentence he just built
        // is still being read out. Without this the next board opens with its «Άκου» greyed for a
        // second or two — Chris's field bug in miniature, on the one screen where «Άκου» is the
        // answer. The new sentence starts with its own count, in silence, and with the button live.
        listens = 0
        undos = 0
        silence()
        _state.update {
            it.copy(index = i, sentence = sentence, shuffledTiles = board(sentence), chosen = emptyList(), correct = null, wrongTries = 0)
        }
    }

    private fun finishSitting() {
        if (ending) return
        ending = true
        // The session counts attempt rows as soon as it is told the module is done, so the last
        // sentence's write has to be in the database before "done" ever reaches the screen.
        val write = lastWrite
        viewModelScope.launch {
            write?.join()
            val level = _state.value.level
            // Judged on the level he actually played, which is not always the one he is set to: with
            // a vocabulary too thin for level 3, a perfect sitting of level-1 sentences is evidence
            // about level 1. Promoting him off it would pin him at a level nothing can build.
            val played = sentences.minOfOrNull { it.level } ?: level
            // Inside the band the dots ask for (spec §13), and never *up* unless the sitting earned
            // it: the sitting still decides when he moves, the dots decide how far it may take him,
            // and a morning he got wrong can only ever hold him or step him back.
            val band = Difficulty.sentences(_state.value.difficulty)
            val newLevel = Difficulty.levelAfterSitting(played, LevelProgression.next(played, results, band.first, band.last), band)
            // A settings write that fails must not strand him on a screen that never says "done".
            val moved = newLevel != level && runCatching { graph.settings.setSentencesLevel(newLevel) }
                .onFailure { graph.errors.record("sentences level write", it) }.isSuccess
            _state.update { it.copy(done = true, levelChanged = newLevel.takeIf { moved }) }
        }
    }

    /**
     * The user pressed back. Whatever was being said stops here, and [then] waits for the last
     * sentence's write: the session counts rows the moment it is told, so leaving before the row
     * lands would lose the sentence he had just built.
     */
    fun leave(then: () -> Unit) {
        loadJob?.cancel()
        silence()
        val write = lastWrite
        viewModelScope.launch { write?.join(); then() }
    }

    /**
     * Records the outcome of one spoken word. Silence is the one failure Dimitris cannot diagnose
     * himself, so it is said on the screen and cleared by the next sound that comes out.
     */
    private fun report(result: Result<*>) = result.fold(
        onSuccess = { _state.update { if (it.error == SPEECH_FAILED) it.copy(error = null) else it } },
        onFailure = { e -> graph.errors.record("sentences speak", e); _state.update { it.copy(error = SPEECH_FAILED) } },
    )

    private fun record(sentence: Sentence, chosen: List<Tile>, firstTry: Boolean, retries: Int, skipped: Boolean = false) {
        // A sentence he asked to hear is a sentence he was given: assisted work, on the same footing
        // as one he had to be corrected on. The button stays; only the row knows.
        val heard = listens
        val outcome = when {
            skipped -> Outcome.SKIPPED
            firstTry && heard == 0 -> Outcome.CORRECT
            else -> Outcome.ASSISTED
        }
        // Every finished sentence is evidence, a skip included: passing on a sentence is not neutral,
        // it is one he could not do, and a level he skips his way through has to be steppable down.
        results += (outcome == Outcome.CORRECT)
        // Read eagerly: the clock is restarted the moment the next sentence arrives.
        val began = startedAt
        val detail = sentencesDetail(
            tiles = sentence.tiles.map { it.label },
            chosen = chosen.map { it.label },
            firstTry = firstTry,
            listened = heard,
            level = sentence.level,
            retries = retries,
            undo = undos,
            ms = now() - began,
        )
        // Chained, because the app scope runs on a pool with no ordering: whoever joins the last
        // write must be joining every write, or a row can land after the session has counted.
        val prev = lastWrite
        // The app scope, not this screen's: pressing back must not lose the sentence he just built.
        lastWrite = graph.scope.launch {
            prev?.join()
            runCatching {
                graph.db.attempts().insert(
                    Attempt(
                        itemId = "sentences:level:${sentence.level}", module = ModuleId.SENTENCES, sessionId = sessionId,
                        startedAt = began, durationMs = now() - began, outcome = outcome,
                        // The one thing this module has to say on the word coach's 0–4 scale: he
                        // had the sentence said to him. Otherwise there is no ladder here at all.
                        cueLevel = if (heard > 0) CueLadder.LISTENED else null, detail = detail,
                    )
                )
            }.onFailure { graph.errors.record("sentences record", it) }
        }
    }

    companion object {
        /** Said on the screen when a tap made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

        /**
         * What an order that was not the sentence is answered with, out loud and in writing.
         *
         * «Όχι έτσι.» — the phase-4 wording — was already softer than «Λάθος», but it is still a
         * "no" said to him, and since «Άκου» he can reach that "no" straight after doing the one
         * thing the app tells him to do: hear the sentence, then build it. Spec §12 does not allow
         * an assisted or retried turn to be called wrong, so what is left is the encouragement and
         * the model to copy, which is the whole of the exercise anyway.
         */
        const val WRONG_ORDER = "Σχεδόν."
    }
}
