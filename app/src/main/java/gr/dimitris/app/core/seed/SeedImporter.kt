package gr.dimitris.app.core.seed

import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File

/** Loads the bundled ARASAAC vocabulary once per manifest version. Never touches caregiver items. */
class SeedImporter(private val graph: AppGraph) {

    suspend fun importIfNeeded() = withContext(Dispatchers.IO) {
        try {
            val manifest = graph.app.assets.open("seed/seed.json").bufferedReader().use { SeedManifest.parse(it.readText()) }
            if (graph.settings.seedVersion.first() >= manifest.version) return@withContext
            // Every item the device has ever had, not only the seeded ones: see newEntries.
            for (entry in newEntries(manifest, onDevice(graph.db.items().all()))) {
                val image = entry.image?.let { copyAsset("seed/$it") }
                // The id comes from the word, not from a fresh UUID: see SeedIds. Two phones that
                // import the same vocabulary have to write the same row, or sync merges the two
                // copies onto both of them and Dimitris gets every word twice.
                graph.items.save(Item(id = SeedIds.item(entry.text), text = entry.text,
                    kind = runCatching { ItemKind.valueOf(entry.kind) }.getOrDefault(ItemKind.WORD),
                    category = runCatching { Category.valueOf(entry.category) }.getOrDefault(Category.CUSTOM),
                    imagePath = image?.let { graph.files.relativize(it) }, source = Source.SEED))
            }
            graph.settings.setSeedVersion(manifest.version)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Throwable) {
            graph.errors.record("seed import", e)
        }
    }

    /** Written to a .tmp file and renamed, so an interrupted copy never leaves a half a pictogram behind. */
    private fun copyAsset(name: String): File? = runCatching {
        val out = File(graph.files.photosDir, name.substringAfterLast('/'))
        if (!out.exists()) {
            val tmp = File(out.path + ".tmp")
            graph.app.assets.open(name).use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(out)) { tmp.delete(); error("Δεν αντιγράφηκε το $name") }
        }
        out
    }.getOrElse { graph.errors.record("seed asset $name", it); null }

    companion object {
        /**
         * What counts as "already on this device" for the vocabulary seed.
         *
         * Deleted rows count. A word the caregiver removed is a decision, not an absence: matching
         * only live rows handed it back to her on every version bump, for ever, with nothing said.
         *
         * Script lines do not count. They are ordinary `Item` rows made from dialogue turns, and a
         * turn typed as «Ναι» or «Πάμε» would silently block the identically-worded talk-board card
         * from ever arriving — the same filter the talk board and the word list already apply.
         */
        fun onDevice(items: List<Item>): Set<String> =
            items.filter { it.kind != ItemKind.SCRIPT_LINE }.mapTo(mutableSetOf()) { it.text }

        /**
         * What a version bump owes an install that already has the old seed: the entries whose text
         * is not on the device yet, and each of those once.
         *
         * Matching on text and not on a row id is what makes a bump safe — the items the caregiver
         * has edited, deleted or re-recorded keep their rows untouched, and a phrase added in
         * version 2 arrives beside them instead of resetting them.
         *
         * [existingTexts] is [onDevice] over every row the device holds, whatever its source and
         * whether or not it is deleted, compared through [SeedText.key]. Looking only at the seeded
         * rows was the phase 4 defect: a phrase the caregiver had typed herself, or a seed text she
         * had edited, counted as missing and the bump handed Dimitris a second card for a phrase he
         * already had. Looking only at the *live* rows was the same mistake for deletions.
         */
        fun newEntries(manifest: SeedManifest, existingTexts: Set<String>): List<SeedEntry> {
            val onDevice = existingTexts.mapTo(mutableSetOf(), SeedText::key)
            return manifest.items.distinctBy { SeedText.key(it.text) }.filter { SeedText.key(it.text) !in onDevice }
        }
    }
}
