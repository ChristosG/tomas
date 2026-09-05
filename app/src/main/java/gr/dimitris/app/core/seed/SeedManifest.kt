package gr.dimitris.app.core.seed

import com.google.gson.Gson

data class SeedEntry(val text: String, val kind: String, val category: String, val image: String?, val arasaacId: Int?)
data class SeedManifest(val version: Int, val items: List<SeedEntry>) {
    companion object {
        fun parse(json: String): SeedManifest = Gson().fromJson(json, SeedManifest::class.java)
    }
}
