package gr.dimitris.app.modules.arcade

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Source
import gr.dimitris.app.core.data.now
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class ArcadeState(
    val index: Int = 0,
    val total: Int = ArcadeModule.GAMES.size,
    val game: ArcadeGame = ArcadeGame.TAP,
    /** How big this game's target is right now, in dp. It is his difficulty; see [Adaptive]. */
    val sizeDp: Float = Adaptive.START,
    /** False while the stored size and the photos are read. No game is started before they land. */
    val ready: Boolean = false,
    /** What the pinch game zooms into, his own photos first. Empty means a plain coloured shape. */
    val photos: List<File> = emptyList(),
    val done: Boolean = false,
    /** Said on the screen when the phone said nothing out loud. */
    val error: String? = null,
)

/**
 * One sitting of the right-hand arcade: the four games, one after another, no timers anywhere.
 *
 * There is no failing here either. A target he misses grows and stays where it is until he gets it;
 * what a poor round costs him is only the difference between CORRECT and ASSISTED on the row, and
 * the size he carries into tomorrow. The size is the exercise, so it is written after every game
 * rather than at the end: a sitting he walks out of halfway still taught his hand something, and
 * that has to survive the walk.
 *
 * Nothing here writes a [gr.dimitris.app.core.scheduler.Scheduler] row. How well his hand aims says
 * nothing about which word is due tomorrow.
 */
class ArcadeViewModel(
    private val graph: AppGraph,
    private val sessionId: String?,
    /** One game per item the session handed the module; free practice asks for all four. */
    count: Int = ArcadeModule.GAMES.size,
) : ViewModel() {
    private val games = ArcadeModule.GAMES.take(count.coerceIn(1, ArcadeModule.GAMES.size))

    // Seeded with the count, so the title says "1/3" while it loads instead of flashing "1/4".
    private val _state = MutableStateFlow(ArcadeState(total = games.size, game = games.first()))
    val state: StateFlow<ArcadeState> = _state.asStateFlow()

    private val gson = Gson()
    private var startedAt = now()

    /**
     * Each game's own target size, read once at the start of the sitting. The four ask his hand for
     * four different things, so they do not share a difficulty; see [Settings.arcadeTargetDp].
     */
    private val sizes = mutableMapOf<ArcadeGame, Float>()

    /** The settings and photo read. Cancelled on the way out, so nothing lands on the next screen. */
    private var loadJob: Job? = null

    /**
     * The attempt write of the game just finished, chained. It runs on the app scope, so the end of
     * the sitting and the back arrow both join it first: the session counts rows, and a row still in
     * flight is not one.
     */
    private var lastWrite: Job? = null

    /** Set the moment the sitting starts winding down, so two endings cannot both end it. */
    private var ending = false

    /** True from the moment a game is finished or skipped until the next one starts: one row each. */
    private var finishing = false

    init { load() }

    private fun load() {
        loadJob = viewModelScope.launch {
            for (game in games) {
                sizes[game] = runCatching { graph.settings.arcadeTargetDp(game).first() }
                    .getOrElse { graph.errors.record("arcade size read", it); Adaptive.START }
            }
            val photos = photos()
            // Not before the reads: their wait is not his playing time.
            startedAt = now()
            _state.update { it.copy(sizeDp = sizeOf(games.first()), photos = photos, ready = true) }
            say(games.first())
        }
    }

    /**
     * What the pinch game shows: his own photos before the bundled pictograms, because a face he
     * knows is worth more than a drawing of a ball. Anything with a picture will do, and a device
     * with no pictures at all gets a coloured shape rather than an empty box.
     */
    private suspend fun photos(): List<File> {
        val items = runCatching { graph.db.items().allActive() }
            .getOrElse { graph.errors.record("arcade photos", it); emptyList() }
        val withPicture = items.filter { !it.imagePath.isNullOrBlank() }
        val his = withPicture.filter { it.source == Source.CAREGIVER }
        return his.ifEmpty { withPicture }.shuffled()
            .mapNotNull { item -> runCatching { graph.files.resolve(item.imagePath!!) }.getOrNull()?.takeIf { it.exists() } }
            .take(PHOTOS)
    }

    /**
     * One game finished: [hits] of [hits] + [misses] tries landed, and the target ended up
     * [newSizeDp] across. The size is persisted before anything else, because it is the only thing
     * the arcade carries from one day to the next.
     */
    fun onResult(hits: Int, misses: Int, newSizeDp: Float) {
        if (finishing || ending) return
        finishing = true
        val size = Adaptive.clamp(newSizeDp)
        graph.feedback.success()
        sizes[_state.value.game] = size
        _state.update { it.copy(sizeDp = size) }
        record(hits, misses, size, skipped = false)
        advance()
    }

    /** «Παράλειψη»: passing on a game is evidence too, so it is written down as a skip. */
    fun skip() {
        if (finishing || ending) return
        finishing = true
        graph.feedback.nudge()
        record(0, 0, _state.value.sizeDp, skipped = true)
        advance()
    }

    private fun advance() {
        val s = _state.value
        finishing = false
        val i = s.index + 1
        if (i >= games.size) { finishSitting(); return }
        val game = games[i]
        startedAt = now()
        // Its own size, not the one the game before it left behind.
        _state.update { it.copy(index = i, game = game, sizeDp = sizeOf(game)) }
        say(game)
    }

    private fun sizeOf(game: ArcadeGame): Float = sizes[game] ?: Adaptive.START

    private fun finishSitting() {
        if (ending) return
        ending = true
        // The session counts attempt rows as soon as it is told the module is done, so the last
        // game's write has to be in the database before "done" ever reaches the screen.
        val write = lastWrite
        viewModelScope.launch {
            write?.join()
            _state.update { it.copy(done = true) }
        }
    }

    /**
     * The user pressed back. Whatever was being said stops here, and [then] waits for the last
     * game's write: the session counts rows the moment it is told, so leaving before the row lands
     * would lose the game he had just played.
     */
    fun leave(then: () -> Unit) {
        loadJob?.cancel()
        graph.voice.quiet()
        val write = lastWrite
        viewModelScope.launch { write?.join(); then() }
    }

    /** The one line of instruction, out loud. Reading is the thing the stroke took from him too. */
    private fun say(game: ArcadeGame) {
        viewModelScope.launch { report(graph.speaker.speakText(game.prompt)) }
    }

    private fun report(result: Result<*>) = result.fold(
        onSuccess = { _state.update { if (it.error == SPEECH_FAILED) it.copy(error = null) else it } },
        onFailure = { e -> graph.errors.record("arcade speak", e); _state.update { it.copy(error = SPEECH_FAILED) } },
    )

    private fun record(hits: Int, misses: Int, size: Float, skipped: Boolean) {
        val game = _state.value.game
        val tries = hits + misses
        // Seven out of ten is his own work; below it the hand needed the target to keep growing,
        // which is help, not failure. Neither is ever said out loud — they are for the physio.
        val outcome = when {
            skipped -> Outcome.SKIPPED
            tries > 0 && hits * 100 >= tries * PASS_PERCENT -> Outcome.CORRECT
            else -> Outcome.ASSISTED
        }
        val detail = gson.toJson(mapOf("hits" to hits, "misses" to misses, "sizeDp" to size))
        // Read eagerly: the clock is restarted the moment the next game arrives.
        val began = startedAt
        val itemId = "$ITEM_PREFIX${game.id}"
        // Chained, because the app scope runs on a pool with no ordering: whoever joins the last
        // write must be joining every write, or a row can land after the session has counted.
        val prev = lastWrite
        // The app scope, not this screen's: pressing back must not lose the game he just played.
        lastWrite = graph.scope.launch {
            prev?.join()
            // The size is his difficulty and the only thing carried to tomorrow; a skipped game
            // leaves it exactly as it was.
            if (!skipped) {
                runCatching { graph.settings.setArcadeTargetDp(game, size) }
                    .onFailure { graph.errors.record("arcade size write", it) }
            }
            runCatching {
                graph.db.attempts().insert(
                    Attempt(
                        itemId = itemId, module = ModuleId.ARCADE, sessionId = sessionId,
                        startedAt = began, durationMs = now() - began, outcome = outcome, cueLevel = null, detail = detail,
                    )
                )
            }.onFailure { graph.errors.record("arcade record", it) }
        }
    }

    companion object {
        /** Attempt rows of the arcade: `arcade:tap`, `arcade:trace`, `arcade:drag`, `arcade:pinch`. */
        const val ITEM_PREFIX = "arcade:"

        /** Hits at or above this share of the tries is his own work. */
        const val PASS_PERCENT = 70

        /** How many of his pictures the pinch game keeps: one per round. */
        const val PHOTOS = 3

        /** The end of a sitting. Said to the hand, because the hand is what did it. */
        const val FINISHED = "Τέλος για σήμερα. Μπράβο το δεξί!"

        /** Said on the screen when the instruction made no sound at all. */
        const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."
    }
}
