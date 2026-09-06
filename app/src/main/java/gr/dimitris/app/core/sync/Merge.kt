package gr.dimitris.app.core.sync

/**
 * The one rule that decides whether a row that arrived from the server replaces the row on this
 * phone. It is the same rule the server applies to a pushed row, written twice on purpose: both
 * ends have to agree without asking each other, or two phones editing the same word offline would
 * settle differently depending on who spoke first.
 *
 * * A row this phone has never seen is always taken.
 * * An append-only row (an attempt, a logged error) is never overwritten: it is a fact that
 *   happened, not a value that can be corrected.
 * * Everything else is last-write-wins on `updatedAt`, **strictly** greater. A tie keeps what is
 *   already here, so a row that comes back around after a round trip does not churn the database
 *   and does not wake up every screen watching it.
 */
object Merge {
    fun decide(local: Map<String, Any?>?, remote: Map<String, Any?>, appendOnly: Boolean): Boolean {
        if (local == null) return true
        if (appendOnly) return false
        return Rows.updatedAt(remote) > Rows.updatedAt(local)
    }
}
