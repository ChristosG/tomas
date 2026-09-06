package gr.dimitris.app.core.seed

import com.google.gson.Gson
import gr.dimitris.app.core.greek.Greek

data class SeedEntry(val text: String, val kind: String, val category: String, val image: String?, val arasaacId: Int?)
data class SeedManifest(val version: Int, val items: List<SeedEntry>) {
    companion object {
        fun parse(json: String): SeedManifest = Gson().fromJson(json, SeedManifest::class.java)
    }
}

/**
 * How a seed importer decides "is this already on the device": trimmed, lower-cased and without
 * accents. Caregivers type in a hurry — «θελω καφε» for «θέλω καφέ», «ΝΑΙ» for «Ναι» — and a
 * version bump that took those for new phrases would hand Dimitris the same card twice.
 */
object SeedText {
    fun key(text: String): String = Greek.stripAccents(Greek.normalize(text))
}

/**
 * The id a seeded row gets, derived from what the row *is* rather than from a fresh `UUID`.
 *
 * Three phones install the same APK and each import the same bundled vocabulary. With a random id
 * per device, «ψωμί» on Chris's phone and «ψωμί» on the father's are two different rows, and the
 * first sync — doing exactly what it is told — merges both onto all three phones. Two hundred words
 * become six hundred, and nobody can tell which copy carries the photo.
 *
 * Deriving the id from the text makes the two phones write the *same* row, so sync merges them
 * instead of multiplying them. It is a UUID version 3 (name-based) over the same
 * [SeedText.key] the importers already dedup by, so «ΝΑΙ» and «Ναι» are one word here too.
 *
 * This changes nothing on a device that has already imported: the importers still decide what to
 * add by text, so a row that is there stays as it is, with the id it was born with.
 */
object SeedIds {
    /**
     * The instant every seeded row claims to have been written, plus the manifest version.
     *
     * September 2020 — before the app existed, and far before any caregiver ever touched it. It is
     * a *constant*, never the clock, and that is the whole point. Two phones importing the same
     * manifest have to write byte-identical rows, or the second install's copies are newer than the
     * first install's photos, voices, pins, prices and deletions, and the first «Συγχρόνισε τώρα»
     * reverts all of them on all three phones. Anything the family actually does is stamped with a
     * real clock, so it always out-ranks an import; a later manifest version out-ranks an earlier
     * one; and re-importing the same version changes nothing at all.
     */
    const val EPOCH = 1_600_000_000_000L

    fun stamp(version: Int): Long = EPOCH + version

    fun item(text: String): String = of("seed:item:${SeedText.key(text)}")
    fun script(title: String): String = of("seed:script:${SeedText.key(title)}")
    fun line(title: String, position: Int): String = of("seed:line:${SeedText.key(title)}:$position")

    /**
     * The `Item` behind one dialogue turn. Its own namespace, not [item]: a turn worded «Ναι» and
     * the talk-board card «Ναι» are two different rows on purpose — the vocabulary importer excludes
     * script lines from its "already here" set for exactly that reason — and giving them one id
     * would fuse them.
     */
    fun lineItem(title: String, position: Int): String = of("seed:line-item:${SeedText.key(title)}:$position")

    private fun of(name: String): String =
        java.util.UUID.nameUUIDFromBytes(name.toByteArray(Charsets.UTF_8)).toString()
}
