package gr.dimitris.app.core.greek

import java.util.Locale

object Euro {
    /** Coins and notes Dimitris will actually handle: 0,50 € up to 50 €. In cents. */
    val denominations: List<Int> = listOf(50, 100, 200, 500, 1000, 2000, 5000)

    /** The most a price may be: 999.999,99 €. Anything longer is a typo, not a price. */
    const val MAX_CENTS = 99_999_999

    fun format(cents: Int): String = String.format(Locale.US, "%d,%02d €", cents / 100, cents % 100)

    /**
     * "3,50", "3.5", "3", "12,99€" → cents; anything else → null.
     *
     * Never throws: this runs on whatever a caregiver typed or pasted, so a number too long for an
     * Int, or one that would overflow the cents arithmetic, is a null like any other bad input.
     */
    fun parse(text: String): Int? {
        val m = Regex("""^\s*(\d+)(?:[.,](\d{1,2}))?\s*€?\s*$""").find(text) ?: return null
        val whole = m.groupValues[1].toIntOrNull() ?: return null
        val frac = m.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toInt()
        val cents = whole.toLong() * 100 + frac
        return if (cents <= MAX_CENTS) cents.toInt() else null
    }

    /**
     * The amount as words, for TTS. [format] is for the eye — Greek TTS reads "10,00 €" as
     * punctuation, and the spoken answer is the whole point of the euro exercises.
     */
    fun spoken(cents: Int): String {
        val euros = cents / 100
        val rest = cents % 100
        // GreekNumbers stops at a thousand; nothing he ever pays is near it, but a caregiver price is.
        if (euros !in 0..1000) return format(cents)
        return when {
            rest == 0 -> "${GreekNumbers.words(euros)} ευρώ"
            euros == 0 -> "${GreekNumbers.words(rest)} λεπτά"
            else -> "${GreekNumbers.words(euros)} ευρώ και ${GreekNumbers.words(rest)} λεπτά"
        }
    }
}
