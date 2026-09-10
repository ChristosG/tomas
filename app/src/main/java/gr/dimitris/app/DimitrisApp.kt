package gr.dimitris.app

import android.app.Application
import gr.dimitris.app.core.difficulty.DifficultyInit
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
            // **First, before anything writes a preference.** A module that has just become
            // default-off stays on for a phone that already had it, and the only honest way to tell
            // an install that already existed from one made a minute ago is that its settings store
            // is not empty yet. See [Settings.grandfatherNewlyDefaultOff].
            runCatching { graph.settings.grandfatherNewlyDefaultOff() }
                .onFailure { graph.errors.record("modules grandfather", it) }
            SeedImporter(graph).importIfNeeded()
            ScriptSeedImporter(graph).importIfNeeded()
            // Where his five dots start, on a phone that was already being practised with before
            // they existed (spec §13). After the two importers, because two of the seven modules
            // read their answer out of the vocabulary and the dialogues; before the first sync, so a
            // phone that has just merged somebody else's rows is not what he is measured by.
            DifficultyInit.run(graph)
            // Last, and in the same coroutine: the seed has to be in the database before the first
            // sync reads it, or a fresh phone would push nothing and then merge the vocabulary it
            // was about to import anyway. Nothing waits for this — it is off the main thread, on the
            // graph's own scope, and a phone with no server address does not even open a socket.
            graph.sync.syncAtStart()
            // And on a phone that has no server to push to, the takes retention finished with go on
            // their own clock instead: there is no "after the push" to wait for, and a recordings
            // folder that only ever grows is not something his father should have to think about.
            graph.sync.retireUnsynced()
        }
        // Its own coroutine, because it never returns. Two of the six dots are read out of the
        // database, and the database can arrive *after* the app has started: a backup restored, or
        // the first sync pull bringing his vocabulary and his Leitner rows down onto a second phone.
        // The run above would have spent those two derivations on a database holding nothing but the
        // seed, and nothing would ever have asked again.
        graph.scope.launch { DifficultyInit.watch(graph) }
    }
}
