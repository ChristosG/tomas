package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.AttemptDao
import gr.dimitris.app.core.data.ModuleId

/**
 * Which modules one day's session is made of.
 *
 * Every module plans for itself and none of them knows about the others, so as soon as six were
 * enabled all six turned up every day: a sitting of fifteen exercises split six ways is three
 * apiece, and a session that touches everything a little is a session he finishes having practised
 * nothing. A day is [MAX_MODULES] modules at most — the word coach, which is what the app is for and
 * is in every session it is switched on for, and up to three others taken in turn.
 *
 * The turn is by least-recent use, read from the attempt rows the modules write for themselves: the
 * one he has not done for longest goes first, and one he has never done goes before all of them.
 * Nothing new is stored for it. A "last run" counter of its own could fall out of step with what he
 * really did — a module he left after one exercise would look practised — and the rows cannot.
 *
 * What is left out was never marked done, so it is still due tomorrow, and tomorrow it is the module
 * that has waited longest.
 */
object ModuleRotation {
    /** One sitting: the word coach and three others. */
    const val MAX_MODULES = 4

    /** Finding his words is the point of the app. It is in every session it is switched on for. */
    val ALWAYS = ModuleId.WORDCOACH

    /**
     * The modules today's session runs, in the order [candidates] came in — the Today order — so the
     * day opens with the same module every time instead of with whatever the rotation turned up.
     * Which modules are chosen rotates; the order they are done in does not, because a man who
     * cannot ask what is happening next is owed a session that starts the way yesterday's did.
     *
     * [candidates] are the enabled modules with something to do today. [lastUsedAt] is when each was
     * last practised (see the overload below); a module that is not in the map has never been
     * practised at all and goes ahead of every module that has.
     */
    fun choose(
        candidates: List<ModuleId>,
        lastUsedAt: Map<ModuleId, Long>,
        max: Int = MAX_MODULES,
        always: ModuleId = ALWAYS,
    ): List<ModuleId> {
        if (max <= 0) return emptyList()
        val ordered = candidates.distinct()
        // Nothing to choose between: a short day runs everything it has.
        if (ordered.size <= max) return ordered

        val kept = LinkedHashSet<ModuleId>()
        if (always in ordered) kept += always
        // Never practised first, then longest ago. The enum's own order breaks a tie, so two modules
        // he has never touched do not swap places between one reading of this and the next.
        for (id in ordered.filter { it != always }.sortedWith(compareBy({ lastUsedAt[it] ?: Long.MIN_VALUE }, { it.ordinal }))) {
            if (kept.size >= max) break
            kept += id
        }
        return ordered.filter { it in kept }
    }

    /** When each module was last practised, from the attempt rows it wrote. */
    suspend fun lastUsedAt(attempts: AttemptDao): Map<ModuleId, Long> =
        attempts.lastUsePerModule().associate { it.module to it.lastAt }
}
