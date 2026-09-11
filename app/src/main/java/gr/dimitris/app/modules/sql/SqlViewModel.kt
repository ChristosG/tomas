package gr.dimitris.app.modules.sql

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.caregiver.insights.AdviceSummary
import gr.dimitris.app.core.data.Adapt
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.scheduler.LevelProgression
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * One finished puzzle, as the row will carry it.
 *
 * Free of the ViewModel so that what is written down can be argued with in a unit test rather than on
 * a phone: see `SqlViewModelTest`. [query] is what *he* put down — the tiles in the order he laid
 * them, the option he tapped, or the query he typed — because a year from now "he got it wrong"
 * without it is unreadable, and the whole of `docs/ADAPTATION.md` is about keeping the thing that
 * makes a row answerable.
 *
 * [tables] says which of the two worlds the question was about. It is the one column that can tell a
 * later reader whether he does better on questions about his own words than on the textbook's —
 * which is the first thing anybody would want to know about this module.
 */
internal fun sqlDetail(
    puzzle: SqlPuzzle,
    query: String,
    ok: Boolean,
    ms: Long,
    tries: Int,
): String = Adapt.detail {
    put("kind", puzzle.kind)
    put("level", puzzle.level)
    put("query", query)
    put("ok", ok)
    put("ms", ms)
    put("tries", tries)
    put("tables", puzzle.set)
}

/**
 * What one finished puzzle counts as.
 *
 * Three outcomes and no fourth: he found it himself ([Outcome.CORRECT]), he found it after a nudge or
 * with the answer on the screen ([Outcome.ASSISTED]), or he passed on it ([Outcome.SKIPPED]). Only
 * the first moves the level up, and all three are evidence — a level he skips his way through has to
 * be steppable back down.
 *
 * A **refused** query is none of the three, and that is the one judgement in this module worth
 * arguing about: `SELECT nmae FROM users` is a typo, not a wrong answer. It is shown, with SQLite's
 * own message under it, and costs him nothing — not the mark, and not a step towards the reveal. He
 * was a programmer; fixing a typo is the part of this he can still do, and charging him for it would
 * make the module a spelling test.
 */
internal fun sqlOutcome(firstTry: Boolean, skipped: Boolean): Outcome = when {
    skipped -> Outcome.SKIPPED
    firstTry -> Outcome.CORRECT
    else -> Outcome.ASSISTED
}

/**
 * Whether [answer] answers [puzzle] — the one judgement, for all four boards.
 *
 * [got] and [wanted] are the two result sets a typed board compares, both of them straight out of
 * real SQLite ([SqlRunner]) and never out of the pure evaluator: what he typed is read by the
 * database, because the database is the only honest reader of something a person wrote. The
 * comparison is order-insensitive unless the query he was asked for had an `ORDER BY` in it, in which
 * case the order *is* the answer.
 */
internal fun sqlAccepts(puzzle: SqlPuzzle, answer: String, got: SqlResult?, wanted: SqlResult?): Boolean =
    when (puzzle.kind) {
        SqlPuzzleKind.ORDER -> answer == puzzle.orderedAnswer
        SqlPuzzleKind.PICK, SqlPuzzleKind.KEYWORD -> answer == puzzle.answer
        SqlPuzzleKind.WRITE -> got != null && wanted != null &&
            SqlResult.same(got, wanted, puzzle.target.ordered)
    }

data class SqlState(
    val level: Int = SqlPuzzles.MIN_LEVEL,
    /** The 1..5 he set on the first screen. It bounds which levels the progression may reach. */
    val difficulty: Int = Difficulty.DEFAULT,
    val index: Int = 0,
    val total: Int = SqlModule.PUZZLES_PER_SESSION,
    /** Null while the tables load, and after that the puzzle he is on. */
    val puzzle: SqlPuzzle? = null,
    /** The tables this puzzle is about, so their shape is on the screen while he writes. */
    val shown: List<SqlTable> = emptyList(),
    /** The tiles he has laid down, in the order he laid them. */
    val chosen: List<String> = emptyList(),
    /** What he has written on a typed board, as it stands. */
    val typed: String = "",
    /** The answer's own result, on the two boards where it *is* the question. */
    val wanted: SqlResult? = null,
    /** What his own query returned. Null until he has run one. */
    val got: SqlResult? = null,
    /** Null until the puzzle is finished: there is no verdict on half an answer. */
    val correct: Boolean? = null,
    val wrongTries: Int = 0,
    /** Two misses: the answer is on the screen and the only thing left to do is «Επόμενο». */
    val revealed: Boolean = false,
    /** His query is inside SQLite right now: «Έτοιμο» is off for exactly as long as that lasts. */
    val running: Boolean = false,
    /** The question is being read out: «Άκου» is off for exactly as long as that lasts. */
    val speaking: Boolean = false,
    val done: Boolean = false,
    val levelChanged: Int? = null,
    /** «Η ερώτηση δεν τρέχει.» — the Greek line, with SQLite's English in [refusalDetail]. */
    val refusal: String? = null,
    val refusalDetail: String? = null,
    /** Said on the screen when a tap made no sound, or when the tables could not be read. */
    val error: String? = null,
)

/**
 * One sitting of SQL.
 *
 * Chris asked for a beginner's SQL tile: Dimitris was a programmer, his father runs a software
 * company, and he still does very basic SQL exercises. So this is a module and not a toy — it
 * rotates into the daily sitting, it writes one attempt row per puzzle, and its difficulty is the
 * same row of five dots every other module has.
 *
 * What it is *not* is a place where anything can go wrong. Every query runs against a fresh in-memory
 * database of four tables ([SqlRunner]), a statement that is not a single `SELECT` is refused in
 * Greek before SQLite sees it, and a syntax error is a line on the screen rather than a crash or a
 * mark against him. Nothing he can type here can break anything, and the one thing he can do with
 * it — ask a question about his own practice and get an answer — is the point.
 */
class SqlViewModel(
    private val graph: AppGraph,
    private val sessionId: String?,
    /** One puzzle per item the session handed the module; free practice asks for the full six. */
    count: Int = SqlModule.PUZZLES_PER_SESSION,
    private val random: Random = Random.Default,
) : ViewModel() {
    private val wanted = count.coerceIn(1, SqlModule.PUZZLES_PER_SESSION)

    // Seeded with the count, so the title says "1/3" while it loads instead of flashing "1/6".
    private val _state = MutableStateFlow(SqlState(total = wanted))
    val state: StateFlow<SqlState> = _state.asStateFlow()

    private var puzzles: List<SqlPuzzle> = emptyList()
    private var tables: SqlTables? = null
    private var runner: SqlRunner? = null
    private var startedAt = now()
    private val results = mutableListOf<Boolean>()

    /** The tables read and the database built. Cancelled on the way out and by [reload]. */
    private var loadJob: Job? = null

    /** The run of the current board's *target*, so a typed answer can wait for it. See [targetResult]. */
    private var showJob: Job? = null

    /** Whatever this screen is saying. One utterance at a time, like every other module. */
    private var speakJob: Job? = null
    private var speakToken = 0

    /**
     * The attempt write of the puzzle just finished. It runs on the app scope, so the end of the
     * session and the back arrow both join it first: the session counts rows, and a row still in
     * flight is not one.
     */
    private var lastWrite: Job? = null

    /** Set the moment the sitting starts winding down, so two taps cannot end it twice. */
    private var ending = false

    /**
     * True from the moment a puzzle is finished or skipped until the next one starts. It carries both
     * guards: one attempt per puzzle, and one advance per finished puzzle.
     */
    private var finishing = false

    /** How many goes he has had at this puzzle, a refused query included. For the row, not the mark. */
    private var tries = 0

    /**
     * The wrong option he tapped last on a choosing board, so that tapping it **again** is not a second
     * go at the puzzle.
     *
     * Two misses reveal the answer ([WRONG_TRIES_BEFORE_REVEAL]), and a tile under a thumb that is not
     * always steady is tapped twice more often than anybody designing this would like: a double tap on
     * one wrong option used to spend both goes and show him the answer to a question he had answered
     * once. A different wrong option is a real second go and still reveals.
     */
    private var lastWrong: String? = null

    /** His query inside SQLite, and the board's own target run. Cancelled before the runner is closed. */
    private var runJob: Job? = null

    init { load() }

    /** He moved the dots. The sitting is rebuilt at the hardest level the new dot admits. */
    fun reload() {
        if (ending || finishing) return
        loadJob?.cancel()
        // Whatever of his is inside SQLite belongs to the sitting being thrown away: the query he
        // handed in and the board's own target. Cancelled here and **joined** in [load] before the
        // database is closed — a close under a reading thread is not a crash (every throw comes back
        // as a Greek refusal) but it is a race nobody should have to think about twice, and since the
        // watchdog pulls the signal on cancellation the join is as short as an abort.
        runJob?.cancel()
        showJob?.cancel()
        graph.voice.quiet()
        load()
    }

    private fun load() {
        // Read before the coroutine starts, because [load] assigns both of them itself.
        val reading = listOfNotNull(runJob, showJob)
        loadJob = viewModelScope.launch {
            reading.forEach { it.join() }
            val stored = runCatching { graph.settings.sqlLevel.first() }
                .getOrElse { graph.errors.record("sql level read", it); SqlPuzzles.MIN_LEVEL }
            val difficulty = runCatching { graph.settings.difficulty(ModuleId.SQL).first() }
                .getOrElse { graph.errors.record("sql difficulty read", it); Difficulty.DEFAULT }
            // The sitting runs at a level inside what the dots admit, and the jump happens here,
            // before any work — so it is his own tap that caused it and the rows record it. See
            // [Difficulty.levelAtLoad].
            val level = Difficulty.levelAtLoad(stored, Difficulty.sql(difficulty))
            if (level != stored) {
                runCatching { graph.settings.setSqlLevel(level) }
                    .onFailure { graph.errors.record("sql level clamp", it) }
            }
            val read = try {
                SqlTables.load(
                    items = graph.db.items(),
                    attempts = graph.db.attempts(),
                    sessions = graph.db.sessions(),
                    names = AdviceSummary.MODULE_NAMES,
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                graph.errors.record("sql tables", e)
                // The textbook alone is still a whole sitting: his own two tables are the half of
                // this module that needs a database read, and a Room failure must not be a dead tile.
                SqlTables(SqlTables.textbook())
            }
            tables = read
            runner?.close()
            val open = SqlRunner(read)
            // Not `runCatching`: it would swallow the cancellation of a `load()` that [reload] has
            // just replaced, and the database built for the sitting nobody is going to see would be
            // left open with nothing holding it.
            val ready = try {
                open.open(); true
            } catch (ce: CancellationException) {
                open.close(); throw ce
            } catch (e: Throwable) {
                graph.errors.record("sql open", e); false
            }
            if (!isActive) { open.close(); return@launch }
            runner = open.takeIf { ready }
            results.clear()
            tries = 0
            lastWrong = null
            runJob = null
            puzzles = List(wanted) { SqlPuzzles.generate(level, read, random) }
            val first = puzzles.firstOrNull()
            // Not before the settings read and the database build: their wait is not his thinking time.
            startedAt = now()
            _state.value = SqlState(
                level = level,
                difficulty = difficulty,
                total = puzzles.size,
                puzzle = first,
                shown = shownFor(first, read),
                done = first == null,
                error = if (ready) null else TABLES_FAILED,
            )
            // Held rather than awaited: the board is on the screen now, and the grid arrives on it a
            // moment later. What must not happen is judging his answer before it does — see
            // [targetResult], which is what joins this.
            showJob = first?.let { p -> viewModelScope.launch { show(p) } }
        }
    }

    /** The shape of the tables this puzzle is about, in the order the query names them. */
    private fun shownFor(puzzle: SqlPuzzle?, tables: SqlTables): List<SqlTable> =
        puzzle?.shown?.distinct()?.mapNotNull { tables.table(it) }.orEmpty()

    /**
     * The result the board has to show before he starts, on the two kinds where it *is* the question.
     * Run through real SQLite rather than through the pure evaluator, so what is on the screen is
     * what the database says — the one fact his own answer will be compared against.
     */
    private suspend fun show(puzzle: SqlPuzzle) {
        if (!puzzle.showResult) return
        val outcome = runner?.run(puzzle.target.text) ?: return
        val rows = (outcome as? SqlOutcome.Rows)?.result ?: run {
            // The generator only ever builds a query that runs, so this is a bug and not a user
            // event: it is written down, and the board falls back to having no grid on it.
            graph.errors.record("sql target", IllegalStateException(puzzle.target.text))
            return
        }
        _state.update { if (it.puzzle === puzzle) it.copy(wanted = rows) else it }
    }

    /**
     * The result his own query is about to be judged against — **waited for**, and asked for again if
     * the wait produced nothing.
     *
     * A typed answer is right when it returns what the target returns, so a target that has not
     * arrived is not a reason to call his query wrong. It used to be exactly that: `show()` is
     * launched asynchronously when the board opens, and a man who reads the question and starts
     * typing straight away could hand in a perfectly correct query while it was still in flight —
     * and be told «Ξανά.», and then be shown the answer he had already written.
     *
     * Null only when the database itself could not answer, which is the app's fault and is said as
     * one ([SqlRunner.NOT_READY]) rather than charged to him.
     */
    private suspend fun targetResult(puzzle: SqlPuzzle): SqlResult? {
        if (!puzzle.showResult) return null
        _state.value.wanted?.let { return it }
        showJob?.join()
        _state.value.wanted?.let { return it }
        // Still nothing: ask once more ourselves. The first attempt may have been cancelled by a
        // reload, or lost the race with a board that has since settled.
        show(puzzle)
        return _state.value.takeIf { it.puzzle === puzzle }?.wanted
    }

    /** One tile laid down, in the order he taps them. */
    fun tap(tile: String) {
        val s = _state.value
        val puzzle = s.puzzle ?: return
        if (finishing || ending) return
        if (puzzle.kind != SqlPuzzleKind.ORDER) return
        if (tile in s.chosen) return
        if (tile !in puzzle.tiles) return
        _state.update { it.copy(chosen = it.chosen + tile) }
    }

    /**
     * One tile taken back off. Tapping the tile in the strip is how, rather than a fourth button
     * under his thumb: the control sits next to the thing it is about, which is the escape hatch
     * `docs/UX.md` already allows and the move «Προτάσεις» made for «Το έγραψα».
     */
    fun untap(tile: String) {
        if (finishing || ending) return
        _state.update { it.copy(chosen = it.chosen - tile) }
    }

    /** One keystroke on a typed board. Nothing is judged until he says he is done. */
    fun onTypedChange(text: String) {
        if (finishing || ending) return
        if (_state.value.puzzle?.kind != SqlPuzzleKind.WRITE) return
        _state.update { it.copy(typed = text) }
    }

    /** One of the three options on a [SqlPuzzleKind.PICK] or [SqlPuzzleKind.KEYWORD] board. */
    fun choose(option: String) {
        val s = _state.value
        val puzzle = s.puzzle ?: return
        if (finishing || ending) return
        if (puzzle.kind != SqlPuzzleKind.PICK && puzzle.kind != SqlPuzzleKind.KEYWORD) return
        // The same wrong option twice is one answer, not two. See [lastWrong].
        if (option == lastWrong) return
        tries++
        val accepted = sqlAccepts(puzzle, option, got = null, wanted = null)
        lastWrong = option.takeUnless { accepted }
        settle(puzzle, option, accepted)
    }

    /**
     * «Έτοιμο»: the tiles as he has laid them, or the query he has written.
     *
     * On a typed board the query goes to SQLite and the result set is compared with the one on the
     * screen. A statement it will not run is not an answer at all — it is said in Greek, with the
     * database's own message under it, and costs him nothing. See [sqlOutcome].
     */
    fun submit() {
        val s = _state.value
        val puzzle = s.puzzle ?: return
        if (finishing || ending || s.running) return
        when (puzzle.kind) {
            SqlPuzzleKind.ORDER -> {
                if (s.chosen.size != puzzle.tiles.size) return
                tries++
                settle(puzzle, s.chosen.joinToString(" "), sqlAccepts(puzzle, s.chosen.joinToString(" "), null, null))
            }
            SqlPuzzleKind.WRITE -> {
                val typed = s.typed.trim()
                if (typed.isEmpty()) return
                runTyped(puzzle, typed)
            }
            else -> Unit
        }
    }

    /**
     * His query, read by SQLite and compared with what the board asked for.
     *
     * Two guards, and both of them are about a query outliving the board it was written on. The
     * target is **waited for** before anything is judged ([targetResult]), so a correct query handed
     * in before the board had finished drawing is not called wrong. And the board is checked again
     * when SQLite comes back: he can press «Έτοιμο» and then «Παράλειψη» in the same second, and
     * without the check the answer to the board he skipped would settle onto the board that replaced
     * it — a second attempt row for the first puzzle, a second entry in [results], and a verdict
     * painted on a question he has not answered.
     */
    private fun runTyped(puzzle: SqlPuzzle, typed: String) {
        _state.update { it.copy(running = true, refusal = null, refusalDetail = null) }
        runJob = viewModelScope.launch {
            val target = targetResult(puzzle)
            if (_state.value.puzzle !== puzzle) return@launch
            if (puzzle.showResult && target == null) {
                // The app cannot say what the answer is, so it cannot say his is wrong. It costs him
                // neither the mark nor a go: `tries` is not touched and nothing is recorded.
                graph.feedback.nudge()
                _state.update { it.copy(running = false, got = null, refusal = SqlRunner.NOT_READY) }
                return@launch
            }
            tries++
            val outcome = runner?.run(typed) ?: SqlOutcome.Refused(SqlRunner.NOT_READY)
            // The board may have moved on while SQLite was reading — see the KDoc above.
            if (_state.value.puzzle !== puzzle) return@launch
            when (outcome) {
                is SqlOutcome.Refused -> {
                    // Not a wrong answer: the keyboard stays, nothing is spent, and the mark is
                    // still his. The one thing that changes is that the screen says what SQLite said.
                    graph.feedback.nudge()
                    _state.update {
                        it.copy(running = false, got = null, refusal = outcome.greek, refusalDetail = outcome.english)
                    }
                }
                is SqlOutcome.Rows -> {
                    val ok = sqlAccepts(puzzle, typed, outcome.result, target)
                    _state.update { it.copy(running = false, got = outcome.result, refusal = null, refusalDetail = null) }
                    settle(puzzle, typed, ok)
                }
            }
        }
    }

    /**
     * One answer, judged. Right is the end of the puzzle; wrong is a nudge, and the second wrong is
     * the answer on the screen — the rule «Αριθμοί» already uses, because tapping on blind past two
     * misses teaches nothing and being stuck is exactly what the progression has to notice.
     */
    private fun settle(puzzle: SqlPuzzle, answer: String, accepted: Boolean) {
        val s = _state.value
        if (accepted) {
            graph.feedback.success()
            finishing = true
            _state.update { it.copy(correct = true) }
            record(puzzle, answer, firstTry = s.wrongTries == 0 && !s.revealed)
        } else if (s.wrongTries + 1 >= WRONG_TRIES_BEFORE_REVEAL) {
            graph.feedback.nudge()
            finishing = true
            _state.update { it.copy(correct = false, wrongTries = it.wrongTries + 1, revealed = true) }
            record(puzzle, answer, firstTry = false)
        } else {
            graph.feedback.nudge()
            _state.update {
                it.copy(
                    correct = false, wrongTries = it.wrongTries + 1,
                    // The tiles come back in one gesture rather than one tap each: the board he got
                    // wrong is not a board to dismantle, it is a board to build again. «Προτάσεις»
                    // hands its cards back the same way. [untap] is still there for a tile he changes
                    // his mind about while he is laying them.
                    chosen = if (puzzle.kind == SqlPuzzleKind.ORDER) emptyList() else it.chosen,
                )
            }
        }
    }

    /**
     * «Άκου»: the Greek question, read out. Never the answer — the question is the only thing on this
     * screen that is words, and a man who reads slowly should not have to read it twice to be sure.
     * It costs nothing, because there is nothing in it to give away.
     */
    fun listen() {
        val s = _state.value
        val puzzle = s.puzzle ?: return
        if (ending || s.speaking) return
        speaking { report(graph.speaker.speakText(puzzle.question)) }
    }

    private fun speaking(block: suspend () -> Unit) {
        speakJob?.cancel()
        graph.voice.quiet()
        val token = ++speakToken
        _state.update { it.copy(speaking = true) }
        speakJob = viewModelScope.launch {
            try {
                block()
            } finally {
                if (speakToken == token) _state.update { it.copy(speaking = false) }
            }
        }
    }

    private fun silence() {
        speakJob?.cancel()
        speakToken++
        graph.voice.quiet()
        _state.update { it.copy(speaking = false) }
    }

    private fun report(result: Result<*>) = result.fold(
        onSuccess = { _state.update { if (it.error == SPEECH_FAILED) it.copy(error = null) else it } },
        onFailure = { e -> graph.errors.record("sql speak", e); _state.update { it.copy(error = SPEECH_FAILED) } },
    )

    fun skip() {
        val s = _state.value
        val puzzle = s.puzzle ?: return
        // One skip per puzzle: the button is still there for a frame, and a second tap would pass on
        // the puzzle that has not been shown yet.
        //
        // And never while a query of his is inside SQLite: [runTyped] refuses to settle onto a board
        // that has changed under it, so skipping here would simply throw his answer away — better to
        // make him wait the two seconds the runner is allowed and let the answer land.
        if (finishing || ending || s.running) return
        finishing = true
        graph.feedback.nudge()
        // Whatever he had put down when he passed on it: the tiles on an ordering board, half a
        // query on a typed one, nothing at all where he had not started.
        val left = when (puzzle.kind) {
            SqlPuzzleKind.WRITE -> s.typed.trim()
            SqlPuzzleKind.ORDER -> s.chosen.joinToString(" ")
            else -> ""
        }
        record(puzzle, left, firstTry = false, skipped = true)
        advance()
    }

    fun next() {
        // Only a finished puzzle moves on. A second tap on «Επόμενο» — the button is still there for
        // a frame after the first — would otherwise skip the puzzle that just arrived, and leave the
        // session counting one he was never shown.
        if (!finishing) return
        advance()
    }

    private fun advance() {
        val s = _state.value
        // Nothing to advance past: still loading, or the sitting is already over.
        if (s.puzzle == null) return
        finishing = false
        val i = s.index + 1
        if (i >= puzzles.size) { finishSitting(); return }
        val puzzle = puzzles[i]
        startedAt = now()
        tries = 0
        lastWrong = null
        silence()
        _state.update {
            it.copy(
                index = i, puzzle = puzzle, shown = shownFor(puzzle, tables ?: SqlTables(emptyList())),
                chosen = emptyList(), typed = "", wanted = null, got = null, correct = null,
                wrongTries = 0, revealed = false, running = false, refusal = null, refusalDetail = null,
            )
        }
        showJob = viewModelScope.launch { show(puzzle) }
    }

    private fun finishSitting() {
        if (ending) return
        ending = true
        // The session counts attempt rows as soon as it is told the module is done, so the last
        // puzzle's write has to be in the database before "done" ever reaches the screen.
        val write = lastWrite
        viewModelScope.launch {
            write?.join()
            val level = _state.value.level
            // Inside what the dots admit (spec §13), and never *up* unless the sitting earned it: the
            // sitting decides when he moves, the dots decide how far it may take him, and a morning
            // he got wrong can only ever hold him or step him back.
            val band = Difficulty.sql(_state.value.difficulty)
            val newLevel = Difficulty.levelAfterSitting(
                level, LevelProgression.next(level, results, band.first, band.last), band,
            )
            val moved = newLevel != level && runCatching { graph.settings.setSqlLevel(newLevel) }
                .onFailure { graph.errors.record("sql level write", it) }.isSuccess
            _state.update { it.copy(done = true, levelChanged = newLevel.takeIf { moved }) }
        }
    }

    /**
     * The user pressed back. Whatever was being said stops here, and [then] waits for the last
     * puzzle's write: the session counts rows the moment it is told, so leaving before the row lands
     * would lose the puzzle he had just done.
     */
    fun leave(then: () -> Unit) {
        loadJob?.cancel()
        // The screen is going and [onCleared] closes the database behind it: a query of his still
        // inside SQLite is aborted here rather than left to finish against a closed connection. The
        // watchdog's `finally` is what turns this cancellation into an abort.
        runJob?.cancel()
        showJob?.cancel()
        silence()
        val write = lastWrite
        viewModelScope.launch { write?.join(); then() }
    }

    /** The in-memory database goes with the screen. There is nothing in it worth keeping. */
    override fun onCleared() {
        runner?.close()
        runner = null
    }

    private fun record(puzzle: SqlPuzzle, answer: String, firstTry: Boolean, skipped: Boolean = false) {
        val outcome = sqlOutcome(firstTry, skipped)
        // Every finished puzzle is evidence, a skip included: passing on a question is not neutral,
        // it is one he could not do, and a level he skips his way through has to be steppable down.
        results += (outcome == Outcome.CORRECT)
        // Read eagerly: the clock is restarted the moment the next puzzle arrives.
        val began = startedAt
        val detail = sqlDetail(
            puzzle = puzzle,
            query = answer,
            ok = outcome == Outcome.CORRECT,
            ms = now() - began,
            tries = tries,
        )
        // Chained, because the app scope runs on a pool with no ordering: whoever joins the last
        // write must be joining every write, or a row can land after the session has counted.
        val prev = lastWrite
        // The app scope, not this screen's: pressing back must not lose the answer he just gave.
        lastWrite = graph.scope.launch {
            prev?.join()
            runCatching {
                graph.db.attempts().insert(
                    Attempt(
                        itemId = "sql:level:${puzzle.level}", module = ModuleId.SQL, sessionId = sessionId,
                        startedAt = began, durationMs = now() - began, outcome = outcome,
                        // No cue ladder here: «Άκου» reads the question, not the answer, so there is
                        // nothing on the 0–4 scale for this module to say.
                        cueLevel = null, detail = detail,
                    )
                )
            }.onFailure { graph.errors.record("sql record", it) }
        }
    }

    companion object {
        /** Said on the screen when a tap made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

        /** The database could not be built. Nothing he did, and the sitting says so rather than hanging. */
        const val TABLES_FAILED = "Οι πίνακες δεν άνοιξαν."

        /** One free retry, then the answer. The same rule «Αριθμοί» uses. */
        const val WRONG_TRIES_BEFORE_REVEAL = 2
    }
}
