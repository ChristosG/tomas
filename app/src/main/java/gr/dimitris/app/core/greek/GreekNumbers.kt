package gr.dimitris.app.core.greek

/**
 * Cardinal numbers 0..9999, in the neuter form used for counting and in the feminine one the clock,
 * the week and the thousands need.
 *
 * Greek numbers have gender, and three of them change with it — ένα/μία, τρία/τρεις,
 * τέσσερα/τέσσερις — so a number is not a string until you know what it is counting. The neuter is
 * what [words] gives, because it is what counting *things* uses and what every exercise before
 * phase 12 needed. [feminine] exists because the three things that came with the higher levels all
 * count feminine nouns: «τρεις χιλιάδες» (χιλιάδα), «τρεις η ώρα» (ώρα) and «μετά από τρεις μέρες»
 * (μέρα). Saying «τρία χιλιάδες» to a Greek ear is the same mistake as "three thousands" to an
 * English one, and he is relearning these words, not hearing them for the hundredth time.
 */
object GreekNumbers {
    /** The biggest number the app ever has to say. Level 14 asks for words up to here. */
    const val MAX = 9999

    private val ones = arrayOf(
        "μηδέν", "ένα", "δύο", "τρία", "τέσσερα", "πέντε", "έξι", "επτά", "οκτώ", "εννέα",
        "δέκα", "έντεκα", "δώδεκα", "δεκατρία", "δεκατέσσερα", "δεκαπέντε", "δεκαέξι", "δεκαεπτά", "δεκαοκτώ", "δεκαεννέα",
    )

    /** The same, for a feminine noun: only ένα, τρία, τέσσερα and their teens actually move. */
    private val onesFeminine = arrayOf(
        "μηδέν", "μία", "δύο", "τρεις", "τέσσερις", "πέντε", "έξι", "επτά", "οκτώ", "εννέα",
        "δέκα", "έντεκα", "δώδεκα", "δεκατρείς", "δεκατέσσερις", "δεκαπέντε", "δεκαέξι", "δεκαεπτά", "δεκαοκτώ", "δεκαεννέα",
    )

    private val tens = arrayOf("", "", "είκοσι", "τριάντα", "σαράντα", "πενήντα", "εξήντα", "εβδομήντα", "ογδόντα", "ενενήντα")
    private val hundreds = arrayOf("", "", "διακόσια", "τριακόσια", "τετρακόσια", "πεντακόσια", "εξακόσια", "επτακόσια", "οκτακόσια", "εννιακόσια")
    private val hundredsFeminine = arrayOf("", "", "διακόσιες", "τριακόσιες", "τετρακόσιες", "πεντακόσιες", "εξακόσιες", "επτακόσιες", "οκτακόσιες", "εννιακόσιες")

    /** The neuter form: what counting things uses, and what every number on a button is read as. */
    fun words(n: Int): String = say(n, feminine = false)

    /**
     * The feminine form, for χιλιάδες, ώρες and μέρες. Same number, different ending on exactly the
     * three words that have one.
     */
    fun feminine(n: Int): String = say(n, feminine = true)

    private fun say(n: Int, feminine: Boolean): String {
        require(n in 0..MAX) { "Εκτός ορίων: $n" }
        if (n == 0) return ones[0]
        val thousands = n / 1000
        val rest = n % 1000
        // χίλια for one thousand and nothing else: the multiplier is a feminine count of χιλιάδες,
        // which is why 2000 is «δύο χιλιάδες» and not «δύο χίλια». χίλια itself is an adjective and
        // takes the gender of what it counts — «χίλιες μέρες» — while χιλιάδες is a noun and does
        // not, which is why only the one-thousand branch asks.
        val head = when (thousands) {
            0 -> ""
            1 -> if (feminine) "χίλιες" else "χίλια"
            else -> "${onesFeminine[thousands]} χιλιάδες"
        }
        val tail = under1000(rest, feminine)
        return listOf(head, tail).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun under1000(n: Int, feminine: Boolean): String {
        if (n == 0) return ""
        val h = n / 100
        val rest = n % 100
        // εκατό standing alone, εκατόν before anything else: «εκατό» but «εκατόν είκοσι τρία».
        val hundredWords = when {
            h == 0 -> ""
            h == 1 -> if (rest == 0) "εκατό" else "εκατόν"
            feminine -> hundredsFeminine[h]
            else -> hundreds[h]
        }
        val unit = if (feminine) onesFeminine else ones
        val restWords = when {
            rest == 0 -> ""
            rest < 20 -> unit[rest]
            rest % 10 == 0 -> tens[rest / 10]
            else -> tens[rest / 10] + " " + unit[rest % 10]
        }
        return listOf(hundredWords, restWords).filter { it.isNotEmpty() }.joinToString(" ")
    }
}
