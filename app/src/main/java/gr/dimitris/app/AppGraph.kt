package gr.dimitris.app

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.room.withTransaction
import gr.dimitris.app.caregiver.insights.AdviceSession
import gr.dimitris.app.caregiver.insights.ClaudeAdvisor
import gr.dimitris.app.caregiver.insights.Focus
import gr.dimitris.app.core.audio.ImageStore
import gr.dimitris.app.core.audio.MediaFiles
import gr.dimitris.app.core.audio.Player
import gr.dimitris.app.core.audio.Recorder
import gr.dimitris.app.core.audio.ToneSynth
import gr.dimitris.app.core.audio.Voice
import gr.dimitris.app.core.data.AppDatabase
import gr.dimitris.app.core.data.Advice as AdviceRow
import gr.dimitris.app.core.data.ItemRepository
import gr.dimitris.app.core.data.ScriptRepository
import gr.dimitris.app.core.data.now as systemNow
import gr.dimitris.app.core.judge.TurnJudge
import gr.dimitris.app.core.log.ErrorReporter
import gr.dimitris.app.core.scheduler.Scheduler
import gr.dimitris.app.core.secrets.SecretStore
import gr.dimitris.app.core.settings.Settings
import gr.dimitris.app.core.speech.AndroidSpeechToText
import gr.dimitris.app.core.speech.AndroidTextToSpeech
import gr.dimitris.app.core.speech.ItemSpeaker
import gr.dimitris.app.core.speech.SpeechToText
import gr.dimitris.app.core.speech.TextToSpeech
import gr.dimitris.app.core.sync.DaoSyncStore
import gr.dimitris.app.core.sync.HttpSyncClient
import gr.dimitris.app.core.sync.SyncDaos
import gr.dimitris.app.core.sync.SyncEngine
import gr.dimitris.app.modules.Module
import gr.dimitris.app.modules.arcade.ArcadeModule
import gr.dimitris.app.modules.numbers.NumbersModule
import gr.dimitris.app.modules.scripts.ScriptsModule
import gr.dimitris.app.modules.sentences.SentencesModule
import gr.dimitris.app.modules.singsay.SingSayModule
import gr.dimitris.app.modules.trace.TraceModule
import gr.dimitris.app.modules.wordcoach.WordCoachModule
import gr.dimitris.app.ui.theme.Feedback
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update

/**
 * Every long-lived object the app needs, wired by hand in one place. No DI framework:
 * anyone can read this file top to bottom and know what exists.
 */
class AppGraph(context: Context) {
    val app: Context = context.applicationContext
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile var db: AppDatabase = AppDatabase.open(app)
        private set

    val files = MediaFiles(app)
    val images = ImageStore(files)
    val settings = Settings(app)
    val tts: TextToSpeech = AndroidTextToSpeech(app)
    /**
     * A `var` for one reason: the emulator has no recognition service at all, so the only way to
     * drive the gentle check of spec §12 in a test is to put a fake in front of the three modules.
     * Nothing in the app ever assigns it.
     */
    var stt: SpeechToText = AndroidSpeechToText(app, newTakeFile = files::newWavFile)
    val recorder = Recorder(app, files)
    val player = Player()
    /** Owned by [voice], which is the only production caller; kept here so tests can drive it alone. */
    val synth = ToneSynth()

    /** The only way in: everything that makes sound goes through here, one at a time. */
    val voice = Voice(app, tts, player, recorder, synth)

    val feedback = Feedback(app)
    val errors = ErrorReporter(scope) { db.errorLogs() }

    /**
     * The caregiver's own Anthropic key, encrypted. Constructing this touches nothing; the file is
     * opened on the first read, which is why every read of it happens off the main thread.
     */
    val secrets = SecretStore(app)

    /**
     * Optional, and off until a caregiver saves a key. It is the only thing in the app that talks to
     * anything outside the phone, and only when someone taps the button on the advice screen.
     */
    val advisor = ClaudeAdvisor(secrets) { settings.claudeModel.first() }

    /**
     * Every judgement of what he said, through one place (spec §13). Off by default twice over — the
     * toggle starts false and there is no key until a caregiver saves one — and in that state it is
     * the same local matching the three speech modules have always used, with no network at all.
     *
     * Held for the life of the app rather than built per turn because it remembers which failures it
     * has already written down: a phone that lost its connection fails on every word of a session,
     * and the error log a caregiver reads must not fill up with three hundred copies of one fact.
     */
    val judge = TurnJudge(
        secrets = secrets,
        // Read per turn, not captured: a caregiver switching it off mid-session stops the next word.
        enabled = { settings.claudeJudging.first() },
        record = { where, e -> errors.record(where, e) },
    )

    /**
     * The one question in flight and the last answer, held here rather than in the advice screen's
     * view model: a request that costs money must not be started twice or thrown away because a
     * caregiver pressed back while it was thinking.
     */
    val adviceSession = AdviceSession(
        scope = scope,
        send = advisor::ask,
        record = { where, e -> errors.record(where, e) },
        // Read through `db` on the call, never captured: a backup import swaps the database
        // underneath everything, and an advice must land in the one that is open now.
        store = { report, advice ->
            db.advice().upsert(
                AdviceRow(
                    // The model that actually answered, not the setting: `ask` resolves its own
                    // value and falls back silently, so reading the setting again here could name
                    // a model that was never asked — and nobody can check that row afterwards.
                    model = advice.model.ifBlank { settings.claudeModel.first() },
                    report = report,
                    caregivers = advice.caregivers,
                    dimitris = advice.dimitris,
                    focusJson = advice.focusJson,
                )
            )
        },
    )

    /** Always built from the current db, so it survives a backup import. */
    val items: ItemRepository get() = ItemRepository(db.items(), db.recordings(), files::relativize)

    /**
     * Always built from the current db, so it survives a backup import. The database is read once
     * into a local, so the dao and the transaction it commits in are always the same instance even
     * if a restore swaps [db] mid-save.
     */
    val scripts: ScriptRepository get() {
        val database = db
        return ScriptRepository(database.scripts(), items, inTransaction = { block -> database.withTransaction { block() } })
    }

    /** Always built from the current db, so it survives a backup import. */
    val scheduler: Scheduler get() = Scheduler(db.schedules())

    /**
     * What Claude last asked the app to work on, if it is still fresh. Null is the ordinary state —
     * nobody has asked for advice, or the last one is more than a week old — and null plans his
     * sitting exactly as every phase before this one planned it.
     *
     * Read on every plan rather than cached: a caregiver can ask for advice in the middle of the
     * day, and the next module he opens should already know about it. It is one indexed row.
     *
     * A failure here is recorded and swallowed. The daily session is the thing Dimitris can do
     * alone; it must not fail to open because an optional advisory table could not be read.
     */
    suspend fun activeFocus(): Focus? = try {
        Focus.active(listOfNotNull(db.advice().newest()), known = null, now = systemNow())
    } catch (ce: CancellationException) {
        throw ce
    } catch (e: Throwable) {
        errors.record("active focus", e)
        null
    }

    /**
     * The one sync, held for the life of the app rather than built per use: it owns the guard that
     * keeps two runs from overlapping, and the last result the caregiver's screen shows. It reads
     * the database and the settings through lambdas, so a backup import — which swaps the database
     * underneath everything — leaves it holding nothing stale.
     *
     * Nothing here touches the network until a caregiver has typed both an address and a token.
     */
    val sync: SyncEngine by lazy {
        SyncEngine(
            client = HttpSyncClient(baseUrl = { settings.syncUrl.first() }, token = { secrets.getSyncToken() }),
            store = DaoSyncStore { SyncDaos.of(db) },
            files = files,
            settings = settings,
            onPulled = { dbGeneration.update { it + 1 } },
            record = { where, e -> errors.record(where, e) },
            configured = { settings.syncUrl.first().isNotBlank() && !secrets.getSyncToken().isNullOrBlank() },
        )
    }

    /**
     * Recording-or-TTS voice for items. Built per use so it always sees the current db and settings,
     * and routed through [voice] so the one-sound-at-a-time rule still holds for spoken items.
     */
    val speaker: ItemSpeaker
        get() = ItemSpeaker(voice::speak, recordingFor = { items.modelRecording(it) }, play = { voice.play(it) }, rate = { settings.speechRate.first() }, resolve = { files.resolve(it) })

    /**
     * Therapy modules in Today-screen order. Empty in phase 0; each later phase adds one. The
     * arcade is last and, unlike the rest, is off until a caregiver switches it on — see
     * [gr.dimitris.app.core.settings.Settings.DEFAULT_OFF].
     */
    val modules: List<Module> =
        listOf(WordCoachModule, NumbersModule, SingSayModule, ScriptsModule, SentencesModule, TraceModule, ArcadeModule)

    /**
     * Bumped every time [db] is replaced. Screens key their flows on it, because a Flow from the
     * old database never emits again once that database is closed.
     */
    val dbGeneration = MutableStateFlow(0)

    /** After a backup import, close and reopen so the restored file is read. */
    fun reopenDatabase() {
        db.close()
        db = AppDatabase.open(app)
        dbGeneration.update { it + 1 }
    }
}

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
