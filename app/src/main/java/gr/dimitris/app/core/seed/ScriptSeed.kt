package gr.dimitris.app.core.seed

import com.google.gson.Gson
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.LineDraft
import gr.dimitris.app.core.data.Script
import gr.dimitris.app.core.data.ScriptLine
import gr.dimitris.app.core.data.Source
import gr.dimitris.app.core.data.Speaker
import gr.dimitris.app.core.difficulty.Difficulty
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * One bundled turn. [intent] is what a good answer has to convey and is written on his own lines
 * only — the other person's are said, not judged.
 */
data class SeedLine(val speaker: String, val text: String, val intent: String? = null)

/**
 * One bundled dialogue. [tier] is how hard it is, 1 to 5, and it goes onto every one of its lines:
 * a dialogue is as hard as its hardest turn, and the seed has no reason to grade them apart.
 *
 * Zero when the manifest left it out — Gson fills a JVM zero rather than the default declared here —
 * which [gr.dimitris.app.core.difficulty.Difficulty.clamp] reads as the easiest tier.
 */
data class SeedScript(val title: String, val lines: List<SeedLine>, val tier: Int = ScriptLine.DEFAULT_TIER)
data class ScriptSeedManifest(val version: Int, val scripts: List<SeedScript>) {
    companion object { fun parse(json: String): ScriptSeedManifest = Gson().fromJson(json, ScriptSeedManifest::class.java) }
}

/** Loads the bundled dialogues once per manifest version; never touches caregiver-made scripts. */
class ScriptSeedImporter(private val graph: AppGraph) {

    suspend fun importIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val manifest = graph.app.assets.open("seed/scripts.json").bufferedReader().use { ScriptSeedManifest.parse(it.readText()) }
            if (graph.settings.scriptsSeedVersion.first() >= manifest.version) return@withContext
            val stamp = SeedIds.stamp(manifest.version)
            val fresh = newScripts(manifest, onDevice(graph.db.scripts().allScripts()))
            // What a bump owes a device that already has the dialogue: not the words — it may have
            // been re-worded, and then it is hers — but the grading. Phase 12 gave every bundled
            // dialogue a tier and every turn of his an intent, and without this the six that shipped
            // before it would have stayed tier 1 with nothing said about what their turns are after,
            // on every phone already in use, for ever.
            for (s in manifest.scripts.distinctBy { SeedText.key(it.title) } - fresh.toSet()) {
                val kept = graph.scripts.load(SeedIds.script(s.title)) ?: continue
                val rows = regraded(s.title, Difficulty.clamp(s.tier), s.lines, kept.lines, stamp)
                if (rows.isNotEmpty()) graph.db.scripts().upsertLines(rows)
            }
            for (s in fresh) {
                // Fixed ids, derived from the dialogue and the turn's place in it: two phones that
                // import the same bundled dialogue write the same rows, so sync merges them instead
                // of leaving Dimitris «Στην καφετέρια» three times over. See SeedIds.
                val tier = Difficulty.clamp(s.tier)
                graph.scripts.save(
                    SeedIds.script(s.title), s.title,
                    s.lines.map { LineDraft(speakerOf(it.speaker), it.text, tier = tier, intent = it.intent) },
                    source = Source.SEED,
                    seedIds = { i -> SeedIds.line(s.title, i) to SeedIds.lineItem(s.title, i) },
                    // Not the clock: a second install's copies must not out-rank the first
                    // install's work on the same dialogue. See SeedIds.EPOCH.
                    at = stamp,
                )
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

        /**
         * The lines of a bundled dialogue already on the device that a version bump may re-grade,
         * and no others. Everything else the device holds is left exactly as it is.
         *
         * Three conditions, and all three are about not touching a caregiver's work:
         *
         * * **the row is ours** — its id is [SeedIds.line] for this title and this position, so a
         *   dialogue she wrote and happened to call «Στην καφετέρια» is not ours and is never seen;
         * * **the words are still ours** — the item's text is the seed's, character for character
         *   once trimmed. A turn she re-worded is hers now, and hers keeps its tier and its intent;
         * * **nobody has touched it since it was seeded** — `updatedAt` is at or below [stamp], which
         *   is [SeedIds.EPOCH] plus a version number. Anything the family does is stamped with a real
         *   clock and is eleven digits larger, so a line she edited in the editor, or one that
         *   arrived from another phone over sync, is out of reach of this by arithmetic.
         *
         * Rows that already say what the manifest says are left out too, so a re-import writes
         * nothing and no `updatedAt` moves for a change nobody made.
         */
        fun regraded(
            title: String,
            tier: Int,
            seedLines: List<SeedLine>,
            onDevice: List<Pair<ScriptLine, Item>>,
            stamp: Long,
        ): List<ScriptLine> {
            val byId = onDevice.associateBy { it.first.id }
            return seedLines.mapIndexedNotNull { i, seed ->
                val (row, item) = byId[SeedIds.line(title, i)] ?: return@mapIndexedNotNull null
                if (row.deleted || row.updatedAt > stamp) return@mapIndexedNotNull null
                if (item.text.trim() != seed.text.trim()) return@mapIndexedNotNull null
                val intent = seed.intent?.trim()?.takeIf { it.isNotEmpty() }
                if (row.tier == tier && row.intent == intent) null else row.copy(tier = tier, intent = intent, updatedAt = stamp)
            }
        }
    }
}
