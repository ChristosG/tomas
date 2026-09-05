package gr.dimitris.app.modules.numbers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.GreekNumbers
import gr.dimitris.app.core.scheduler.startOfDay
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NumbersState(
    val level: Int = 1,
    val index: Int = 0,
    val total: Int = NumbersModule.EXERCISES_PER_SESSION,
    val exercise: NumberExercise? = null,
    /** Count exercise: how many objects tapped so far. */
    val tapped: Int = 0,
    /** Chosen option, null until he answers. */
    val chosen: Int? = null,
    val correct: Boolean? = null,
    val wrongTries: Int = 0,
    val done: Boolean = false,
    val levelChanged: Int? = null,
    /** Said on the screen when a prompt made no sound at all. */
    val error: String? = null,
)

class NumbersViewModel(private val graph: AppGraph, private val sessionId: String?) : ViewModel() {
    private val _state = MutableStateFlow(NumbersState())
    val state: StateFlow<NumbersState> = _state.asStateFlow()
    private var exercises: List<NumberExercise> = emptyList()
    private var startedAt = now()
    private val results = mutableListOf<Boolean>()
    private val gson = Gson()

    /**
     * The attempt write of the exercise just answered. It runs on the app scope, so the end of the
     * session and the back arrow both join it first: the session counts rows, and a row still in
     * flight is not one.
     */
    private var lastWrite: Job? = null

    /** Set the moment the session starts winding down, so two taps cannot end it twice. */
    private var ending = false

    init {
        viewModelScope.launch {
            val level = graph.settings.numbersLevel.first()
            val prices = runCatching { graph.db.items().withPrices().map { Price(it.text, it.priceCents!!) } }
                .getOrElse { graph.errors.record("numbers prices", it); emptyList() }
            exercises = ExerciseGenerator().session(level, prices, NumbersModule.EXERCISES_PER_SESSION)
            results += recentResults(level)
            _state.value = NumbersState(level = level, exercise = exercises.first(), total = exercises.size)
            speakPrompt()
        }
    }

    private suspend fun recentResults(level: Int): List<Boolean> =
        runCatching {
            graph.db.attempts().since(startOfDay(now()) - 30L * 24 * 60 * 60 * 1000)
                .filter { it.module == ModuleId.NUMBERS && it.itemId == "numbers:level:$level" }
                .map { it.outcome == Outcome.CORRECT }
        }.getOrDefault(emptyList())

    fun speakPrompt() {
        val e = _state.value.exercise ?: return
        viewModelScope.launch { report(graph.speaker.speakText(e.prompt)) }
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
        // Answered already: the question stays as it is until «Επόμενο».
        if (s.correct == true) return
        if (value == e.answer) {
            graph.feedback.success()
            _state.update { it.copy(chosen = value, correct = true) }
            viewModelScope.launch { report(graph.speaker.speakText(sayAnswer(e) + ". Σωστά!")) }
            record(e, firstTry = s.wrongTries == 0, given = value)
        } else {
            graph.feedback.nudge()
            _state.update { it.copy(chosen = value, correct = false, wrongTries = it.wrongTries + 1) }
            viewModelScope.launch { report(graph.speaker.speakText("Όχι αυτό. Δοκίμασε ξανά.")) }
        }
    }

    fun skip() {
        val e = _state.value.exercise ?: return
        graph.feedback.nudge()
        record(e, firstTry = false, given = null, skipped = true)
        next()
    }

    fun next() {
        val s = _state.value
        // Nothing to advance past: the exercises are still loading, or the session is already over.
        if (s.exercise == null) return
        val i = s.index + 1
        if (i >= exercises.size) { finishSession(); return }
        startedAt = now()
        _state.update { it.copy(index = i, exercise = exercises[i], tapped = 0, chosen = null, correct = null, wrongTries = 0) }
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
            val newLevel = NumberProgression.next(level, results)
            if (newLevel != level) graph.settings.setNumbersLevel(newLevel)
            _state.update { it.copy(done = true, levelChanged = newLevel.takeIf { n -> n != level }) }
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

    private fun record(e: NumberExercise, firstTry: Boolean, given: Int?, skipped: Boolean = false) {
        val outcome = when { skipped -> Outcome.SKIPPED; firstTry -> Outcome.CORRECT; else -> Outcome.ASSISTED }
        if (!skipped) results += firstTry
        val detail = gson.toJson(mapOf("type" to e.type, "exercise" to e, "given" to given, "answer" to e.answer))
        // Read eagerly: the clock is restarted the moment the next exercise arrives.
        val began = startedAt
        // The app scope, not this screen's: pressing back must not lose the answer he just gave.
        lastWrite = graph.scope.launch {
            runCatching {
                graph.db.attempts().insert(
                    Attempt(itemId = "numbers:level:${e.level}", module = ModuleId.NUMBERS, sessionId = sessionId, startedAt = began,
                        durationMs = now() - began, outcome = outcome, cueLevel = null, detail = detail)
                )
            }.onFailure { graph.errors.record("numbers record", it) }
        }
    }

    private fun sayAnswer(e: NumberExercise): String = when (e) {
        is NumberExercise.Compare, is NumberExercise.NumberLine, is NumberExercise.Count, is NumberExercise.WordMatch -> GreekNumbers.words(e.answer)
        is NumberExercise.CoinPick, is NumberExercise.Pay -> Euro.format(e.answer)
        is NumberExercise.PriceCompare -> if (e.a.cents >= e.b.cents) e.a.name else e.b.name
    }

    companion object {
        /** Said on the screen when a tap made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."
    }
}
