package gr.dimitris.app.core.seed

import com.google.gson.Gson
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.LineDraft
import gr.dimitris.app.core.data.Script
import gr.dimitris.app.core.data.Source
import gr.dimitris.app.core.data.Speaker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class SeedLine(val speaker: String, val text: String)
data class SeedScript(val title: String, val lines: List<SeedLine>)
data class ScriptSeedManifest(val version: Int, val scripts: List<SeedScript>) {
    companion object { fun parse(json: String): ScriptSeedManifest = Gson().fromJson(json, ScriptSeedManifest::class.java) }
}

/** Loads the bundled dialogues once per manifest version; never touches caregiver-made scripts. */
class ScriptSeedImporter(private val graph: AppGraph) {

    suspend fun importIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val manifest = graph.app.assets.open("seed/scripts.json").bufferedReader().use { ScriptSeedManifest.parse(it.readText()) }
            if (graph.settings.scriptsSeedVersion.first() >= manifest.version) return@withContext
            for (s in newScripts(manifest, onDevice(graph.db.scripts().allScripts()))) {
                graph.scripts.save(null, s.title, s.lines.map { LineDraft(speakerOf(it.speaker), it.text) }, source = Source.SEED)
            }
            graph.settings.setScriptsSeedVersion(manifest.version)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            graph.errors.record("script seed import", e)
        }
    }

    companion object {
        /** An unknown tag is the other person talking: better a wrong avatar than a lost line. */
        fun speakerOf(name: String): Speaker = runCatching { Speaker.valueOf(name.trim().uppercase()) }.getOrDefault(Speaker.OTHER)

        /**
         * What counts as "already on this device" for the dialogue seed: every script the device
         * holds, deleted ones included.
         *
         * Deleted counts because a caregiver who removes a shipped dialogue has decided against it.
         * Matching only live scripts brought it back on the next version bump — «Με έναν φίλο»,
         * deleted during the task-3 checks, reappeared beside the new taxi dialogue — and would go
         * on doing so on every bump, with nothing said anywhere.
         */
        fun onDevice(scripts: List<Script>): Set<String> = scripts.mapTo(mutableSetOf()) { it.title }

        /**
         * The dialogues a version bump owes a device that has been in use: the ones whose title is
         * not there yet. [existingTitles] is [onDevice] over every script, hers as well as ours — a
         * script she wrote and named "Στην καφετέρια" is the one she rehearses, and a second copy
         * beside it would be worse than no bump at all.
         */
        fun newScripts(manifest: ScriptSeedManifest, existingTitles: Set<String>): List<SeedScript> {
            val onDevice = existingTitles.mapTo(mutableSetOf(), SeedText::key)
            return manifest.scripts.distinctBy { SeedText.key(it.title) }.filter { SeedText.key(it.title) !in onDevice }
        }
    }
}
