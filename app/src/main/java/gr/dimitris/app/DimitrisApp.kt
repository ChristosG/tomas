package gr.dimitris.app

import android.app.Application
import gr.dimitris.app.core.log.CrashHandler
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
        graph.scope.launch { SeedImporter(graph).importIfNeeded() }
    }
}
