package gr.dimitris.app.today

/**
 * What the end of a session says out loud and on the screen.
 *
 * Greek has no "1 ασκήσεις": one exercise is «1 άσκηση». The number is his, so it is worth being
 * right about, and the same sentence is spoken and shown so hearing and reading agree.
 */
object SessionWording {
    /** Said and shown when a session ends with nothing said out loud. No score, an invitation. */
    const val NOTHING_DONE = "Τα λέμε ξανά αργότερα."

    /** "Έκανες 3 ασκήσεις σήμερα: Λέξεις, Αριθμοί." — [titles] are the modules he actually practised. */
    fun summary(count: Int, titles: List<String>): String {
        if (count <= 0) return NOTHING_DONE
        val exercises = if (count == 1) "1 άσκηση" else "$count ασκήσεις"
        val what = titles.filter { it.isNotBlank() }.joinToString(", ")
        return if (what.isEmpty()) "Έκανες $exercises σήμερα." else "Έκανες $exercises σήμερα: $what."
    }
}
