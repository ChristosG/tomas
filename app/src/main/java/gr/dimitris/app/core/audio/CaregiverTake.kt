package gr.dimitris.app.core.audio

/**
 * What a caregiver's editor does with a finished take.
 *
 * The modules already refuse a silent take of *his* voice ([Recorded.isSilent]). The editors did
 * not, and a caregiver's take is the worse of the two to lose: hers is the model. A silent .m4a
 * saved as «η φωνή σου, ως πρότυπο» is an «Άκου» that plays nothing at all, on a screen where
 * hearing the word is the whole help on offer — and neither of them would find out until he was
 * sitting in front of it.
 *
 * A verdict on its own, with no view model and no [gr.dimitris.app.AppGraph] around it, so the
 * decision can be watched by a plain unit test: the editors have Android all the way down.
 */
object CaregiverTake {
    /**
     * The take to attach to the item or the line, or null when nothing was said into it.
     *
     * A refused take is deleted here rather than left for a later sweep: the file is a few hundred
     * kilobytes of nothing, and nothing will ever point at it again.
     */
    fun keptOrDiscarded(take: Recorded): Recorded? {
        if (!take.isSilent) return take
        take.file.delete()
        return null
    }
}
