package gr.dimitris.app

import android.content.Context
import androidx.compose.runtime.staticCompositionLocalOf
import gr.dimitris.app.core.audio.ImageStore
import gr.dimitris.app.core.audio.MediaFiles
import gr.dimitris.app.core.audio.Player
import gr.dimitris.app.core.audio.Recorder
import gr.dimitris.app.core.data.AppDatabase
import gr.dimitris.app.core.data.ItemRepository
import gr.dimitris.app.core.log.ErrorReporter
import gr.dimitris.app.core.settings.Settings
import gr.dimitris.app.core.speech.AndroidTextToSpeech
import gr.dimitris.app.core.speech.TextToSpeech
import gr.dimitris.app.modules.Module
import gr.dimitris.app.ui.theme.Feedback
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

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
    val recorder = Recorder(app, files)
    val player = Player()
    val feedback = Feedback(app)
    val errors = ErrorReporter(scope) { db.errorLogs() }

    /** Always built from the current db, so it survives a backup import. */
    val items: ItemRepository get() = ItemRepository(db.items(), db.recordings())

    /** Therapy modules in Today-screen order. Empty in phase 0; each later phase adds one. */
    val modules: List<Module> = emptyList()

    /** After a backup import, close and reopen so the restored file is read. */
    fun reopenDatabase() {
        db.close()
        db = AppDatabase.open(app)
    }
}

val LocalAppGraph = staticCompositionLocalOf<AppGraph> { error("AppGraph not provided") }
