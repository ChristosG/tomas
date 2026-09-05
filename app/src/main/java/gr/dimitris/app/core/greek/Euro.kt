package gr.dimitris.app.core.greek

import java.util.Locale

object Euro {
    /** Coins and notes Dimitris will actually handle: 0,50 € up to 50 €. In cents. */
    val denominations: List<Int> = listOf(50, 100, 200, 500, 1000, 2000, 5000)

    fun format(cents: Int): String = String.format(Locale.US, "%d,%02d €", cents / 100, cents % 100)

    /** "3,50", "3.5", "3", "12,99€" → cents; anything else → null. */
    fun parse(text: String): Int? {
        val m = Regex("""^\s*(\d+)(?:[.,](\d{1,2}))?\s*€?\s*$""").find(text) ?: return null
        val whole = m.groupValues[1].toInt()
        val frac = m.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toInt()
        return whole * 100 + frac
    }
}
