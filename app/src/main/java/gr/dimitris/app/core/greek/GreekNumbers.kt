package gr.dimitris.app.core.greek

/** Cardinal numbers 0..1000 in the neuter form used for counting. */
object GreekNumbers {
    private val ones = arrayOf(
        "μηδέν", "ένα", "δύο", "τρία", "τέσσερα", "πέντε", "έξι", "επτά", "οκτώ", "εννέα",
        "δέκα", "έντεκα", "δώδεκα", "δεκατρία", "δεκατέσσερα", "δεκαπέντε", "δεκαέξι", "δεκαεπτά", "δεκαοκτώ", "δεκαεννέα",
    )
    private val tens = arrayOf("", "", "είκοσι", "τριάντα", "σαράντα", "πενήντα", "εξήντα", "εβδομήντα", "ογδόντα", "ενενήντα")
    private val hundreds = arrayOf("", "", "διακόσια", "τριακόσια", "τετρακόσια", "πεντακόσια", "εξακόσια", "επτακόσια", "οκτακόσια", "εννιακόσια")

    fun words(n: Int): String {
        require(n in 0..1000) { "Εκτός ορίων: $n" }
        if (n == 1000) return "χίλια"
        if (n < 20) return ones[n]
        val h = n / 100
        val rest = n % 100
        val restWords = when {
            rest == 0 -> ""
            rest < 20 -> ones[rest]
            rest % 10 == 0 -> tens[rest / 10]
            else -> tens[rest / 10] + " " + ones[rest % 10]
        }
        if (h == 0) return restWords
        val hundredWords = if (h == 1) (if (rest == 0) "εκατό" else "εκατόν") else hundreds[h]
        return if (rest == 0) hundredWords else "$hundredWords $restWords"
    }
}
