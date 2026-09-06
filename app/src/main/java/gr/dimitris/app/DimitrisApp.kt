package gr.dimitris.app

import android.app.Application
import gr.dimitris.app.core.log.CrashHandler
import gr.dimitris.app.core.seed.ScriptSeedImporter
import gr.dimitris.app.core.seed.SeedImporter
import kotlinx.coroutines.launch

class DimitrisApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        // First, so a crash while the graph is being built is still written down. Until the graph
        // exists the dao lambda throws, which CrashHandler swallows.
        CrashHandler.install(this) {
            check(::graph.isInitialized) { "Η βάση δεν έχει ανοίξει ακόμα" }
            graph.db.errorLogs()
        }
        graph = AppGraph(this)
        // One coroutine, in order: the dialogues are imported after the vocabulary, never beside it,
        // so two importers are never writing items at the same moment on a first run.
        graph.scope.launch {
            SeedImporter(graph).importIfNeeded()
            ScriptSeedImporter(graph).importIfNeeded()
            // Last, and in the same coroutine: the seed has to be in the database before the first
            // sync reads it, or a fresh phone would push nothing and then merge the vocabulary it
            // was about to import anyway. Nothing waits for this — it is off the main thread, on the
            // graph's own scope, and a phone with no server address does not even open a socket.
            graph.sync.syncAtStart()
        }
    }
}
