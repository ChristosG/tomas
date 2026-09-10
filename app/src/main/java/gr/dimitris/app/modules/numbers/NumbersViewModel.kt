package gr.dimitris.app.modules.numbers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Adapt
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.GreekNumbers
import gr.dimitris.app.core.greek.GreekTime
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One finished exercise, as the row will carry it.
 *
 * Free of the ViewModel so that what is written down can be argued with in a unit test rather than
 * on a phone: see `TelemetryTest`. The first four keys are the ones phase 3 already wrote, in the
 * order they always had; [given] is the option he tapped, and where it is not the answer the
 * *distance* between the two says how far off he was, which is the whole reason it is kept.
 */
internal fun numbersDetail(e: NumberExercise, given: Int?, retries: Int, ms: Long): String = Adapt.detail {
    put("type", e.type)
    // The exercise itself, as it always was: the numbers he was shown are not reconstructible
    // from the level alone, and a year from now "he was wrong" without them is unreadable.
    kept("exercise", e)
    put("given", given)
    put("answer", e.answer)
    put("level", e.level)
    // How many options were on the screen. A wrong tap out of two is a coin toss; out of four it
    // is an answer, and the level progression cannot tell them apart without this.
    put("optionCount", e.options.size)
    put("retries", retries)
    put("ms", ms)
}

data class NumbersState(
    val level: Int = 1,
    /** The 1..5 he set on the first screen. It bounds which levels the progression may reach. */
    val difficulty: Int = Difficulty.DEFAULT,
    val index: Int = 0,
    val total: Int = NumbersModule.EXERCISES_PER_SESSION,
    val exercise: NumberExercise? = null,
    /** Count exercise: how many objects tapped so far. */
    val tapped: Int = 0,
    /** Chosen option, null until he answers. */
    val chosen: Int? = null,
    val correct: Boolean? = null,
    val wrongTries: Int = 0,
    /** Two misses: the right option is lit and said, and the only thing left to do is «Επόμενο». */
    val revealed: Boolean = false,
    val done: Boolean = false,
    val levelChanged: Int? = null,
    /** Said on the screen when a prompt made no sound at all. */
    val error: String? = null,
)

class NumbersViewModel(
    private val graph: AppGraph,
    private val sessionId: String?,
    /** One exercise per item the session handed the module; free practice asks for the full ten. */
    private val count: Int = NumbersModule.EXERCISES_PER_SESSION,
) : ViewModel() {
    // Seeded with the count, so the title says "1/7" while it loads instead of flashing "1/10".
    private val _state = MutableStateFlow(NumbersState(total = count))
    val state: StateFlow<NumbersState> = _state.asStateFlow()
    private var exercises: List<NumberExercise> = emptyList()
    private var startedAt = now()
    private val results = mutableListOf<Boolean>()

    /**
     * The attempt write of the exercise just answered. It runs on the app scope, so the end of the
     * session and the back arrow both join it first: the session counts rows, and a row still in
     * flight is not one.
     */
    private var lastWrite: Job? = null

    /** The settings read and the exercise build. Cancelled by [reload], which starts another. */
    private var loadJob: Job? = null

    /** Set the moment the session starts winding down, so two taps cannot end it twice. */
    private var ending = false

    /**
     * True from the moment an exercise is answered or skipped until the next one starts. It carries
     * both guards: one attempt per exercise, and one advance per finished exercise.
     */
    private var finishing = false

    init { load() }

    /**
     * One sitting of exercises, at the level he is on.
     *
     * Re-entered by [reload] when he moves the dots on the first screen: the difficulty he has just
     * asked for has already moved the stored level to the bottom of its band, so the sitting is built
     * again from there. Nothing is lost by that — the row is only on the screen before the first
     * answer — and it is the whole reason the dots feel like a control rather than a preference.
     */
    private fun load() {
        loadJob = viewModelScope.launch {
            val stored = runCatching { graph.settings.numbersLevel.first() }
                .getOrElse { graph.errors.record("numbers level read", it); NumberProgression.MIN_LEVEL }
            val difficulty = runCatching { graph.settings.difficulty(ModuleId.NUMBERS).first() }
                .getOrElse { graph.errors.record("numbers difficulty read", it); Difficulty.DEFAULT }
            // The sitting runs at a level inside the band the dots ask for, and the jump happens
            // here, before any work: the exercises are generated from it and every row's
            // `detail.level` records it. See [Difficulty.levelAtLoad] for what clamping at the other
            // end instead did to a bad morning.
            val level = Difficulty.levelAtLoad(stored, Difficulty.numbers(difficulty))
            if (level != stored) {
                runCatching { graph.settings.setNumbersLevel(level) }
                    .onFailure { graph.errors.record("numbers level clamp", it) }
            }
            val prices = runCatching { graph.db.items().withPrices().map { Price(it.text, it.priceCents!!) } }
                .getOrElse { graph.errors.record("numbers prices", it); emptyList() }
            // Through the same rule the module used, so the list is never empty whatever it was handed.
            exercises = ExerciseGenerator().session(level, prices, exercisesFor(count))
            results.clear()
            // Not before the settings read and the price query: their wait is not his thinking time.
            startedAt = now()
            _state.value = NumbersState(level = level, difficulty = difficulty, exercise = exercises.first(), total = exercises.size)
            speakPrompt()
        }
    }

    /** He moved the dots. The sitting is rebuilt at the level the new band starts from. */
    fun reload() {
        if (ending || finishing) return
        loadJob?.cancel()
        graph.voice.quiet()
        load()
    }

    fun speakPrompt() {
        val e = _state.value.exercise ?: return
        viewModelScope.launch { report(graph.speaker.speakText(e.spokenPrompt)) }
    }

    /**
     * Records the outcome of one spoken prompt. Silence is the one failure Dimitris cannot diagnose
     * himself, so it is said on the screen and cleared by the next sound that comes out.
     */
    private fun report(result: Result<*>) = result.fold(
        onSuccess = { _state.update { if (it.error == SPEECH_FAILED) it.copy(error = null) else it } },
        onFailure = { e -> graph.errors.record("numbers speak", e); _state.update { it.copy(error = SPEECH_FAILED) } },
    )

    /** Count exercise: one object tapped; says the running count. */
    fun tapObject() {
        val s = _state.value
        val e = s.exercise as? NumberExercise.Count ?: return
        if (s.tapped >= e.n) return
        val n = s.tapped + 1
        _state.update { it.copy(tapped = n) }
        graph.feedback.tap()
        viewModelScope.launch { report(graph.speaker.speakText(GreekNumbers.words(n))) }
    }

    fun choose(value: Int) {
        val s = _state.value
        val e = s.exercise ?: return
        // Answered already (right, or shown after two misses): the question stays as it is until
        // «Επόμενο», and the last screen of a finished run takes no answers at all.
        if (finishing || ending) return
        if (value == e.answer) {
            graph.feedback.success()
            finishing = true
            _state.update { it.copy(chosen = value, correct = true) }
            viewModelScope.launch { report(graph.speaker.speakText(sayAnswer(e) + ". Σωστά!")) }
            record(e, firstTry = s.wrongTries == 0, given = value, retries = s.wrongTries)
        } else if (s.wrongTries + 1 >= WRONG_TRIES_BEFORE_REVEAL) {
            // Second miss: he is shown and told the answer instead of being left to tap on. It counts
            // as helped, and a helped answer is what lets the progression step him back down from a
            // level he cannot do — being stuck is exactly the thing it has to notice.
            graph.feedback.nudge()
            finishing = true
            _state.update { it.copy(chosen = value, correct = false, wrongTries = it.wrongTries + 1, revealed = true) }
            viewModelScope.launch { report(graph.speaker.speakText("Να το σωστό: ${sayAnswer(e)}.")) }
            record(e, firstTry = false, given = value, retries = s.wrongTries + 1)
        } else {
            graph.feedback.nudge()
            _state.update { it.copy(chosen = value, correct = false, wrongTries = it.wrongTries + 1) }
            viewModelScope.launch { report(graph.speaker.speakText("Ξανά.")) }
        }
    }

    fun skip() {
        val e = _state.value.exercise ?: return
        // One skip per exercise: the button is still there for a frame, and a second tap would pass
        // on the exercise that has not been shown yet.
        if (finishing || ending) return
        finishing = true
        graph.feedback.nudge()
        record(e, firstTry = false, given = null, retries = _state.value.wrongTries, skipped = true)
        advance()
    }

    fun next() {
        // Only a finished exercise moves on. A second tap on «Επόμενο» — the button is still there
        // for a frame after the first — would otherwise skip the exercise that just arrived, and it
        // would leave the session counting an exercise he was never shown.
        if (!finishing) return
        advance()
    }

    private fun advance() {
        val s = _state.value
        // Nothing to advance past: the exercises are still loading, or the session is already over.
        if (s.exercise == null) return
        finishing = false
        val i = s.index + 1
        if (i >= exercises.size) { finishSession(); return }
        startedAt = now()
        _state.update { it.copy(index = i, exercise = exercises[i], tapped = 0, chosen = null, correct = null, wrongTries = 0, revealed = false) }
        speakPrompt()
    }

    private fun finishSession() {
        if (ending) return
        ending = true
        // The session counts attempt rows as soon as it is told the module is done, so the last
        // answer's write has to be in the database before "done" ever reaches the screen.
        val write = lastWrite
        viewModelScope.launch {
            write?.join()
            val level = _state.value.level
            // The step the sitting earned, held inside the band the dots ask for (spec §13), and
            // never *up* unless the sitting earned it: the progression still decides when he moves,
            // the dots decide how far it may take him, and ten questions he got wrong can only ever
            // hold him or step him back. See [Difficulty.levelAfterSitting].
            val band = Difficulty.numbers(_state.value.difficulty)
            val newLevel = Difficulty.levelAfterSitting(level, NumberProgression.next(level, results), band)
            // A settings write that fails must not strand him on a screen that never says "done".
            val moved = newLevel != level && runCatching { graph.settings.setNumbersLevel(newLevel) }
                .onFailure { graph.errors.record("numbers level write", it) }.isSuccess
            _state.update { it.copy(done = true, levelChanged = newLevel.takeIf { moved }) }
        }
    }

    /**
     * The user pressed back. Whatever was being said stops here, and [then] waits for the last
     * answer's write: the session counts rows the moment it is told, so leaving before the row
     * lands would lose the exercise he had just done.
     */
    fun leave(then: () -> Unit) {
        graph.voice.quiet()
        val write = lastWrite
        viewModelScope.launch { write?.join(); then() }
    }

    private fun record(e: NumberExercise, firstTry: Boolean, given: Int?, retries: Int, skipped: Boolean = false) {
        val outcome = when { skipped -> Outcome.SKIPPED; firstTry -> Outcome.CORRECT; else -> Outcome.ASSISTED }
        // Every finished exercise is evidence, a skip included: passing on a question is not neutral,
        // it is one he could not do, and a level he skips his way through has to be steppable down.
        results += (outcome == Outcome.CORRECT)
        // Read eagerly: the clock is restarted the moment the next exercise arrives.
        val began = startedAt
        val detail = numbersDetail(e, given = given, retries = retries, ms = now() - began)
        // Chained, because the app scope runs on a pool with no ordering: whoever joins the last
        // write must be joining every write, or a row can land after the session has counted.
        val prev = lastWrite
        // The app scope, not this screen's: pressing back must not lose the answer he just gave.
        lastWrite = graph.scope.launch {
            prev?.join()
            runCatching {
                graph.db.attempts().insert(
                    Attempt(itemId = "numbers:level:${e.level}", module = ModuleId.NUMBERS, sessionId = sessionId, startedAt = began,
                        durationMs = now() - began, outcome = outcome, cueLevel = null, detail = detail)
                )
            }.onFailure { graph.errors.record("numbers record", it) }
        }
    }

    private fun sayAnswer(e: NumberExercise): String = when (e) {
        is NumberExercise.Compare, is NumberExercise.NumberLine, is NumberExercise.Count, is NumberExercise.WordMatch,
        is NumberExercise.Arithmetic, is NumberExercise.Missing, is NumberExercise.WordProblem,
        -> GreekNumbers.words(e.answer)
        // Spoken, not written: Greek TTS reads "10,00 €" as punctuation.
        is NumberExercise.CoinPick, is NumberExercise.Pay, is NumberExercise.Change -> Euro.spoken(e.answer)
        is NumberExercise.PriceCompare -> if (e.a.cents >= e.b.cents) e.a.name else e.b.name
        // The words and not the digits: «τρεις και μισή» is what the answer to a clock face sounds like.
        is NumberExercise.Clock -> GreekTime.words(e.answer)
        is NumberExercise.DayAfter -> GreekTime.day(e.answer)
    }

    companion object {
        /** Said on the screen when a tap made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

        /** One free retry, then the answer. Tapping on blind past that teaches nothing. */
        const val WRONG_TRIES_BEFORE_REVEAL = 2
    }
}
