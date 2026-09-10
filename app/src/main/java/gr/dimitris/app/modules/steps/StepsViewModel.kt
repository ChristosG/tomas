package gr.dimitris.app.modules.steps

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Adapt
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.speech.Recognition
import gr.dimitris.app.core.speech.SpeechFailure
import gr.dimitris.app.core.speech.take
import gr.dimitris.app.modules.wordcoach.CueLadder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.random.Random

/**
 * Which half of a task he is on.
 *
 * Two stages and not two screens: the strip he builds in [ORDER] is the strip he reads from in
 * [TELL], in the same place on the same screen, because the telling is *about* the thing he just
 * built. A second screen would have taken it away at the moment it became useful.
 */
enum class StepStage { ORDER, TELL }

/**
 * One finished exercise, as the row will carry it.
 *
 * Free of the ViewModel so that what is written down can be argued with in a unit test rather than on
 * a phone: see `StepsViewModelTest`.
 *
 * [steps] is the part that makes the row answerable a year from now. On an ordering row it is **his**
 * order, tile by tile, because *where* he put the wrong step is the whole of what went wrong and a
 * count of tries throws exactly that away; on a telling row it is the task's own order, which is what
 * the telling was judged against. Which of the two a row holds is said by its `itemId`
 * ([StepsViewModel.ORDER_ITEM] / [StepsViewModel.TELL_ITEM]).
 */
internal fun stepsDetail(
    task: StepTask,
    steps: List<String>,
    tries: Int,
    ms: Long,
    judge: Map<String, Any?> = emptyMap(),
): String = Adapt.detail {
    put("task", task.title)
    words("steps", steps)
    put("difficulty", task.difficulty)
    put("tries", tries)
    put("judge", judge)
    put("ms", ms)
}

/**
 * What one finished exercise counts as.
 *
 * Three outcomes and no fourth: he did it himself ([Outcome.CORRECT]), he did it after a miss or with
 * the answer in front of him ([Outcome.ASSISTED]), or he passed on it ([Outcome.SKIPPED]). There is
 * no "wrong": the ordering has unlimited retries and the telling ends in «Το είπα!», so nothing in
 * this module can end with him having failed.
 */
internal fun stepsOutcome(firstTry: Boolean, skipped: Boolean): Outcome = when {
    skipped -> Outcome.SKIPPED
    firstTry -> Outcome.CORRECT
    else -> Outcome.ASSISTED
}

/**
 * The first place his order parts company with the task's, or null when what he has put down so far is
 * right.
 *
 * It compares **groups**, not steps ([StepTask.groups]). Thirteen of the twenty tasks have more than
 * one right answer — a suitcase's four things go in in any order — and the first cut of this compared
 * the literal step list, so twenty-three of «βαλίτσα»'s twenty-four correct orders were answered with
 * «Σχεδόν.» and one of his correct steps ringed. He cannot deduce the seed's order from the task, so
 * the only way out was trial and error, in the one module built for the thing he says he is worst at.
 *
 * The *first* place, and only the first. A man who has put step 4 where step 2 goes has everything
 * after it wrong as a consequence, and a strip of four marks says nothing about what to do next — it
 * says he got it all wrong, which is both untrue and the one thing this app may never tell him.
 *
 * A strip shorter than the order is not wrong for being unfinished: only the tiles he has laid are
 * compared. A tile in no group at all — the difficulty-5 distractor, for which [groupOf] answers null
 * — is wrong wherever it is, because null is never a group the task wanted.
 */
internal fun firstWrongStep(chosen: List<String>, wanted: List<Int>, groupOf: (String) -> Int?): Int? =
    chosen.indices.firstOrNull { i -> groupOf(chosen[i]) != wanted.getOrNull(i) }

/**
 * Where the next tile he taps goes: into the slot the mark is on, or at the end when nothing is
 * marked.
 *
 * This is the whole of the promise «Σχεδόν.» makes. Without it the mark cost him the tail: correct
 * A B C D, he lays A C D B, the mark lands on C — and taking C out gave him A D B, so the only way to
 * get B into position 2 was to take D and B out as well and lay three tiles again. On a six-step task
 * one misplacement meant re-laying five. With it: tap B out of the strip, tap B on the board, done.
 */
internal fun insertedAt(chosen: List<Step>, step: Step, slot: Int?): List<Step> {
    val at = (slot ?: chosen.size).coerceIn(0, chosen.size)
    return chosen.subList(0, at) + step + chosen.subList(at, chosen.size)
}

/**
 * The strip with one tile taken out of it, and where the marked slot has got to.
 *
 * The mark survives the tap that answers it — that is what makes the insert above reachable — and it
 * moves up with the tiles when he takes one out from *above* it, so it goes on meaning the same place
 * in the sequence rather than the same index into a list that has changed under it.
 */
internal fun removedFrom(chosen: List<Step>, step: Step, slot: Int?): Pair<List<Step>, Int?> {
    val at = chosen.indexOfFirst { it.text == step.text }
    if (at < 0) return chosen to slot
    val left = chosen.filterIndexed { i, _ -> i != at }
    val mark = when {
        slot == null -> null
        at < slot -> slot - 1
        else -> slot
    }
    return left to mark?.coerceIn(0, left.size)
}

data class StepsState(
    /** The 1..5 he set on the first screen. It decides which tasks the sitting draws from. */
    val difficulty: Int = Difficulty.DEFAULT,
    val index: Int = 0,
    val total: Int = StepsModule.TASKS_PER_SESSION,
    /** Null while the seed loads, and after that the task he is on. */
    val task: StepTask? = null,
    /** The tiles on the board: the steps and, at difficulty 5, one that belongs to another task. */
    val tiles: List<Step> = emptyList(),
    /** What he has put in the strip, in the order he put it. */
    val chosen: List<Step> = emptyList(),
    val stage: StepStage = StepStage.ORDER,
    /**
     * The one slot in the strip that is marked: the first that is not where it belongs, and — once he
     * has taken that tile out — the hole the next tile he taps goes into. Null when nothing is marked.
     *
     * Two things at once on purpose. It is the correction («this is the place that is wrong») and the
     * insertion point («and this is where the next one lands»), which together are what make moving a
     * single tile two taps instead of re-laying the tail. See [insertedAt] and [removedFrom].
     */
    val wrongAt: Int? = null,
    /** «Σχεδόν.» is on the screen: his last order was not the order. */
    val missed: Boolean = false,
    /** How many orders of his were not the order. For the row, and for what the next one counts as. */
    val misses: Int = 0,
    /** Something is being said right now: «Άκου» is off for exactly as long as that lasts. */
    val modelPlaying: Boolean = false,
    /** Recognition is on and this device has it: his telling is checked, gently. */
    val sttOn: Boolean = false,
    /** The settings have been read. Until then the green button is drawn and dead — see [gr.dimitris.app.core.speech.GentleCheck]. */
    val sttResolved: Boolean = false,
    val listening: Boolean = false,
    /** How loud he is, 0..1, while the window is open. Drawn by the listening indicator. */
    val listenLevel: Float = 0f,
    /** The window has closed and the judge has not answered yet. Nothing is asked of him. */
    val thinking: Boolean = false,
    /** Whether «Το είπα!» is his to press. Always true with recognition off. */
    val canConfirm: Boolean = false,
    /** What the recogniser made of his telling, shown under the steps. Null when nothing was heard. */
    val heard: String? = null,
    /** «Δοκίμασε ξανά»: one window disagreed with him, and nothing else has changed. */
    val nudge: Boolean = false,
    /** The full telling, left on the screen after one that did not land, for him to repeat. */
    val whole: String? = null,
    /** The judge's one warm Greek line about his telling, when it had one. */
    val feedback: String? = null,
    val done: Boolean = false,
    /** Said on the screen when a tap made no sound, or when the recogniser could not listen. */
    val error: String? = null,
)

/**
 * One sitting of «Βήματα»: four everyday tasks, each of them ordered and then told.
 *
 * The exercise Dimitris asked for without asking for it. He told Chris that what he is «καμένος» at
 * is a task that needs steps, and this module is that sentence taken literally: the steps of
 * «Φτιάχνω καφέ» on seven tiles, in the wrong order, and nothing else on the screen.
 *
 * Nothing here can end in failure. A wrong order is answered with one nudge and **one** highlighted
 * tile, the tiles stay where they are, and he may try as often as he likes; the only thing a miss
 * spends is the difference between CORRECT and ASSISTED. A telling the phone did not recognise buys
 * him the whole telling, said once, and then «Το είπα!» — the phase-11 window rule, unchanged.
 */
class StepsViewModel(
    private val graph: AppGraph,
    private val sessionId: String?,
    /** One task per item the session handed the module; free practice asks for the full four. */
    count: Int = StepsModule.TASKS_PER_SESSION,
    private val random: Random = Random.Default,
) : ViewModel() {
    private val wanted = count.coerceIn(1, StepsModule.TASKS_PER_SESSION)

    // Seeded with the count, so the title says "1/3" while it loads instead of flashing "1/4".
    private val _state = MutableStateFlow(StepsState(total = wanted))
    val state: StateFlow<StepsState> = _state.asStateFlow()

    private var tasks: List<StepTask> = emptyList()

    /** When the exercise he is on started. Restarted at each stage: two rows, two clocks. */
    private var startedAt = now()

    /** The seed read. Cancelled on the way out and by [reload]. */
    private var loadJob: Job? = null

    /** Whatever this screen is saying. One utterance at a time, like every other module. */
    private var speakJob: Job? = null
    private var speakToken = 0

    /** The open recognition window, so leaving or moving on can close it. */
    private var listenJob: Job? = null

    /**
     * The attempt write of the exercise just finished. It runs on the app scope, so the end of the
     * session and the back arrow both join it first: the session counts rows, and a row still in
     * flight is not one.
     */
    private var lastWrite: Job? = null

    /** Set the moment the sitting starts winding down, so two taps cannot end it twice. */
    private var ending = false

    /**
     * True from the moment an exercise is finished until the next one starts. It carries both guards:
     * one attempt per exercise, and one advance per finished exercise.
     */
    private var finishing = false

    /** Whether the judge will really be asked. Read once, when the sitting is planned. */
    private var judged = false

    /** The check for the task he is telling, or null while he is still ordering. One per task. */
    private var telling: TellCheck? = null

    /** What the judge said about the telling just finished, for the attempt row. */
    private var lastVerdict: Map<String, Any?> = emptyMap()

    /** How many times he asked to hear the telling. It is the answer, so the row has to say so. */
    private var listens = 0

    /** The telling has been said to him on this task, whether he asked for it or not. */
    private var helped = false

    /**
     * True once a window failed because the phone could not listen. It only ever opens the confirm,
     * never closes it: a recogniser that broke once must not be able to take «Το είπα!» away again.
     */
    private var recogniserBroke = false

    /** Which recogniser failures have been written down. One row per class per sitting, not per window. */
    private val reported = mutableSetOf<Recognition.ErrorClass>()

    init {
        load()
        // A new screen is a new run: the judge writes one row per failure class per run, and a sitting
        // on an offline phone must not fill «Σφάλματα» with one row per task.
        graph.judge.newRun()
        // Only while a window is open: the bar belongs to the microphone, and nothing else draws it.
        // Here rather than in [load], which [reload] runs again: two collectors would draw one voice.
        viewModelScope.launch {
            graph.stt.level.collect { l -> _state.update { if (it.listening) it.copy(listenLevel = l) else it } }
        }
    }

    /** He moved the dots. The sitting is rebuilt from the tasks the new dot admits. */
    fun reload() {
        if (ending || finishing) return
        loadJob?.cancel()
        stopRecogniser()
        graph.voice.quiet()
        load()
    }

    private fun load() {
        loadJob = viewModelScope.launch {
            val difficulty = runCatching { graph.settings.difficulty(ModuleId.STEPS).first() }
                .getOrElse { graph.errors.record("steps difficulty read", it); Difficulty.DEFAULT }
            val seed = StepTasks.load(graph.app.assets)
            // Before the board is built, because it decides what the telling is judged against.
            // `available()` opens the encrypted key file, so it is taken here and not per task.
            judged = runCatching { graph.judge.available() }
                .onFailure { graph.errors.record(TellCheck.WHERE, it) }
                .getOrDefault(false)
            val on = runCatching {
                graph.settings.sttEnabled.first() && withContext(Dispatchers.Default) { graph.stt.isAvailable }
            }.getOrElse { graph.errors.record("steps stt read", it); false }
            tasks = seed.plan(wanted, difficulty, random)
            listens = 0
            helped = false
            recogniserBroke = false
            val first = tasks.firstOrNull()
            telling = null
            lastVerdict = emptyMap()
            // Not before the settings read and the seed: their wait is not his thinking time.
            startedAt = now()
            _state.value = StepsState(
                difficulty = difficulty,
                // What was really built, not what was asked for.
                total = tasks.size,
                task = first,
                tiles = first?.board(random).orEmpty(),
                sttOn = on,
                sttResolved = true,
                // With recognition off nothing about the telling stage changes: «Το είπα!» from the start.
                canConfirm = !on,
                // A seed that could not be read is no tasks, and the screen says so instead of holding him.
                done = first == null,
            )
        }
    }

    // ------------------------------------------------------------------ stage 1: the order

    /**
     * One tile into the strip — at the marked slot when there is one, and at the end when there is
     * not. The last place it can be wrong is «Έτοιμο», so nothing is judged here.
     *
     * Filling the marked slot answers the mark, so the mark goes: the next tile after it is another
     * tile at the end, not a second insert into the same place.
     */
    fun tap(step: Step) {
        val s = _state.value
        if (finishing || ending) return
        if (s.stage != StepStage.ORDER) return
        val task = s.task ?: return
        if (s.chosen.any { it.text == step.text }) return
        if (s.tiles.none { it.text == step.text }) return
        // Never more tiles than the task has steps: the strip is what is checked, and a seventh tile
        // in it could only ever be the distractor with every real step already down.
        if (s.chosen.size >= task.steps.size) return
        _state.update {
            it.copy(chosen = insertedAt(it.chosen, step, it.wrongAt), wrongAt = null, missed = false)
        }
    }

    /**
     * One tile back out of the strip. Tapping it in the strip is how, rather than a fourth button
     * under his thumb: the control sits next to the thing it is about — the move «SQL» made for its
     * own strip and «Προτάσεις» for «Το έγραψα».
     *
     * The mark stays through this, which is the point of it: taking the wrong tile out is the first
     * half of moving one tile, and [tap] is the second. See [removedFrom].
     */
    fun untap(step: Step) {
        if (finishing || ending) return
        if (_state.value.stage != StepStage.ORDER) return
        _state.update {
            val (left, mark) = removedFrom(it.chosen, step, it.wrongAt)
            it.copy(chosen = left, wrongAt = mark, missed = mark != null)
        }
    }

    /**
     * «Έτοιμο»: the steps as he has put them.
     *
     * Right is the end of stage 1 and the beginning of the telling. Wrong is a nudge, the first tile
     * that is out of place marked, and another go — as many as he likes. The strip is **not** handed
     * back: he has one thing to change, and a board that emptied itself would make him lay all six
     * again to fix one.
     */
    fun submit() {
        val s = _state.value
        val task = s.task ?: return
        if (finishing || ending) return
        if (s.stage != StepStage.ORDER) return
        if (s.chosen.size != task.steps.size) return
        val wrong = firstWrongStep(s.chosen.map { it.text }, task.groups, task::groupOfTile)
        if (wrong == null) {
            graph.feedback.success()
            record(task, stage = StepStage.ORDER, steps = s.chosen.map { it.text }, firstTry = s.misses == 0)
            toTelling(task, s.chosen)
        } else {
            graph.feedback.nudge()
            _state.update { it.copy(wrongAt = wrong, missed = true, misses = it.misses + 1) }
        }
    }

    /**
     * The strip stays as it is and the question changes: now tell them.
     *
     * [helpedAlready] is true when the strip he is about to read from is the answer rather than his
     * own work — «Παράλειψη» on the ordering — so the telling row cannot come out as work he did
     * alone.
     */
    private fun toTelling(task: StepTask, strip: List<Step>, helpedAlready: Boolean = false) {
        // The ordering's row has landed; the telling is the next exercise and needs the guard open.
        finishing = false
        telling = TellCheck(
            judged = { judged },
            difficulty = { _state.value.difficulty },
            askJudge = { ask -> graph.judge.judge(ask) },
            record = { where, e -> graph.errors.record(where, e) },
        )
        listens = 0
        helped = helpedAlready
        lastVerdict = emptyMap()
        startedAt = now()
        _state.update {
            it.copy(
                stage = StepStage.TELL, chosen = strip, wrongAt = null, missed = false,
                heard = null, nudge = false, whole = null, feedback = null, error = null,
                canConfirm = !it.sttOn || recogniserBroke,
            )
        }
    }

    // ----------------------------------------------------------------- stage 2: the telling

    /**
     * «Μίλα». The window opens and waits for him — no stopwatch — and what comes back goes to
     * [TellCheck].
     */
    fun listen() {
        val s = _state.value
        if (!s.sttOn || s.listening || s.thinking || finishing || ending) return
        if (s.stage != StepStage.TELL) return
        // The microphone is about to open: whatever was being said stops here, or the recogniser hears
        // the phone's own telling and agrees with it.
        silence()
        _state.update { it.copy(listening = true, listenLevel = 0f, heard = null, nudge = false, error = null) }
        listenJob = viewModelScope.launch {
            val heard = graph.stt.listen()
            // The take is his own voice from that same window. There is nothing in this module to
            // attach it to — a step is not an `Item` — so it is deleted rather than left on disk with
            // nothing pointing at it.
            heard.take?.file?.delete()
            heard.fold(
                onSuccess = { t -> weigh(t.text.takeIf { it.isNotBlank() }) },
                onFailure = { e -> recogniserFailed(e) },
            )
        }
    }

    /** «Στοπ»: the window closes now, and what it had heard still comes back through [listen]. */
    fun stopListening() {
        if (_state.value.listening) graph.stt.stop()
    }

    /**
     * What the phone made of his telling, and what the task does about it.
     *
     * The rules are [TellCheck]'s; this is what the screen does with them. A telling that counts ends
     * the task — being made to press a button to agree with the phone is one step too many for a man
     * who has just said thirty words. One that did not buys the whole telling, shown and said once, so
     * that his next «Μίλα» has something to repeat rather than a wall.
     */
    private suspend fun weigh(text: String?) {
        val s = _state.value
        val task = s.task ?: return
        val check = telling ?: return
        // The window has closed; the judge may take a moment. Nothing moves on the screen but the
        // green button, which greys rather than opening a second window over a decided telling.
        val asking = judged && text != null
        if (asking) _state.update { it.copy(listening = false, listenLevel = 0f, thinking = true) }
        val told = check.weigh(text, task)
        lastVerdict = told.judge
        _state.update {
            it.copy(
                listening = false, listenLevel = 0f, thinking = false,
                heard = told.heard, nudge = told.nudging,
                canConfirm = told.canConfirm || recogniserBroke,
                feedback = told.feedback,
                // The telling he was given stays on the screen until the task moves on, so a second
                // miss does not take away the sentence he was about to repeat.
                whole = told.whole ?: it.whole,
                error = if (told.heard == null) HEARD_NOTHING else null,
            )
        }
        if (told.accepted) {
            graph.feedback.success()
            finish(task, check)
            return
        }
        graph.feedback.nudge()
        // Said once, and counted as the help it is: the phone has just read him the answer before his
        // next go, so the row cannot claim he found it himself.
        told.whole?.let { whole ->
            helped = true
            speaking { report(graph.speaker.speakText(whole)) }
        }
    }

    /** A window with no words at all. His silence is answered gently; see [gr.dimitris.app.core.speech.GentleCheck]. */
    private suspend fun recogniserFailed(e: Throwable) {
        if (e !is SpeechFailure.NotWorking) {
            weigh(null)
            return
        }
        // A phone that could not listen is the *phone's* failure, and it costs him nothing: no try is
        // spent, the confirm opens at once and stays open, and the line names the real trouble.
        val klass = Recognition.classOf(e.code)
        if (reported.add(klass)) graph.errors.record("steps listen", e)
        recogniserBroke = true
        _state.update {
            it.copy(
                listening = false, listenLevel = 0f, thinking = false, heard = null, nudge = false,
                canConfirm = true, error = klass.line,
            )
        }
    }

    /** «Το είπα!»: he says he told them, and that is the end of it. Assisted work if he was helped. */
    fun confirm() {
        val s = _state.value
        val task = s.task ?: return
        val check = telling ?: return
        if (finishing || ending || s.listening || s.thinking) return
        if (s.stage != StepStage.TELL) return
        if (!s.canConfirm) return
        graph.feedback.success()
        finish(task, check)
    }

    /** The telling is done, whichever way. The row lands and the next task arrives. */
    private fun finish(task: StepTask, check: TellCheck) {
        record(
            task, stage = StepStage.TELL, steps = task.order,
            // A telling he had read to him — by «Άκου» or after a miss — is assisted work, on the same
            // footing as an order he rebuilt. Nothing on the screen calls it anything.
            firstTry = !helped && listens == 0, tries = check.tries,
        )
        advance()
    }

    // ------------------------------------------------------------------------ both stages

    /**
     * «Άκου». In stage 1 it reads the task and the strip **as it stands** — his own order, said back
     * to him, which is how a man who reads slowly checks his own work without being told the answer.
     * In stage 2 it reads the telling, which *is* the answer, and the row says he heard it.
     */
    fun listenModel() {
        val s = _state.value
        val task = s.task ?: return
        if (ending || s.modelPlaying || s.listening) return
        val text = when (s.stage) {
            StepStage.ORDER -> listOf(task.title).plus(s.chosen.map { it.text }).joinToString(". ") + "."
            StepStage.TELL -> {
                listens++
                task.telling
            }
        }
        speaking { report(graph.speaker.speakText(text)) }
    }

    /**
     * «Παράλειψη». In stage 1 it passes on the ordering and goes straight to the telling with the
     * steps in the right order on the screen — he has said he cannot order them, and the answer is
     * then the least this app can do with the question. In stage 2 it passes on the task.
     */
    fun skip() {
        val s = _state.value
        val task = s.task ?: return
        // One skip per exercise: the button is still there for a frame, and a second tap would pass on
        // the exercise that has not been shown yet.
        if (finishing || ending || s.listening || s.thinking) return
        graph.feedback.nudge()
        when (s.stage) {
            StepStage.ORDER -> {
                record(task, stage = StepStage.ORDER, steps = s.chosen.map { it.text }, firstTry = false, skipped = true)
                // The strip is set to the answer, because the telling stage is about an order he can
                // read — and that is help, which the telling row has to carry.
                toTelling(task, task.steps, helpedAlready = true)
            }
            StepStage.TELL -> {
                record(task, stage = StepStage.TELL, steps = task.order, firstTry = false, skipped = true, tries = telling?.tries ?: 0)
                advance()
            }
        }
    }

    private fun advance() {
        val s = _state.value
        // Nothing to advance past: still loading, or the sitting is already over.
        if (s.task == null || ending) return
        finishing = false
        val i = s.index + 1
        if (i >= tasks.size) { finishSitting(); return }
        val task = tasks[i]
        telling = null
        lastVerdict = emptyMap()
        listens = 0
        helped = false
        startedAt = now()
        // The listens belonged to the task being left, and so does whatever was being said: he is
        // ready for the next board the instant the mark appears.
        silence()
        stopRecogniser()
        _state.update {
            it.copy(
                index = i, task = task, tiles = task.board(random), chosen = emptyList(),
                stage = StepStage.ORDER, wrongAt = null, missed = false, misses = 0,
                heard = null, nudge = false, whole = null, feedback = null, error = null,
                canConfirm = !it.sttOn || recogniserBroke,
            )
        }
    }

    private fun finishSitting() {
        if (ending) return
        ending = true
        // The session counts attempt rows as soon as it is told the module is done, so the last row
        // has to be in the database before "done" ever reaches the screen.
        val write = lastWrite
        silence()
        stopRecogniser()
        viewModelScope.launch {
            write?.join()
            _state.update { it.copy(done = true) }
        }
    }

    /**
     * The user pressed back. Whatever was being said stops here, and [then] waits for the last row:
     * the session counts rows the moment it is told, so leaving before one lands would lose the
     * exercise he had just done.
     */
    fun leave(then: () -> Unit) {
        loadJob?.cancel()
        silence()
        stopRecogniser()
        val write = lastWrite
        viewModelScope.launch { write?.join(); then() }
    }

    /** The microphone was refused: say so instead of a button that does nothing. */
    fun micDenied() {
        _state.update { it.copy(listening = false, listenLevel = 0f, error = MIC_DENIED) }
    }

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
     * bumped so the cancelled job's `finally` cannot re-open the button for a task that is gone.
     */
    private fun silence() {
        speakJob?.cancel()
        speakToken++
        graph.voice.quiet()
        _state.update { it.copy(modelPlaying = false) }
    }

    /** Closes any open window and stops claiming to be listening. */
    private fun stopRecogniser() {
        listenJob?.cancel()
        listenJob = null
        // Only a window that is actually open is closed: «Στοπ» is his word, and a stop sent for a
        // window nobody opened would be one more thing happening that he never asked for.
        if (_state.value.listening) graph.stt.stop()
        // The job that was cancelled may have been waiting on the judge: nothing is coming back, so
        // the button he is looking at may not stay greyed for a verdict that will never arrive.
        _state.update { it.copy(listening = false, listenLevel = 0f, thinking = false) }
    }

    /**
     * Records the outcome of one spoken line. Silence is the one failure Dimitris cannot diagnose
     * himself, so it is said on the screen and cleared by the next sound that comes out.
     */
    private fun report(result: Result<*>) = result.fold(
        onSuccess = { _state.update { if (it.error == SPEECH_FAILED) it.copy(error = null) else it } },
        onFailure = { e -> graph.errors.record("steps speak", e); _state.update { it.copy(error = SPEECH_FAILED) } },
    )

    private fun record(
        task: StepTask,
        stage: StepStage,
        steps: List<String>,
        firstTry: Boolean,
        skipped: Boolean = false,
        tries: Int = _state.value.misses,
    ) {
        if (finishing) return
        finishing = true
        val outcome = stepsOutcome(firstTry, skipped)
        // Read eagerly: the clock is restarted the moment the next exercise arrives.
        val began = startedAt
        val detail = stepsDetail(
            task = task,
            steps = steps,
            tries = tries,
            ms = now() - began,
            judge = if (stage == StepStage.TELL) lastVerdict else emptyMap(),
        )
        val itemId = if (stage == StepStage.ORDER) "$ORDER_ITEM${task.id}" else "$TELL_ITEM${task.id}"
        // Only the telling has anything to say on the word coach's 0–4 scale, and only when the phone
        // read it out to him before he said it.
        val cue = if (stage == StepStage.TELL && (helped || listens > 0)) CueLadder.LISTENED else null
        // Chained, because the app scope runs on a pool with no ordering: whoever joins the last write
        // must be joining every write, or a row can land after the session has counted.
        val prev = lastWrite
        // The app scope, not this screen's: pressing back must not lose the exercise he just did.
        lastWrite = graph.scope.launch {
            prev?.join()
            runCatching {
                graph.db.attempts().insert(
                    Attempt(
                        itemId = itemId, module = ModuleId.STEPS, sessionId = sessionId,
                        startedAt = began, durationMs = now() - began, outcome = outcome,
                        cueLevel = cue, detail = detail,
                    )
                )
            }.onFailure { graph.errors.record("steps record", it) }
        }
    }

    companion object {
        /** Said on the screen when a tap made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

        /** Recognition came back with nothing. Never a verdict on him: the invitation stays open. */
        const val HEARD_NOTHING = "Δεν άκουσα. Δοκίμασε ξανά."

        /** The microphone was refused. A fact about the phone, not about him. */
        const val MIC_DENIED = "Δεν έχω άδεια για το μικρόφωνο."

        /**
         * What an order that was not the order is answered with, in writing as well as by the buzz.
         *
         * «Σχεδόν.» — «Προτάσεις»' own word — and not «λάθος» or «όχι»: spec §12 does not allow a
         * retried turn to be called wrong, and the tile that is marked says the rest.
         */
        const val ALMOST = "Σχεδόν."

        /** What stage 2 asks, said once above the steps. */
        const val TELL_THEM = "Πες τα βήματα"

        /** What stage 1 asks. The tiles are in the wrong order and the strip is empty. */
        const val PUT_IN_ORDER = "Βάλε τα βήματα με τη σειρά."

        /** The ordering row's id, per task: `steps:order:coffee`. */
        const val ORDER_ITEM = "steps:order:"

        /** The telling row's id, per task: `steps:tell:coffee`. */
        const val TELL_ITEM = "steps:tell:"
    }
}
