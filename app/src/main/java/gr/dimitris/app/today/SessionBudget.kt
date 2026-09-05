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
     */
    fun <T> share(items: List<T>, allowance: Int, atomic: Boolean): List<T> =
        if (atomic) items else items.take(allowance)
}
