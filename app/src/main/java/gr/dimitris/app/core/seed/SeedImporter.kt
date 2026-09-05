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
            // Every active item, not only the seeded ones: see newEntries.
            val existing = graph.db.items().allActive().map { it.text }.toSet()
            for (entry in newEntries(manifest, existing)) {
                val image = entry.image?.let { copyAsset("seed/$it") }
                graph.items.save(Item(text = entry.text, kind = runCatching { ItemKind.valueOf(entry.kind) }.getOrDefault(ItemKind.WORD),
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
         * What a version bump owes an install that already has the old seed: the entries whose text
         * is not on the device yet, and each of those once.
         *
         * Matching on text and not on a row id is what makes a bump safe — the items the caregiver
         * has edited, deleted or re-recorded keep their rows untouched, and a phrase added in
         * version 2 arrives beside them instead of resetting them. Anything she deleted comes back,
         * which is the price of not keeping a tombstone per seed text; it is a phrase she can
         * delete once more, not work she loses.
         *
         * [existingTexts] is every active item on the device, whatever its source, compared through
         * [SeedText.key]. Looking only at the seeded rows was the phase 4 defect: a phrase the
         * caregiver had typed herself, or a seed text she had edited, counted as missing and the
         * bump handed Dimitris a second card for a phrase he already had.
         */
        fun newEntries(manifest: SeedManifest, existingTexts: Set<String>): List<SeedEntry> {
            val onDevice = existingTexts.mapTo(mutableSetOf(), SeedText::key)
            return manifest.items.distinctBy { SeedText.key(it.text) }.filter { SeedText.key(it.text) !in onDevice }
        }
    }
}
