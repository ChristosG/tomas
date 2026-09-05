package gr.dimitris.app

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
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
import gr.dimitris.app.core.settings.Settings
import gr.dimitris.app.core.speech.AndroidSpeechToText
import gr.dimitris.app.core.speech.AndroidTextToSpeech
import gr.dimitris.app.core.speech.ItemSpeaker
import gr.dimitris.app.core.speech.SpeechToText
import gr.dimitris.app.core.speech.TextToSpeech
import gr.dimitris.app.modules.Module
import gr.dimitris.app.modules.numbers.NumbersModule
import gr.dimitris.app.modules.singsay.SingSayModule
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

    /** Always built from the current db, so it survives a backup import. */
    val items: ItemRepository get() = ItemRepository(db.items(), db.recordings(), files::relativize)

    /** Always built from the current db, so it survives a backup import. */
    val scripts: ScriptRepository get() = ScriptRepository(db.scripts(), items)

    /** Always built from the current db, so it survives a backup import. */
    val scheduler: Scheduler get() = Scheduler(db.schedules())

    /**
     * Recording-or-TTS voice for items. Built per use so it always sees the current db and settings,
     * and routed through [voice] so the one-sound-at-a-time rule still holds for spoken items.
     */
    val speaker: ItemSpeaker
        get() = ItemSpeaker(voice::speak, recordingFor = { items.modelRecording(it) }, play = { voice.play(it) }, rate = { settings.speechRate.first() }, resolve = { files.resolve(it) })

    /** Therapy modules in Today-screen order. Empty in phase 0; each later phase adds one. */
    val modules: List<Module> = listOf(WordCoachModule, NumbersModule, SingSayModule)

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
