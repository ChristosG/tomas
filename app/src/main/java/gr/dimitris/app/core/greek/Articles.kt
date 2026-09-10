package gr.dimitris.app.core.greek

/**
 * CHRIS: rewrite me. Which of his words take which article, and where does the final «ν» stay?
 * Claude wrote a first version so nothing blocks; ArticlesTest and GreekTest describe the contract.
 *
 * The small words Dimitris drops. His speech is telegraphic — «φάρμακα πρέπει πάρω» — and the
 * article is the first thing agrammatism takes: he has the nouns and the verbs, and «ο», «τον»,
 * «στη» are what is missing from between them. Levels 5 to 8 of the sentence builder put them on
 * the board as cards of their own, which means the module has to know, for every card in his
 * vocabulary, what its article is.
 *
 * Three rules and a list, in that order:
 *
 *  1. **The ending, where the ending is the whole answer.** -ο and -ι are neuter, -η is feminine,
 *     -ος/-ας/-ης/-ές are masculine. Those cover most of the seed and any word a caregiver types.
 *  2. **The list, where the ending lies.** «γάλα» is neuter and «πόρτα» is feminine and both end in
 *     -α; «ρούχα» is a neuter plural and «σούπα» a feminine singular and no ending tells them apart.
 *     Every such word is named in [FORMS], one by one, the way [NOT_DESTINATIONS] names the places
 *     «πάμε» cannot go to.
 *  3. **Silence, everywhere else.** [nounForm] returns null and the sentence builder simply does not
 *     use that card at the article levels. A caregiver may add any word to this phone; the one thing
 *     this module must never do is put «η ρούχα» on the screen as the sentence to copy. A word it
 *     has never seen costs him one card out of thirty, and guessing costs him the exercise.
 *
 * Nothing here is about the talk board or the word coach: a card is spoken and shown in the
 * nominative everywhere else in the app, exactly as it always was.
 */

/** The two cases a sentence card is ever written in: what does it, and what it is done to. */
enum class Case { NOMINATIVE, ACCUSATIVE }

/** Greek has three, and an article that gets it wrong is the mistake this module exists to unteach. */
enum class Gender { MASCULINE, FEMININE, NEUTER }

/** Everything an article needs to know about a noun, and nothing else. */
data class NounForm(val gender: Gender, val plural: Boolean)

/**
 * What [noun] is, or null when this file cannot say — see rule 3 above.
 *
 * Matched on the word as a caregiver may have typed it: trimmed, lower-cased, accents off. The
 * accent is read once, for the one distinction it alone carries — «καφές» is a masculine singular
 * and «πατάτες» a feminine plural, and only the tone on the ending separates them.
 */
fun Greek.nounForm(noun: String): NounForm? {
    val whole = Greek.stripAccents(Greek.normalize(noun))
    if (whole.isEmpty()) return null
    // The whole card first: «σούπερ μάρκετ» is one word here, and its ending is not what decides it.
    FORMS[whole]?.let { return it }
    val word = noun.trim().substringAfterLast(' ')
    val bare = Greek.stripAccents(Greek.normalize(word))
    FORMS[bare]?.let { return it }
    if (bare.length < 2) return null
    return when {
        // A neuter that ends in sigma keeps it and takes «το»: «το κρέας», «το φως».
        bare in NEUTER_IN_SIGMA -> NounForm(Gender.NEUTER, plural = false)
        bare in FEMININE_IN_OS -> NounForm(Gender.FEMININE, plural = false)
        // A plural in -ες or -εις: its gender is not readable from the ending at all — «οι γυναίκες»
        // and «οι άντρες» end the same way — so it is the list's business, not a rule's.
        pluralEnding(word) -> null
        // What is left ending in -ές carries the accent on that ending, and there the ending is a
        // liar in the other direction: «καφές» is a masculine singular, but «δουλειές», «αδερφές»,
        // «ελιές», «καρδιές», «γραμμές», «φορές» are feminine plurals stressed the same way. The
        // masculines are a closed handful and the plurals are open-ended, so the handful is named
        // ([MASCULINE_IN_ES]) and everything else is silence. Reading «δουλειές» as a masculine
        // singular would put «ο δουλειές θέλει τον δουλειέ» on the screen as the sentence to copy,
        // which is exactly what rule 3 at the top of this file exists to prevent.
        bare.endsWith("ες") -> if (bare in MASCULINE_IN_ES) NounForm(Gender.MASCULINE, plural = false) else null
        bare.endsWith("ος") || bare.endsWith("ας") || bare.endsWith("ης") ->
            NounForm(Gender.MASCULINE, plural = false)
        bare.endsWith("η") -> NounForm(Gender.FEMININE, plural = false)
        bare.endsWith("ο") || bare.endsWith("ι") -> NounForm(Gender.NEUTER, plural = false)
        // -α, -ια, -οι, -υ, -ω and everything foreign: unreadable without the list.
        else -> null
    }
}

/**
 * The definite article [noun] takes in [case] — «ο», «τον», «στη» — or null when [nounForm] cannot
 * say what the noun is.
 *
 * The masculine accusative is always «τον», never «το»: that final «ν» is what tells the two apart
 * in speech, and dropping it in a module whose whole subject is the small words would be teaching
 * the deficit. The feminine keeps or loses its own by the ordinary rule — see [keepsNu].
 *
 * [before] is the word the article will really stand in front of, which is the one that decides
 * that «ν». It is [noun] itself in the nominative, and the **accusative** after a verb or a
 * preposition: «τη θάλασσα» is read off «θάλασσα», but «τον χυμό» is read off «χυμό», not «χυμός».
 */
fun Greek.article(noun: String, case: Case, before: String = noun): String? {
    val form = Greek.nounForm(noun) ?: return null
    return articleFor(form, case, before = before)
}

/**
 * The article a noun of this [form] takes, written in full — «την», never «τη».
 *
 * For a card standing on its own, which is not standing in front of anything: the sentence builder
 * lays articles out as cards of their own, and the final «ν» is a rule about what comes next.
 */
fun Greek.article(form: NounForm, case: Case): String = articleFor(form, case, before = "")

/**
 * «σε» and the article, written as one word the way Greek writes it: «στο φαρμακείο», «στη
 * θάλασσα», «στον δρόμο». Null when the noun is not one this file knows.
 *
 * The noun that follows is in the accusative, so this is [article] with «σ» in front of it and the
 * same final-«ν» rule; that it comes out as one card rather than two is the point — «σε το» is not
 * something anybody says, and a board that offered it would be teaching a form he must unlearn.
 */
fun Greek.contracted(noun: String, before: String = noun): String? {
    val form = Greek.nounForm(noun) ?: return null
    return "σ" + articleFor(form, Case.ACCUSATIVE, before = before)
}

/** The same, for a card standing on its own. See [article]. */
fun Greek.contracted(form: NounForm): String = "σ" + articleFor(form, Case.ACCUSATIVE, before = "")

/**
 * Whether [noun] is written in the plural. The list first, then the one ending that says so on its
 * own: -ες and -εις without the accent on that ending — «πατάτες», «πόλεις», never «καφές».
 *
 * Shared with [accusative], which has to make the same call for the same reason and used to make it
 * with its own copy of this rule.
 */
fun Greek.plural(noun: String): Boolean {
    val word = noun.trim().substringAfterLast(' ')
    FORMS[Greek.stripAccents(Greek.normalize(noun))]?.let { return it.plural }
    FORMS[Greek.stripAccents(Greek.normalize(word))]?.let { return it.plural }
    return pluralEnding(word)
}

/** The article itself, once the noun has been read. [before] decides the feminine's final «ν». */
private fun articleFor(form: NounForm, case: Case, before: String): String = when {
    form.plural && case == Case.NOMINATIVE -> if (form.gender == Gender.NEUTER) "τα" else "οι"
    form.plural -> when (form.gender) {
        Gender.MASCULINE -> "τους"
        Gender.FEMININE -> "τις"
        Gender.NEUTER -> "τα"
    }
    case == Case.NOMINATIVE -> when (form.gender) {
        Gender.MASCULINE -> "ο"
        Gender.FEMININE -> "η"
        Gender.NEUTER -> "το"
    }
    else -> when (form.gender) {
        Gender.MASCULINE -> "τον"
        Gender.FEMININE -> if (keepsNu(before)) "την" else "τη"
        Gender.NEUTER -> "το"
    }
}

/**
 * Whether the feminine «την» keeps its «ν» before [next]: it does before a vowel and before the
 * sounds that can carry it — κ, π, τ, ξ, ψ and the digraphs μπ, ντ, γκ, τσ, τζ — and loses it
 * everywhere else. «την τράπεζα», «την ομπρέλα», «την μπύρα»; «τη θάλασσα», «τη δουλειά».
 *
 * The school rule, and the reason it is worth having in an app for a man relearning these words: he
 * hears «τη θάλασσα» from everyone around him, and a card that said «την θάλασσα» would be a form
 * he has to correct later.
 */
private fun keepsNu(next: String): Boolean {
    val w = Greek.stripAccents(Greek.normalize(next))
    if (w.isEmpty()) return true
    if (w.take(2) in NU_DIGRAPHS) return true
    return w[0] in VOWELS || w[0] in NU_CONSONANTS
}

/**
 * The one ending that says "plural" by itself, read off the accented word. See [plural], and
 * [accusative], which has to make the same call before it takes a final sigma off.
 */
internal fun pluralEnding(word: String): Boolean {
    val bare = Greek.stripAccents(Greek.normalize(word))
    if (bare.endsWith("εις")) return true
    // «πατάτες» keeps a plain «ε» before the sigma; «καφές» carries the tone there and is singular.
    return bare.endsWith("ες") && word.length >= 2 && word.trim().dropLast(1).last().lowercaseChar() == 'ε'
}

private const val VOWELS = "αεηιουω"
private const val NU_CONSONANTS = "κπτξψ"
private val NU_DIGRAPHS = setOf("μπ", "ντ", "γκ", "τσ", "τζ")

/**
 * The nouns in -ος that are feminine. A closed and short class; none of them is in the seed yet, and
 * one missing from here would be read as a masculine and lose its sigma — «ο μέθοδος», «τον μέθοδο»
 * — so it is worth adding to rather than being clever about.
 */
private val FEMININE_IN_OS = setOf(
    "οδος", "λεωφορος", "εισοδος", "εξοδος", "νησος", "ηπειρος", "ψηφος", "διαμετρος",
    "μεθοδος", "περιοδος", "προοδος", "καθοδος", "ανοδος", "συνοδος", "αμμος", "παρθενος",
    "διαλεκτος", "ψαμμος", "πλατανος", "δοκος",
)

/**
 * The masculine singulars in -ές. **A closed handful, and that is the point**: every other Greek
 * word ending in an accented -ές is a feminine plural, so [nounForm] answers for these and stays
 * silent for the rest. See the comment at the -ές branch.
 */
private val MASCULINE_IN_ES = setOf("καφες", "κεφτες", "μεζες", "τενεκες", "μπουφες", "ναργιλες")

/** The same handful, for [accusative], which has to make the same call before it takes a sigma off. */
internal fun masculineInEs(bare: String): Boolean = bare in MASCULINE_IN_ES

/**
 * Every word whose ending does not say what it is, written out one by one.
 *
 * Almost all of them end in -α, which in Greek is a feminine singular («η πόρτα», «η σούπα») and
 * also a neuter plural («τα ρούχα», «τα φάρμακα») and there is no rule that separates the two. The
 * rest are the handful of oddities a kitchen and a city hold: «το γάλα», «το σούπερ μάρκετ», «οι
 * πατάτες».
 *
 * Swept once against the bundled seed: every PEOPLE, FOOD, THINGS and PLACES card whose ending is
 * unreadable is here, so the sentence builder can use the whole vocabulary rather than the half of
 * it that happens to end in -ο. A word a caregiver adds later and this list has never seen is
 * simply not offered at levels 5–8 — see rule 3 at the top of this file.
 */
private val FORMS: Map<String, NounForm> = buildMap {
    fun f(vararg words: String) = words.forEach { put(it, NounForm(Gender.FEMININE, plural = false)) }
    fun n(vararg words: String) = words.forEach { put(it, NounForm(Gender.NEUTER, plural = false)) }
    fun fPl(vararg words: String) = words.forEach { put(it, NounForm(Gender.FEMININE, plural = true)) }
    fun nPl(vararg words: String) = words.forEach { put(it, NounForm(Gender.NEUTER, plural = true)) }

    // PEOPLE
    f("μαμα", "νοσοκομα", "λογοθεραπευτρια", "γυναικα", "γιαγια", "θεια")
    // FOOD
    f("μπυρα", "πιτσα", "σαλατα", "μπανανα", "σοκολατα", "σουπα", "φετα", "ελια", "κρεμα", "μαρμελαδα")
    n("γαλα")
    fPl("πατατες", "ελιες")
    nPl("μακαρονια", "φρουτα")
    // THINGS
    f("τσαντα", "ομπρελα", "καρεκλα", "πορτα", "μπαλα", "εφημεριδα", "κουβερτα")
    nPl("κλειδια", "ρουχα", "παπουτσια", "φαρμακα", "χρηματα", "γυαλια")
    // PLACES
    f("καφετερια", "θαλασσα", "εκκλησια", "τραπεζα", "ταβερνα", "κουζινα", "δουλεια", "πλατεια", "αγορα", "παραλια")
    n("σουπερ μαρκετ", "μαρκετ")
    // The -μα neuters, by hand rather than by rule. «-μα is neuter» would be a new bug of its own:
    // «η κρέμα», «η φόρμα», «η πιτζάμα» end the same way and are feminine, and they are above.
    n("γραμμα", "ονομα", "προβλημα", "χρωμα", "στομα", "δωματιο", "κτημα", "βλεμμα", "αιμα", "σωμα", "ρευμα", "κλιμα")
    // The feminine plurals stressed on the ending that a caregiver is most likely to type. The rule
    // stays silent about this whole class ([MASCULINE_IN_ES]); these are the ones worth answering.
    fPl("δουλειες", "αδερφες", "αδελφες", "καρδιες", "φωτιες", "μηχανες", "γραμμες", "φορες", "μπριζολες")
}
