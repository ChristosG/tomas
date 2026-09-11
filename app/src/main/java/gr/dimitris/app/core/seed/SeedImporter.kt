package gr.dimitris.app.core.seed

import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.Source
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.greek.Gender
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
            val stamp = SeedIds.stamp(manifest.version)
            // Every item the device has ever had, not only the seeded ones: see newEntries.
            val all = graph.db.items().all()
            for (entry in newEntries(manifest, onDevice(all))) {
                val image = entry.image?.let { copyAsset("seed/$it") }
                // Nothing here comes from the clock or from a fresh UUID — see [row] and SeedIds.
                graph.items.save(row(entry, manifest.version, image?.let { graph.files.relativize(it) }), at = stamp)
            }
            // What a bump owes a device that already has the vocabulary: not the words — she may
            // have re-typed one, and then it is hers — but the grading. Phase 13 gave every bundled
            // word a tier and every bundled noun a gender, and without this the two hundred that
            // shipped before it would have stayed tier 1 with no gender on every phone already in
            // use, for ever: his dots 3 to 5 would have drawn on the new words alone and the article
            // levels would have gone on guessing from endings. The category travels the same road
            // since v5, and for a sharper reason: a phone that imported v4 has the twenty long
            // sentences filed under «Μέρη» and «Χρόνος», where the word coach and the talk board
            // would go on offering them for ever. See [regraded].
            val regrade = regraded(manifest, all, stamp)
            if (regrade.isNotEmpty()) graph.db.items().upsertAll(regrade)
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
         * One bundled word as a row — the same row on every phone that ever imports this manifest.
         *
         * Nothing in it comes from the device: the id is derived from the text ([SeedIds.item]) and
         * the timestamps from the manifest version ([SeedIds.stamp]). That is what makes the second
         * caregiver's install a no-op on the server instead of a wave of "newer" rows that reverts
         * every photo, voice, pin, price and deletion the family had made to the bundled words.
         *
         * [imagePath] is the copied pictogram's path on this phone, which is
         * `photos/<the file name in the manifest>` and therefore the same everywhere too.
         */
        fun row(entry: SeedEntry, version: Int, imagePath: String?): Item = Item(
            id = SeedIds.item(entry.text),
            text = entry.text,
            kind = runCatching { ItemKind.valueOf(entry.kind) }.getOrDefault(ItemKind.WORD),
            category = categoryOf(entry),
            imagePath = imagePath,
            source = Source.SEED,
            tier = Difficulty.clamp(entry.tier),
            gender = genderOf(entry),
            createdAt = SeedIds.stamp(version),
            updatedAt = SeedIds.stamp(version),
        )

        /**
         * The gender a bundled word claims, normalised to what the column holds — `"M"`, `"F"`,
         * `"N"` — and null for anything else.
         *
         * Through [gr.dimitris.app.core.greek.Gender] rather than stored raw, so a typo in
         * `words.json` («Θ» for «F», a stray space) becomes "nobody has said" and the ending is read
         * as it always was, instead of a value in the database that no reader understands.
         */
        internal fun genderOf(entry: SeedEntry): String? = Gender.of(entry.gender)?.code

        /**
         * Which shelf a bundled word sits on, with anything unreadable as [Category.CUSTOM] — the
         * same reading [row] has always done, named so that [regraded] can do it too.
         */
        internal fun categoryOf(entry: SeedEntry): Category =
            runCatching { Category.valueOf(entry.category) }.getOrDefault(Category.CUSTOM)

        /**
         * The bundled words already on the device whose grading a version bump may correct, and no
         * others. Everything else the device holds is left exactly as it is.
         *
         * Three things are corrected: the **tier** and the **gender** since phase 13's first bump,
         * and the **category** since v5. The category is here because a shelf is not decoration —
         * «Λέξεις» and «Μίλα» both exclude [Category.SINGING] by name, so a phone that imported v4,
         * where the twenty long sentences were still filed under «Μέρη» and «Χρόνος», would have
         * gone on offering them as talk-board cards and word-coach targets for ever.
         *
         * The same three conditions as the dialogues' [gr.dimitris.app.core.seed.ScriptSeedImporter.regraded],
         * and all three are about not touching a caregiver's work:
         *
         * * **the row is ours** — its id is [SeedIds.item] for this text, so a word she typed
         *   herself is not ours and is never seen, even when it says the same thing;
         * * **the word is still ours** — the row's text is the manifest's, character for character
         *   once trimmed. A word she re-typed («καφες» without its tonos) is hers now, and hers keeps
         *   whatever tier, gender and category she gave it;
         * * **nobody has touched it since it was seeded** — `updatedAt` is at or below [stamp], which
         *   is [SeedIds.EPOCH] plus a version number. Anything the family does is stamped with a real
         *   clock and is eleven digits larger, so a word she edited in the editor, or one that
         *   arrived from another phone over sync, is out of reach of this by arithmetic.
         *
         * A deleted row is left deleted and ungraded: she decided against that word, and a bump that
         * rewrote its `updatedAt` would push a pointless row at the other phones on the next sync.
         *
         * Rows that already say what the manifest says are left out too, so a re-import writes
         * nothing and no `updatedAt` moves for a change nobody made.
         */
        fun regraded(manifest: SeedManifest, onDevice: List<Item>, stamp: Long): List<Item> {
            val byId = onDevice.associateBy { it.id }
            return manifest.items.distinctBy { SeedText.key(it.text) }.mapNotNull { entry ->
                val row = byId[SeedIds.item(entry.text)] ?: return@mapNotNull null
                if (row.deleted || row.updatedAt > stamp) return@mapNotNull null
                if (row.text.trim() != entry.text.trim()) return@mapNotNull null
                val tier = Difficulty.clamp(entry.tier)
                val gender = genderOf(entry)
                val category = categoryOf(entry)
                if (row.tier == tier && row.gender == gender && row.category == category) null
                else row.copy(tier = tier, gender = gender, category = category, updatedAt = stamp)
            }
        }

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
