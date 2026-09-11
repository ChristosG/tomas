package gr.dimitris.app.today

/**
 * How long today's session is allowed to be.
 *
 * Every module plans for itself and none of them knows about the others, so with eight modules due
 * at once the session became an hour of work. A session is one sitting: it is capped at
 * [MAX_SESSION_ITEMS] exercises, shared out evenly, and never shrunk below [MIN_PER_MODULE] — a
 * module worth entering is worth more than one exercise. What does not fit was never marked done,
 * so it is still due tomorrow.
 */
object SessionBudget {
    /** One sitting, across every module together. */
    const val MAX_SESSION_ITEMS = 15

    /** Below this a module is not worth switching to. Small sessions grow past the cap instead. */
    const val MIN_PER_MODULE = 3

    /** Exercises each of [moduleCount] modules may contribute today. */
    fun allowance(moduleCount: Int): Int =
        if (moduleCount <= 0) 0 else maxOf(MIN_PER_MODULE, MAX_SESSION_ITEMS / moduleCount)

    /**
     * What one module really contributes. A module that runs as one unit — a dialogue — keeps its
     * whole list: cutting it changed nothing about what ran and only made the session row's
     * `plannedItemCount` a number the module was going to overshoot. The dialogue is held to a
     * sitting's length by the editor's own cap on how many turns a dialogue may have, not here.
     *
     * [granularity] is how many items one of the module's exercises is worth
     * ([gr.dimitris.app.modules.Module.granularity]). The share is cut to a whole number of exercises,
     * because the alternative is a promise the module cannot keep: «Βήματα» is two items per task, so a
     * budget of three bought one task and wrote two rows, and the session row said 3 planned / 2 done on
     * every sitting that tile was in. Never below one exercise — a module the session opens at all is
     * owed one, and then the planned count is the truth about it rather than one less.
     */
    fun <T> share(items: List<T>, allowance: Int, atomic: Boolean, granularity: Int = 1): List<T> {
        if (atomic) return items
        val unit = granularity.coerceAtLeast(1)
        return items.take((allowance - allowance % unit).coerceAtLeast(unit))
    }
}
