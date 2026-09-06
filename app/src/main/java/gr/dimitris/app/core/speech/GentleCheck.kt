package gr.dimitris.app.core.speech

/**
 * What the app does with what it heard, for one word, one dialogue turn or one phrase.
 *
 * Chris' finding after the first field test was that recording a take checked nothing: a silent take
 * "passed", and so did a wrong word. Spec §12 answers it with a check that is gentle rather than
 * absent — the phone compares, and a miss buys him *one* nudge, never a refusal:
 *
 * - it matched: say so, and confirm the turn for him at the cue level he was on;
 * - it did not, the first time: «Δοκίμασε ξανά», the cue unchanged, «Άκου» there, the microphone one
 *   tap away again;
 * - it did not, again: nothing more is asked of him. «Το είπα!» comes back and confirms exactly as
 *   it did before recognition existed.
 *
 * A window that heard nothing at all counts as one of those tries. It has to: two dead windows in a
 * row would otherwise leave a man who *had* said the word with no way to say he had — which is the
 * wall this whole task exists to remove.
 *
 * Held apart from the three view models because it is the same machine in all of them, and because
 * a state machine that decides whether he may confirm his own work should be provable in a plain
 * test rather than only on a phone.
 */
class GentleCheck {
    enum class Verdict {
        /** Heard and matched: confirm for him. */
        MATCHED,

        /** Missed once. Nudge, and leave everything else exactly where it was. */
        NUDGE,

        /** Missed again. Stop asking: «Το είπα!» is his to press. */
        OPEN,

        /**
         * The window came back with no words at all — silence, a sound that matched nothing, a
         * session that hung to its bound, or his own «Στοπ» before he had started.
         *
         * It costs him **nothing**: no try, no nudge counted against him, the cue where it was.
         * A phone that did not hear him has said nothing about whether he spoke, and it may not be
         * allowed to decide anything on that basis.
         */
        NOTHING,
    }

    /**
     * How many times the phone heard him say something *else*. Only that: a window that heard
     * nothing is not a try, and neither is one the phone could not open. This is the number the
     * caregiver reads in the row and the number the confirm is gated on, and both of them must mean
     * "the check disagreed with him", never "the microphone had a bad moment".
     */
    var tries: Int = 0
        private set

    /** The best text of the last window, or null when nothing was heard. */
    var heard: String? = null
        private set

    var matched: Boolean = false
        private set

    /** «Δοκίμασε ξανά» is on the screen: one miss, and nothing else has changed. */
    val nudging: Boolean get() = !matched && tries in 1 until TRIES_BEFORE_CONFIRM

    /** Whether «Το είπα!» is his to press: after a match, or after he has been asked twice. */
    val canConfirm: Boolean get() = matched || tries >= TRIES_BEFORE_CONFIRM

    /**
     * What one window came back with. [text] is null when nothing was heard at all.
     */
    fun record(text: String?, isMatch: Boolean): Verdict {
        heard = text
        matched = isMatch
        if (text == null) return Verdict.NOTHING
        if (isMatch) return Verdict.MATCHED
        // A real mismatch, and the only thing that counts as one.
        tries++
        return if (tries < TRIES_BEFORE_CONFIRM) Verdict.NUDGE else Verdict.OPEN
    }

    /** Which green button the bottom of the screen is showing. */
    enum class Primary {
        /**
         * The settings have not been read yet. «Το είπα!» is drawn, greyed: the layout must not
         * jump, and a tap landing in this instant must not confirm a word the recogniser was about
         * to be asked about — nor open a window when he meant to confirm. It lasts one DataStore
         * read, on the first item of a run only.
         */
        WAITING,

        /** «Μίλα»: the recogniser has not had its go yet. */
        SPEAK,

        /** «Το είπα!», and it confirms exactly as it did before recognition existed. */
        CONFIRM,
    }

    companion object {
        /**
         * Which green primary belongs on the screen. A pure function because it is the one place
         * where a button changes what it does under his thumb, and because the same three lines
         * would otherwise be written out in three screens.
         */
        fun primaryFor(resolved: Boolean, sttOn: Boolean, canConfirm: Boolean): Primary = when {
            !resolved -> Primary.WAITING
            sttOn && !canConfirm -> Primary.SPEAK
            else -> Primary.CONFIRM
        }

        /**
         * One nudge, and no more. Two goes is what a therapist gives before moving on; a third would
         * be the phone insisting, and insisting is what makes a man stop trying.
         */
        const val TRIES_BEFORE_CONFIRM = 2

        /** The nudge itself. Not «λάθος», not «όχι»: the invitation is what is being repeated. */
        const val TRY_AGAIN = "Δοκίμασε ξανά"

        /** Said over the microphone button that opens a window. */
        const val SPEAK = "Μίλα"
    }
}
