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
            val existing = graph.db.items().activeOfSource(Source.SEED).map { it.text.trim() }.toSet()
            for (entry in manifest.items) {
                if (entry.text.trim() in existing) continue
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

    private fun copyAsset(name: String): File? = runCatching {
        val out = File(graph.files.photosDir, name.substringAfterLast('/'))
        if (!out.exists()) graph.app.assets.open(name).use { input -> out.outputStream().use { input.copyTo(it) } }
        out
    }.getOrElse { graph.errors.record("seed asset $name", it); null }
}
