package gr.dimitris.app.core.greek

/**
 * The clock and the week, in the words he hears them in.
 *
 * Pure and Android-free — no `LocalTime`, no `Calendar`, no locale — because level 13 is not about
 * what time it is now. It is about reading a face that was drawn for him and naming the day after a
 * day that was named for him, and both have to be the same on every phone, in every timezone, at
 * every hour the app happens to be opened. A clock exercise that changed meaning at midnight would be
 * a clock exercise nobody could test.
 *
 * A time is one number: minutes on a twelve-hour face, 0 until [MINUTES]. That keeps every option he
 * may tap an `Int`, like every other option in the module, so nothing in the exercise machinery has to
 * learn a new type for it.
 */
object GreekTime {
    /** Minutes on a twelve-hour face. Twelve o'clock is 0; the app never shows him AM and PM. */
    const val MINUTES = 12 * 60

    /** Monday first, the way a Greek week and a Greek calendar are both written. */
    val days: List<String> = listOf("Δευτέρα", "Τρίτη", "Τετάρτη", "Πέμπτη", "Παρασκευή", "Σάββατο", "Κυριακή")

    /**
     * The hours as a clock is read: feminine, because it is «μία η ώρα», and twelve at index 0 so
     * that the index is the hand's own position.
     */
    private val hours = listOf("δώδεκα", "μία", "δύο", "τρεις", "τέσσερις", "πέντε", "έξι", "επτά", "οκτώ", "εννέα", "δέκα", "έντεκα")

    /** A day index from anywhere, brought into 0..6. Negative [index] included. */
    fun day(index: Int): String = days[wrap(index, days.size)]

    /** Which day it is [plus] days after [from]. The whole of level 13's date question. */
    fun dayAfter(from: Int, plus: Int): Int = wrap(from + plus, days.size)

    /** A time from anywhere, brought onto the face. */
    fun normalise(minutes: Int): Int = wrap(minutes, MINUTES)

    /** 0..11, where 0 is twelve: the hour hand's own position. */
    fun hourOf(minutes: Int): Int = normalise(minutes) / 60

    fun minuteOf(minutes: Int): Int = normalise(minutes) % 60

    /** The time as a twelve-hour face, and the only place in the module digits carry a colon. */
    fun digits(minutes: Int): String {
        val h = hourOf(minutes)
        return "${if (h == 0) 12 else h}:${minuteOf(minutes).toString().padStart(2, '0')}"
    }

    /**
     * The time as it is said: «τρεις ακριβώς», «τρεις και τέταρτο», «τρεις και μισή»,
     * «τέσσερις παρά τέταρτο».
     *
     * Past the half hour Greek counts *down to the next hour* — «τέσσερις παρά είκοσι» for 3:40 — so
     * the hour in the words is not the hour on the hand, and a man learning to read a face has to hear
     * that or the face and the words will never agree. The quarters get their own words because that
     * is what they are called; «τρεις και δεκαπέντε» is a train timetable, not a question to a person.
     */
    fun words(minutes: Int): String {
        val h = hourOf(minutes)
        val m = minuteOf(minutes)
        val next = hours[(h + 1) % 12]
        return when {
            m == 0 -> "${hours[h]} ακριβώς"
            m == 15 -> "${hours[h]} και τέταρτο"
            m == 30 -> "${hours[h]} και μισή"
            m == 45 -> "$next παρά τέταρτο"
            m < 30 -> "${hours[h]} και ${GreekNumbers.words(m)}"
            else -> "$next παρά ${GreekNumbers.words(60 - m)}"
        }
    }

    private fun wrap(n: Int, size: Int): Int = ((n % size) + size) % size
}
