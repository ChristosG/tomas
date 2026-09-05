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
        graph = AppGraph(this)
        CrashHandler.install { graph.db.errorLogs() }
        graph.scope.launch { SeedImporter(graph).importIfNeeded() }
    }
}
