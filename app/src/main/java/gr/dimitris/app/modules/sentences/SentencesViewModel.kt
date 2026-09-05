package gr.dimitris.app.modules.sentences

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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SentencesState(
    val level: Int = SentenceTemplates.MIN_LEVEL,
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
    private val gson = Gson()

    /** The vocabulary read. Cancelled on the way out, so nothing arrives to speak over the next screen. */
    private var loadJob: Job? = null

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

    private fun load() {
        loadJob = viewModelScope.launch {
            val level = runCatching { graph.settings.sentencesLevel.first() }
                .getOrElse { graph.errors.record("sentences level read", it); SentenceTemplates.MIN_LEVEL }
            val pool = runCatching { graph.db.items().activeOfKinds(listOf(ItemKind.WORD)) }
                .getOrElse { graph.errors.record("sentences pool", it); emptyList() }
            sentences = plan(level, pool)
            // Not before the settings read and the vocabulary query: their wait is not his thinking time.
            startedAt = now()
            val first = sentences.firstOrNull()
            _state.value = SentencesState(
                level = level,
                total = sentences.size.coerceAtLeast(1),
                sentence = first,
                shuffledTiles = first?.let(::board).orEmpty(),
                // A device with no vocabulary has nothing to build and says so instead of holding him.
                done = first == null,
            )
        }
    }

    /**
     * [wanted] sentences at [level], dropping a level whenever the vocabulary cannot fill the shape:
     * a talk board of four food words still gives him «θέλω καφέ» to build, which is the exercise.
     */
    private fun plan(level: Int, pool: List<Item>): List<Sentence> {
        val out = mutableListOf<Sentence>()
        var at = level
        while (out.size < wanted && at >= SentenceTemplates.MIN_LEVEL) {
            out += templates.session(at, pool, wanted - out.size)
            at--
        }
        return out
    }

    /** The cards as they are laid out: shuffled, so the order on the board is never the answer. */
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
            viewModelScope.launch { report(graph.speaker.speakText(tile.label)) }
            return
        }
        if (chosen.map { it.label } == sentence.tiles.map { it.label }) {
            graph.feedback.success()
            finishing = true
            _state.update { it.copy(correct = true) }
            viewModelScope.launch { report(graph.speaker.speakText(sentence.text)) }
            record(sentence, chosen, firstTry = s.wrongTries == 0)
        } else {
            // Never a fail state: the sentence is said and left on the screen, the cards come back,
            // and he tries again as often as he likes. Only the first-try mark is spent.
            graph.feedback.nudge()
            _state.update { it.copy(chosen = emptyList(), correct = false, wrongTries = it.wrongTries + 1) }
            viewModelScope.launch { report(graph.speaker.speakText("$WRONG_ORDER ${sentence.text}")) }
        }
    }

    /** The last card back off the sentence. */
    fun undo() {
        if (finishing || ending) return
        _state.update { it.copy(chosen = it.chosen.dropLast(1)) }
    }

    fun skip() {
        val s = _state.value
        val sentence = s.sentence ?: return
        // One skip per sentence: the button is still there for a frame, and a second tap would pass
        // on the sentence that has not been shown yet.
        if (finishing || ending) return
        finishing = true
        graph.feedback.nudge()
        record(sentence, s.chosen, firstTry = false, skipped = true)
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
            val newLevel = LevelProgression.next(level, results, SentenceTemplates.MIN_LEVEL, SentenceTemplates.MAX_LEVEL)
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
        onFailure = { e -> graph.errors.record("sentences speak", e); _state.update { it.copy(error = SPEECH_FAILED) } },
    )

    private fun record(sentence: Sentence, chosen: List<Tile>, firstTry: Boolean, skipped: Boolean = false) {
        val outcome = when { skipped -> Outcome.SKIPPED; firstTry -> Outcome.CORRECT; else -> Outcome.ASSISTED }
        // Every finished sentence is evidence, a skip included: passing on a sentence is not neutral,
        // it is one he could not do, and a level he skips his way through has to be steppable down.
        results += (outcome == Outcome.CORRECT)
        val detail = gson.toJson(
            mapOf(
                "tiles" to sentence.tiles.map { it.label },
                "chosen" to chosen.map { it.label },
                "firstTry" to firstTry,
            )
        )
        // Read eagerly: the clock is restarted the moment the next sentence arrives.
        val began = startedAt
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
                        startedAt = began, durationMs = now() - began, outcome = outcome, cueLevel = null, detail = detail,
                    )
                )
            }.onFailure { graph.errors.record("sentences record", it) }
        }
    }

    companion object {
        /** Said on the screen when a tap made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

        /** What a wrong order is answered with, out loud and in writing. Never "λάθος". */
        const val WRONG_ORDER = "Όχι έτσι."
    }
}
