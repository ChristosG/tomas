package gr.dimitris.app.core.greek

import java.util.Locale

object Euro {
    /** Coins and notes Dimitris will actually handle: 0,50 € up to 50 €. In cents. */
    val denominations: List<Int> = listOf(50, 100, 200, 500, 1000, 2000, 5000)

    /**
     * Every coin and note a till can hand *back*, largest first.
     *
     * Wider than [denominations] on purpose: what he pays with is a note out of his own pocket, and
     * the app only ever asks him for the ones he can recognise — but change is whatever the shop has
     * in the drawer, and 6,60 € back from a twenty is two coins [denominations] does not contain.
     * Pretending otherwise would mean rounding his change, which is the one lie a money exercise
     * cannot tell.
     */
    val changeDenominations: List<Int> = listOf(5000, 2000, 1000, 500, 200, 100, 50, 20, 10, 5, 2, 1)

    /** The most a price may be: 999.999,99 €. Anything longer is a typo, not a price. */
    const val MAX_CENTS = 99_999_999

    /**
     * The most change [change] will count out: 500 €, ten times the biggest note he ever hands over.
     * A price or a payment that asks for more than this is a number that came from a bug, and counting
     * out twenty thousand one-cent coins is not a better answer than admitting it.
     */
    const val MAX_CHANGE_CENTS = 50_000

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
        // GreekNumbers stops at 9999; nothing he ever pays is near it, but a caregiver price is.
        if (euros !in 0..GreekNumbers.MAX) return format(cents)
        return when {
            rest == 0 -> "${GreekNumbers.words(euros)} ευρώ"
            euros == 0 -> cents(rest)
            else -> "${GreekNumbers.words(euros)} ευρώ και ${cents(rest)}"
        }
    }

    /**
     * The λεπτά part, counted the way Greek counts: «ένα λεπτό» for one and «δύο λεπτά» for the rest.
     *
     * A caregiver types 3,01 € for something and the phone says it back to a man relearning his
     * numbers; «ένα λεπτά» is the kind of small wrongness he would hear and not be able to say why.
     * ευρώ needs no such branch — it is invariable, and «ένα ευρώ» is already right.
     */
    private fun cents(rest: Int): String =
        if (rest == 1) "ένα λεπτό" else "${GreekNumbers.words(rest)} λεπτά"

    /**
     * What he is owed back, as coins and notes, largest first: `change(2000, 1340)` is
     * `[500, 100, 50, 10]` — a five, a one, fifty λεπτά and ten λεπτά, for 6,60 € back from a twenty.
     *
     * **Never throws, and never lies.** Paying less than the price is not an error it can report — it
     * is a question that has no change in it — so it comes back as no coins at all, and so does an
     * exact payment. The euro denominations are a canonical set, which is why taking the biggest coin
     * that still fits, over and over, always lands exactly on the amount: the list's sum is the change
     * to the cent, and [changeCents] is the same number without the coins.
     *
     * An amount beyond [MAX_CHANGE_CENTS] is not a till's change but a typo somewhere upstream, and is
     * answered with no coins rather than with a drawerful of them.
     */
    fun change(paidCents: Int, priceCents: Int): List<Int> {
        var left = changeCents(paidCents, priceCents)
        if (left > MAX_CHANGE_CENTS) return emptyList()
        val out = mutableListOf<Int>()
        for (d in changeDenominations) while (left >= d) { out += d; left -= d }
        return out
    }

    /** The change as one amount. Never negative: paying too little is a question, not a debt. */
    fun changeCents(paidCents: Int, priceCents: Int): Int =
        (paidCents.toLong() - priceCents.toLong()).coerceIn(0L, MAX_CENTS.toLong()).toInt()
}
