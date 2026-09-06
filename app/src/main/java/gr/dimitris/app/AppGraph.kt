package gr.dimitris.app

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.room.withTransaction
import gr.dimitris.app.caregiver.insights.ClaudeAdvisor
import gr.dimitris.app.core.audio.ImageStore
import gr.dimitris.app.core.audio.MediaFiles
import gr.dimitris.app.core.audio.Player
import gr.dimitris.app.core.audio.Recorder
import gr.dimitris.app.core.audio.ToneSynth
import gr.dimitris.app.core.audio.Voice
import gr.dimitris.app.core.data.AppDatabase
import gr.dimitris.app.core.data.ItemRepository
import gr.dimitris.app.core.data.ScriptRepository
import gr.dimitris.app.core.log.ErrorReporter
import gr.dimitris.app.core.scheduler.Scheduler
import gr.dimitris.app.core.secrets.SecretStore
import gr.dimitris.app.core.settings.Settings
import gr.dimitris.app.core.speech.AndroidSpeechToText
import gr.dimitris.app.core.speech.AndroidTextToSpeech
import gr.dimitris.app.core.speech.ItemSpeaker
import gr.dimitris.app.core.speech.SpeechToText
import gr.dimitris.app.core.speech.TextToSpeech
import gr.dimitris.app.modules.Module
import gr.dimitris.app.modules.arcade.ArcadeModule
import gr.dimitris.app.modules.numbers.NumbersModule
import gr.dimitris.app.modules.scripts.ScriptsModule
import gr.dimitris.app.modules.sentences.SentencesModule
import gr.dimitris.app.modules.singsay.SingSayModule
import gr.dimitris.app.modules.trace.TraceModule
import gr.dimitris.app.modules.wordcoach.WordCoachModule
import gr.dimitris.app.ui.theme.Feedback
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
    val stt: SpeechToText = AndroidSpeechToText(app)
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
