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
