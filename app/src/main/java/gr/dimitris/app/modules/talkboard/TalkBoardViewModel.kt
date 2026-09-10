package gr.dimitris.app.modules.talkboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Adapt
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemCount
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.judge.Ask
import gr.dimitris.app.core.judge.Kind
import gr.dimitris.app.core.judge.LocalJudge
import gr.dimitris.app.core.judge.Verdict
import gr.dimitris.app.core.speech.GentleCheck
import gr.dimitris.app.core.speech.Recognition
import gr.dimitris.app.core.speech.SpeechFailure
import gr.dimitris.app.core.speech.SpeechMatch
import gr.dimitris.app.core.speech.Transcript
import gr.dimitris.app.core.speech.take
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Shown under the strip when a tap made no sound at all. */
const val SPEECH_FAILED = "Δεν ακούγεται η φωνή. Δες τις ρυθμίσεις."

/**
 * Recognition came back with nothing, inside the expansion. Never a verdict on him: the same line
 * the word coach says, because it is the same moment and he should not meet two sentences for it.
 */
const val HEARD_NOTHING = "Δεν άκουσα τίποτα. Δοκίμασε ξανά αν θέλεις."

/** The microphone was refused. The same line the word coach shows, for the same reason. */
const val MIC_DENIED = "Χωρίς άδεια μικροφώνου"

/**
 * One tap on the board, as the row will carry it.
 *
 * Free of the ViewModel so that what is written down can be argued with in a unit test rather than
 * on a phone: see `TelemetryTest`. There is nothing to time here and nothing to mark: a tap is him
 * saying a word, and the app is a voice, not an examiner. `strip` is what phase 1 already wrote and
 * an off-strip tap still writes nothing at all, so those rows are untouched.
 *
 * [stripLen] is how many words stood on the strip once this one joined it. It is the only knob the
 * board has — how long a sentence it lets him build — and nothing else records how long the ones he
 * really builds are. See `docs/ADAPTATION.md`.
 */
internal fun talkBoardDetail(inStrip: Boolean, stripLen: Int?): String = Adapt.detail {
    if (!inStrip) return@detail
    put("strip", true)
    put("stripLen", stripLen)
}

/**
 * One «Ολόκληρη», as the row will carry it.
 *
 * Free of the ViewModel for the same reason [talkBoardDetail] is: see `TelemetryTest`. It is a row
 * of its own kind — the board's other rows are single words he said — so it says so in its first
 * key, and everything a reader would want to compare it with later is beside it: the content words
 * he tapped, the sentence he was given back, where that sentence came from and how long it took
 * ([judge]), and what the recogniser then made of him repeating it.
 *
 * The recognition keys carry the names the word coach already writes them under, so a reader that
 * can read `sttHeard` on one module can read it on both.
 */
internal fun expandDetail(
    words: String,
    expanded: String,
    stripLen: Int,
    sttOn: Boolean,
    heard: String?,
    matched: Boolean,
    sttTries: Int,
    ms: Long,
    judge: Map<String, Any?>,
): String = Adapt.detail {
    put("kind", EXPAND_KIND)
    put("words", words)
    put("expanded", expanded)
    put("judge", judge)
    put("stripLen", stripLen)
    if (sttOn) {
        put("sttHeard", heard)
        put("sttMatched", matched)
        put("sttTries", sttTries)
    }
    put("ms", ms)
    put("sttOn", sttOn)
}

/** What `detail.kind` says about a row «Ολόκληρη» wrote. */
const val EXPAND_KIND = "expand"

/**
 * The item id every «Ολόκληρη» row is written against.
 *
 * Synthetic, like [gr.dimitris.app.today.SessionViewModel.SESSION_SUMMARY]: an expansion is about a
 * sentence he built, not about any one of the words in it, and pinning it on whichever word happened
 * to be last would claim he practised that word. Nothing joins it to the `items` table — there is no
 * foreign key on `attempts.itemId` — and the talk board is outside
 * [gr.dimitris.app.caregiver.progress.ProgressStats.GRADED_MODULES], so it cannot reach a per-word
 * judgement either.
 */
const val EXPAND_ITEM = "talkboard:expand"

/**
 * Whether «Ολόκληρη» is on the screen at all.
 *
 * Absent rather than greyed, which is the controller's ruling and the kinder of the two: a button he
 * can see but never press is a promise the app keeps breaking, and without a key or the toggle there
 * is nothing behind it — [LocalJudge] cannot build a Greek sentence out of content words and must
 * not pretend to (see its KDoc). Two words is the floor because one word is not a sentence to expand.
 */
internal fun showsExpand(words: Int, judgeReady: Boolean): Boolean =
    judgeReady && words >= EXPAND_WORDS_MIN

/**
 * How many *words* stand on the strip, which is not how many chips do.
 *
 * A vocabulary item can be a whole phrase — «θέλω νερό», «δώσε μου νερό» are single cards on this
 * board — so one chip is often two or three words, and counting chips would hide «Ολόκληρη» from
 * exactly the sentences that are most worth expanding.
 */
internal fun stripWords(items: List<Item>): Int = items.sumOf { wordsIn(it.text) }

private fun wordsIn(text: String): Int = text.trim().split(WHITESPACE).count { it.isNotEmpty() }

private val WHITESPACE = Regex("\\s+")

/** Fewer than this and there is no sentence to make. */
const val EXPAND_WORDS_MIN = 2

/**
 * What the sentence he heard cost him in help, on the cue ladder's own scale.
 *
 * Three, the same as [gr.dimitris.app.modules.wordcoach.CueLadder.LISTENED]: the whole point of the
 * expansion is that the phone says the sentence *first* and he repeats it, so the row must never read
 * as unprompted speech.
 */
const val EXPAND_CUE_LEVEL = 3

/**
 * How much grammar the judge is asked to insist on, until Task 4's dot row exists.
 *
 * Two is [gr.dimitris.app.core.settings.Settings]' own default for the ladder that is coming, so the
 * sentences he gets today are the sentences he will get when it arrives and nobody has touched the
 * dots. Replace with `settings.difficulty(ModuleId.TALKBOARD)` then.
 */
const val EXPAND_DIFFICULTY = 2

/** Where a failure inside the expansion is written down for the caregiver's error list. */
const val WHERE_EXPAND = "talkboard expand"

sealed class Tab(val label: String) {
    object Favourites : Tab("Αγαπημένα")
    data class Cat(val category: Category) : Tab(category.greek)
}

/**
 * What one recognition window came back with, as the expansion needs to read it.
 *
 * The translation from a [Transcript] and its failures happens in the ViewModel, so that
 * [ExpansionFlow] — the part with the rules in it — holds nothing from `android.speech` and can be
 * argued with in a plain unit test. The emulator has no recognition service at all, so that is not a
 * convenience: it is the only place the gentle check of this flow can be proved.
 */
sealed interface Heard {
    /** Words came back. */
    data class Words(val text: String) : Heard

    /** Silence, a sound that matched nothing, or his own «Στοπ» before he had started. His, and answered gently. */
    object Silence : Heard

    /** The phone could not listen at all. Never his: [line] points at the settings, and it costs him no try. */
    data class Broken(val line: String) : Heard
}

/**
 * What «Ολόκληρη» has put on the screen, or null when the board is in its ordinary state.
 *
 * [sentence] is null for exactly as long as the judge is being asked; everything else waits for it,
 * because there is nothing to repeat until it arrives.
 */
data class Expansion(
    /** The content words he tapped, as they were sent. */
    val words: String,
    /** The full sentence to read, hear and repeat — or his own words back, when the judge fell back. */
    val sentence: String? = null,
    /** Whether the recogniser is his to use at all. With it off, «Το είπα!» is the primary from the start. */
    val sttOn: Boolean = false,
    val listening: Boolean = false,
    val heard: String? = null,
    val matched: Boolean = false,
    /** «Δοκίμασε ξανά» is on the screen: one miss, and nothing else has changed. */
    val nudge: Boolean = false,
    /** How many windows the phone heard him say something else. */
    val sttTries: Int = 0,
    /** Whether «Το είπα!» is his to press. */
    val canConfirm: Boolean = false,
    /** He said it, or said he said it: the check mark, and nothing left but «Κλείσε». */
    val done: Boolean = false,
    val error: String? = null,
) {
    /** The judge has not answered yet. */
    val thinking: Boolean get() = sentence == null
}

/**
 * «Ολόκληρη»: the content words he tapped, turned into one whole Greek sentence, said to him, and
 * then said back by him. Spec §13 calls this the core therapy, and it is the agrammatism drill and
 * the communication help at the same time — «φάρμακα πρέπει πάρω» is what he can get out, and
 * «Πρέπει να πάρω τα φάρμακα» is what he wants to have said.
 *
 * Everything it needs is a function value, for two reasons. The board's ViewModel cannot be built
 * without an Android context, and the four things this has to get right — that the sentence is shown
 * and spoken, that a repeat the phone agrees with is written as his own work, that a fallback shows
 * his words unchanged rather than inventing grammar, and that closing it writes what really happened
 * — are rules, not Android. They belong in a test that runs in a second. The second reason is the
 * same one [gr.dimitris.app.core.judge.TurnJudge] is built this way: the seams are the places where
 * something can be wrong without anything throwing.
 *
 * Two rules hold everywhere in here:
 *
 * * **One row per expansion, at most.** A match writes it, a confirm writes it, closing writes it,
 *   and whichever happened first is the one that counts — `written` is the latch. Closing after a
 *   match does not add a second row saying he passed on it.
 * * **Nothing is written for a sentence that never arrived.** Closing while the judge is still being
 *   asked cancels the ask and writes nothing: he saw no sentence, so there is nothing to have skipped.
 */
class ExpansionFlow(
    private val scope: CoroutineScope,
    /** [gr.dimitris.app.core.judge.TurnJudge.judge]. Never throws in production; guarded anyway. */
    private val askJudge: suspend (Ask) -> Verdict,
    /** His own 1–5 dot row, once Task 4 has one. */
    private val difficulty: suspend () -> Int,
    /**
     * The phone saying the sentence. A failure is his to be told about, not to be swallowed, and it
     * silences whatever was sounding first — [sayAgain] can arrive in the middle of the utterance it
     * is about to repeat.
     */
    private val speakOut: suspend (String) -> Result<Unit>,
    /** Whether recognition is on and available. One settings read, taken while the judge is thinking. */
    private val sttOn: suspend () -> Boolean,
    /** Opens the recognition window and waits for him. Quiets the speaker first — see the ViewModel. */
    private val openWindow: suspend () -> Heard,
    /** «Στοπ». What was heard still arrives through the window that was opened. */
    private val closeWindow: () -> Unit,
    /** Where the row goes. Fired on a scope that outlives this screen: a back press must not lose it. */
    private val writeRow: (Attempt) -> Unit,
    private val onSuccess: () -> Unit,
    private val onNudge: () -> Unit,
    /** The caregiver's error list. Defaulted to silence so a test that is not about it need not say so. */
    private val record: (String, Throwable) -> Unit = { _, _ -> },
    private val now: () -> Long = ::now,
) {
    private val _state = MutableStateFlow<Expansion?>(null)
    val state: StateFlow<Expansion?> = _state.asStateFlow()

    /** The one thing in flight: the ask, or the window. A new one replaces whatever the old one was. */
    private var job: Job? = null

    private var check = GentleCheck()

    /** True once the recogniser has said it could not listen. It never takes the confirm back. */
    private var broke = false

    private var startedAt = 0L
    private var stripLen = 0
    private var judgeDetail: Map<String, Any?> = emptyMap()
    private var written = false

    /**
     * «Ολόκληρη». [words] is the strip as one line and [len] how many words that is.
     *
     * The judge is asked for the sentence, the sentence is shown and spoken, and then it is his to
     * repeat. A second tap while one is open does nothing — the strip row is showing the expansion
     * rather than the button by then, but a view model's door is not the place to rely on that.
     */
    fun open(words: String, len: Int) {
        if (_state.value != null) return
        val line = words.trim()
        // The words, not the chips: [len] is how many cards he tapped and goes in the row as it
        // always did, but one card can be a whole phrase and the floor is about the sentence.
        if (wordsIn(line) < EXPAND_WORDS_MIN) return
        written = false
        broke = false
        check = GentleCheck()
        judgeDetail = emptyMap()
        startedAt = now()
        stripLen = len
        _state.value = Expansion(words = line)
        job = scope.launch {
            // Asked while he is waiting for the sentence anyway: one DataStore read and one question
            // to the package manager, neither of which should cost him a beat after it lands.
            val on = try {
                sttOn()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                record(WHERE_EXPAND, e)
                false
            }
            val level = try {
                difficulty()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                record(WHERE_EXPAND, e)
                EXPAND_DIFFICULTY
            }
            val ask = Ask(
                kind = Kind.EXPAND,
                // No question was asked and no sentence was wanted: the words are the whole of it.
                prompt = null,
                target = null,
                heard = line,
                difficulty = level,
            )
            val began = now()
            val verdict = try {
                askJudge(ask)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                // The judge's own contract is that it never throws; if one ever does, his words come
                // back rather than an empty screen.
                record(WHERE_EXPAND, e)
                LocalJudge.judge(ask)
            }
            judgeDetail = verdict.detail(ms = now() - began)
            // The sentence, or his own words when the judge fell back — never nothing. LocalJudge
            // hands [Ask.heard] straight back for exactly this, because a local attempt at Greek
            // grammar would teach him wrong forms.
            val sentence = verdict.expanded?.trim()?.takeIf { it.isNotEmpty() } ?: line
            // With recognition off there is nothing to repeat into, so «Το είπα!» is his at once.
            _state.update { it?.copy(sentence = sentence, sttOn = on, canConfirm = !on) }
            val said = speakOut(sentence)
            if (said.isFailure) _state.update { it?.copy(error = SPEECH_FAILED) }
        }
    }

    /**
     * «Πες το»: the recognition window opens and waits for him to say the sentence back.
     *
     * No stopwatch and no countdown — Task 1's rule — and the sentence stays on the screen while the
     * window is open, because it is the thing he is reading.
     */
    fun sayIt() {
        val s = _state.value ?: return
        val sentence = s.sentence ?: return
        if (s.listening || s.done || !s.sttOn) return
        // Whatever was still being said stops at the window: the recogniser would otherwise hear the
        // phone's own sentence and agree with it.
        job?.cancel()
        _state.value = s.copy(listening = true, heard = null, matched = false, nudge = false, error = null)
        job = scope.launch {
            val heard = try {
                openWindow()
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Throwable) {
                record(WHERE_EXPAND, e)
                Heard.Silence
            }
            resolve(heard, sentence)
        }
    }

    /**
     * The board's own «Πες το», while an expansion is open: the sentence again, out loud.
     *
     * This is the whole of the fix for the hole the first round left. He hears a seven-word sentence
     * once, loses the front of it — which is the deficit the feature exists for — and reaches for the
     * one control on this board that has ever made it talk. Before this, that button read his three
     * telegraphic words instead, closed the sentence and wrote it off as skipped. Now it repeats what
     * is on the screen, changes no state and writes nothing, so he can hear it as many times as he
     * needs to before «Μίλα» — and after a «Δοκίμασε ξανά» as well, so his second go is not from
     * memory.
     *
     * Returns whether the tap belonged to the expansion at all; false leaves the board's ordinary
     * behaviour alone. While the window is open it is consumed and nothing is said: the recogniser
     * would otherwise hear the phone.
     */
    fun sayAgain(): Boolean {
        val s = _state.value ?: return false
        if (s.listening) return true
        // Consumed but silent while the judge is still being asked: there is no sentence to repeat
        // yet, and the one job in flight is the ask itself, which must not be cancelled to read out
        // words that are about to be replaced.
        val line = s.sentence ?: return true
        job?.cancel()
        job = scope.launch {
            val said = speakOut(line)
            _state.update { it?.copy(error = if (said.isFailure) SPEECH_FAILED else null) }
        }
        return true
    }

    /** «Στοπ»: the window closes now, and what it had heard still comes back through [sayIt]. */
    fun stop() {
        if (_state.value?.listening == true) closeWindow()
    }

    /**
     * The microphone was refused. Said on the screen rather than left as a button that does nothing,
     * and «Το είπα!» opens: a permission the app does not hold is not a reason he cannot say he said
     * the sentence.
     */
    fun micDenied() {
        _state.update { it?.copy(listening = false, canConfirm = true, error = MIC_DENIED) }
        broke = true
    }

    /**
     * «Το είπα!». His word against the phone's: a sentence he says he said is work done, with the
     * help of having heard it first — which is what [EXPAND_CUE_LEVEL] records.
     */
    fun confirm() {
        val s = _state.value ?: return
        if (s.thinking || s.listening || s.done || !s.canConfirm) return
        finish(if (s.matched) Outcome.CORRECT else Outcome.ASSISTED)
    }

    /**
     * «Κλείσε», and everything else that ends the flow: a word tapped into the strip, the board being
     * left. A sentence he heard and walked away from is a row that says so; a sentence that never
     * arrived is no row at all.
     */
    fun close() {
        val s = _state.value ?: return
        job?.cancel()
        job = null
        if (s.listening) closeWindow()
        if (!written && s.sentence != null) finish(Outcome.SKIPPED)
        _state.value = null
    }

    private fun resolve(heard: Heard, sentence: String) {
        when (heard) {
            // The phone's bad morning, not his: no try spent, no nudge, and the confirm opens at once
            // and stays open. Chris' whole report was about the app putting its own trouble on him.
            is Heard.Broken -> {
                broke = true
                _state.update {
                    it?.copy(
                        listening = false, heard = null, matched = false, nudge = false,
                        canConfirm = true, error = heard.line,
                    )
                }
            }
            Heard.Silence -> step(null, sentence)
            is Heard.Words -> step(heard.text, sentence)
        }
    }

    /**
     * What the phone heard, weighed against the sentence — the gentle check of spec §12, whole.
     *
     * [SpeechMatch.phraseMatches] and not a second question to the model: the sentence is known
     * verbatim, which is the case that comparison was written for, and a man who has just repeated a
     * long sentence is owed «Μπράβο» now rather than up to eight seconds later.
     */
    private fun step(text: String?, sentence: String) {
        val matched = text != null && SpeechMatch.phraseMatches(text, sentence)
        val verdict = check.record(text, matched)
        _state.update {
            it?.copy(
                listening = false, heard = text, matched = matched, sttTries = check.tries,
                nudge = check.nudging, canConfirm = check.canConfirm || broke,
                error = if (text == null) HEARD_NOTHING else null,
            )
        }
        // A match is confirmed for him: being made to press a button to agree with the phone is one
        // step too many for a man who has just done the hard part.
        if (verdict == GentleCheck.Verdict.MATCHED) finish(Outcome.CORRECT) else onNudge()
    }

    private fun finish(outcome: Outcome) {
        val s = _state.value ?: return
        if (!written) {
            written = true
            val began = startedAt
            val ms = now() - began
            writeRow(
                Attempt(
                    itemId = EXPAND_ITEM,
                    module = ModuleId.TALKBOARD,
                    startedAt = began,
                    durationMs = ms,
                    outcome = outcome,
                    cueLevel = EXPAND_CUE_LEVEL,
                    detail = expandDetail(
                        words = s.words,
                        expanded = s.sentence.orEmpty(),
                        stripLen = stripLen,
                        sttOn = s.sttOn,
                        heard = s.heard,
                        matched = s.matched,
                        sttTries = check.tries,
                        ms = ms,
                        judge = judgeDetail,
                    ),
                )
            )
        }
        if (outcome != Outcome.SKIPPED) {
            onSuccess()
            _state.update { it?.copy(done = true) }
        }
    }
}

class TalkBoardViewModel(private val graph: AppGraph) : ViewModel() {
    val strip = SentenceStrip()

    /**
     * Script lines are items of category CUSTOM, so without this filter the dialogues would take
     * over the «Δικά μας» tab and the favourites. A line is practised inside its script, not tapped
     * out of context here.
     */
    private val all: StateFlow<List<Item>> = graph.items.observeAll()
        .map { l -> l.filter { it.kind != ItemKind.SCRIPT_LINE } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Room re-emits this on every attempt insert, so a tap re-ranks favourites with no nudging. */
    private val usage: Flow<List<ItemCount>> = graph.db.attempts().mostUsed(ModuleId.TALKBOARD, USAGE_LIMIT, EXPAND_ITEM)
        .catch { graph.errors.record("talkboard usage", it); emit(emptyList()) }

    private val pinnedIds: StateFlow<Set<String>> = graph.db.items().observePinned().map { l -> l.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _tab = MutableStateFlow<Tab>(Tab.Favourites)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    /** Tabs: favourites, then every category that has at least one item, QUICK excluded (it has its own row). */
    val tabs: StateFlow<List<Tab>> = all.map { items ->
        listOf(Tab.Favourites) + Category.entries.filter { c -> c != Category.QUICK && items.any { it.category == c } }.map { Tab.Cat(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(Tab.Favourites))

    val quick: StateFlow<List<Item>> = all.map { l -> l.filter { it.category == Category.QUICK } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shown: StateFlow<List<Item>> = combine(all, pinnedIds, usage, _tab) { items, pinned, use, tab ->
        when (tab) {
            Tab.Favourites -> Favourites.rank(items, pinned, use)
            is Tab.Cat -> items.filter { it.category == tab.category }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _stripFull = MutableStateFlow(false)
    val stripFull: StateFlow<Boolean> = _stripFull.asStateFlow()

    /**
     * Set when a speak attempt made no sound, cleared by the next one that does. Silence is the one
     * failure Dimitris cannot diagnose himself, so it is said on the screen instead of only logged.
     */
    private val _speechError = MutableStateFlow<String?>(null)
    val speechError: StateFlow<String?> = _speechError.asStateFlow()

    /**
     * True from a said sentence until the strip next changes. No timer: the check mark is the answer
     * to "did it come out?", and Dimitris decides when he is done looking at it.
     */
    private val _spoken = MutableStateFlow(false)
    val spoken: StateFlow<Boolean> = _spoken.asStateFlow()

    /** Which classes of recogniser trouble have already reached «Σφάλματα» from this board. */
    private val reportedRecogniser = mutableSetOf<Recognition.ErrorClass>()

    /**
     * Whether «Ολόκληρη» has anything behind it: the caregiver's toggle on, and a key saved.
     *
     * The question is [gr.dimitris.app.core.judge.TurnJudge.available]'s to answer — it makes those
     * two reads for every turn anyway — and it is asked because the judge never *reports* being
     * unavailable: by design it answers locally instead, and a local answer to an EXPAND is his own
     * words back. That is the right behaviour for a judged turn that failed mid-flow (he still gets
     * his sentence read to him) and the wrong thing to put behind a button.
     *
     * The toggle is the trigger and the judge is the answer, so a caregiver who saves a key and
     * switches it on changes the next screen he opens rather than the next time the app starts.
     */
    val judgeReady: StateFlow<Boolean> = graph.settings.claudeJudging
        .map { graph.judge.available() }
        .catch { e -> graph.errors.record("talkboard judge", e); emit(false) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** The whole of «Ολόκληρη». See [ExpansionFlow]; everything it needs is wired from the graph here. */
    private val flow = ExpansionFlow(
        scope = viewModelScope,
        askJudge = { ask -> graph.judge.judge(ask) },
        // Task 4's dot row replaces this with settings.difficulty(ModuleId.TALKBOARD).
        difficulty = { EXPAND_DIFFICULTY },
        // Silence first: «Πες το» can arrive in the middle of the sentence it is about to repeat,
        // and two overlapping Greek sentences are worse than none.
        speakOut = { text -> graph.voice.quiet(); graph.speaker.speakText(text) },
        // isAvailable asks the package manager across a binder: not on the thread drawing the board.
        sttOn = { graph.settings.sttEnabled.first() && withContext(Dispatchers.Default) { graph.stt.isAvailable } },
        openWindow = {
            // The microphone is about to open, so the speaker stops here. Said the other way round:
            // the sentence the phone has just read out must never be what the recogniser hears.
            graph.voice.quiet()
            heardOf(graph.stt.listen())
        },
        closeWindow = { graph.stt.stop() },
        // The app's scope, not this screen's: leaving the board must not lose the sentence he said.
        writeRow = { row ->
            graph.scope.launch {
                runCatching { graph.db.attempts().insert(row) }
                    .onFailure { graph.errors.record(WHERE_EXPAND, it) }
            }
        },
        onSuccess = { graph.feedback.success() },
        onNudge = { graph.feedback.nudge() },
        record = { where, e -> graph.errors.record(where, e) },
    )

    val expansion: StateFlow<Expansion?> = flow.state

    private val _listenLevel = MutableStateFlow(0f)

    /** How loud he is, 0..1, while the expansion's window is open. Zero at every other moment. */
    val listenLevel: StateFlow<Float> = _listenLevel.asStateFlow()

    init {
        // A new screen is a new run: the judge writes one row per failure class per run, and a
        // connection that was down this morning and is down again tonight is two facts, not one.
        graph.judge.newRun()
        // Only while a window is open: the bar belongs to the microphone, and nothing else draws it.
        viewModelScope.launch {
            graph.stt.level.collect { l -> _listenLevel.value = if (flow.state.value?.listening == true) l else 0f }
        }
    }

    fun selectTab(t: Tab) { _tab.value = t }

    /** Grid tap: say it and add it to the sentence. */
    fun tap(item: Item) {
        // The expansion was of the words that were on the strip; one more word makes it a sentence
        // about something he did not tap. It closes, and the row says he left it.
        flow.close()
        // A full strip still says the word; the attempt is logged for what it was, not what was asked.
        val added = strip.add(item)
        _stripFull.value = !added
        if (added) _spoken.value = false
        val len = strip.items.value.size
        viewModelScope.launch { heard(graph.speaker.speak(item)); log(item, inStrip = added, stripLen = len) }
    }

    /** Quick row tap: say it immediately, never added to the sentence. */
    fun tapQuick(item: Item) {
        // It does not change the strip, but it does make the phone speak — which is the one thing
        // that cannot happen over an open recogniser. Anything he taps to say out loud ends the drill.
        flow.close()
        viewModelScope.launch { heard(graph.speaker.speak(item)); log(item, inStrip = false) }
    }

    /**
     * «Πες το», the board's own: the strip read out loud — or, with an expansion open, the sentence
     * read out loud again. See [ExpansionFlow.sayAgain]: it is the only control on this board he has
     * ever used to make it talk, so while there is a sentence on the screen it belongs to that
     * sentence, and it neither closes the flow nor writes a row.
     */
    fun speakStrip() {
        if (flow.sayAgain()) return
        val snapshot = strip.items.value
        if (snapshot.isEmpty()) return
        viewModelScope.launch {
            val said = heard(graph.speaker.speakText(strip.text))
            // An interrupted utterance still resolves as success at the TTS layer, so the check mark
            // is only earned if the sentence it belongs to is still the one on the strip.
            if (said && strip.items.value == snapshot) {
                graph.feedback.success()
                _spoken.value = true
            }
        }
    }

    fun undo() { flow.close(); strip.removeLast(); _stripFull.value = false; _spoken.value = false }
    fun clear() { flow.close(); strip.clear(); _stripFull.value = false; _spoken.value = false }

    /** «Ολόκληρη»: the words on the strip, as one whole Greek sentence. */
    fun expand() = flow.open(strip.text, strip.items.value.size)

    /** «Πες το», inside the expansion: the recogniser opens and waits for him to say it back. */
    fun sayExpansion() = flow.sayIt()

    /** «Στοπ», inside the expansion. */
    fun stopListening() = flow.stop()

    /** The microphone was refused: say so, and leave him «Το είπα!». */
    fun micDenied() = flow.micDenied()

    /** «Το είπα!», inside the expansion. */
    fun confirmExpansion() = flow.confirm()

    /** «Κλείσε». */
    fun closeExpansion() = flow.close()

    /**
     * One window, as [ExpansionFlow] reads it.
     *
     * His own voice is **deleted** rather than kept. On the on-device path the window hands back a
     * WAV of him, and everywhere else in the app that file is attached to the item he was naming —
     * but an expansion is written against [EXPAND_ITEM], which is no item, so there would be nothing
     * to attach it to and the file would sit in the recordings folder with no row pointing at it.
     */
    private suspend fun heardOf(result: Result<Transcript>): Heard {
        // Off the drawing thread, and a delete that failed is said out loud in the error list rather
        // than leaving exactly the orphan this is here to prevent.
        result.take?.file?.let { file ->
            withContext(Dispatchers.IO) {
                if (file.exists() && !file.delete()) {
                    graph.errors.record(WHERE_EXPAND, IllegalStateException("take not deleted: ${file.name}"))
                }
            }
        }
        return result.fold(
            onSuccess = { t -> t.text.trim().takeIf { it.isNotEmpty() }?.let { Heard.Words(it) } ?: Heard.Silence },
            onFailure = { e ->
                if (e is SpeechFailure.NotWorking) {
                    // Once per screen at most: the line is on the screen, and the caregiver's list
                    // must not fill with one row per tap on a phone that is simply offline.
                    if (reportedRecogniser.add(Recognition.classOf(e.code))) graph.errors.record("talkboard listen", e)
                    Heard.Broken(Recognition.classOf(e.code).line)
                } else {
                    Heard.Silence
                }
            },
        )
    }

    /** Records the outcome of one speak attempt and reports whether anything was actually heard. */
    private fun heard(result: Result<*>): Boolean {
        result.fold(
            onSuccess = { _speechError.value = null },
            onFailure = { graph.errors.record("talkboard speak", it); _speechError.value = SPEECH_FAILED },
        )
        return result.isSuccess
    }

    private suspend fun log(item: Item, inStrip: Boolean, stripLen: Int? = null) {
        val t = now()
        runCatching {
            graph.db.attempts().insert(
                Attempt(itemId = item.id, module = ModuleId.TALKBOARD, startedAt = t, durationMs = 0, outcome = Outcome.CORRECT,
                    cueLevel = null, detail = talkBoardDetail(inStrip, stripLen))
            )
        }.onFailure { graph.errors.record("talkboard log", it) }
    }

    /**
     * He is leaving the board with an expansion open. It ends the way «Κλείσε» ends it — the window
     * closed and the row written — because a sentence he heard and walked away from is still
     * something that happened.
     */
    override fun onCleared() {
        flow.close()
    }

    private companion object {
        /** Enough most-used items to fill the favourites tab several times over. */
        const val USAGE_LIMIT = 24
    }
}
