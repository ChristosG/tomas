package gr.dimitris.app.core.greek

/**
 * CHRIS: rewrite me. Which Greek nouns keep their final sigma in the accusative?
 * Claude wrote a first version so nothing blocks; AccusativeTest describes the contract.
 *
 * The one ending the sentence builder has to get right. Every picture card is written in the
 * nominative — the way a word is looked up, and the way the talk board says it — but a sentence
 * wants the object of the verb: «θέλω καφέ», not «θέλω καφές». Agrammatism is what this module is
 * for, so a card that spelled the ending wrong would be teaching the mistake.
 *
 * The rule is small because modern Greek is: masculine and feminine nouns drop their final sigma in
 * the accusative singular (καφές → καφέ, χυμός → χυμό, άντρας → άντρα, οδός → οδό), and everything
 * else is already the word he needs — neuters (νερό, ψωμί), feminines in -α and -η (μπύρα, ζάχαρη),
 * and every plural in his vocabulary (πατάτες, φρούτα, κλειδιά).
 *
 * The ending alone cannot tell a masculine from one of the neuters that also end in sigma — κρέας,
 * φως, λάθος are the accusative already — so those are named, one by one, in [NEUTER_IN_SIGMA].
 *
 * Nor from a plural in -ες, which keeps its sigma: «τρώω πατάτες». The accent is what separates the
 * two, since a masculine singular in -ές carries it on the ending and a plural does not (πατά-τες,
 * γυναί-κες). The exception the accent cannot catch is a plural of a noun stressed on the ending —
 * «οι φορές», «οι γραμμές» — and no card in this vocabulary is one.
 */
/**
 * [gender] is what the row itself says the word is, when it says anything
 * ([gr.dimitris.app.core.data.Item.gender]). It matters in one place and one only: a **neuter** in
 * -ος or -ας keeps its sigma, and [NEUTER_IN_SIGMA] is a list, so a neuter a caregiver typed that is
 * not on it would come out as «θέλω το άνθο». The article half of this file has taken a stated
 * gender since phase 13; this half taking it too is what keeps a card's article and its ending from
 * ever disagreeing about the same word.
 */
fun Greek.accusative(noun: String, gender: String? = null): String {
    val trimmed = noun.trim()
    if (trimmed.isEmpty()) return trimmed
    // Only the noun bends. «σούπερ μάρκετ» is one card, and its first word is not what carries the case.
    val head = trimmed.substringBeforeLast(' ', missingDelimiterValue = "")
    val last = trimmed.substringAfterLast(' ')
    val bent = dropFinalSigma(last, Gender.of(gender) == Gender.NEUTER)
    return if (head.isEmpty()) bent else "$head $bent"
}

/** The word with its final sigma gone, or the word itself when it keeps it. */
private fun dropFinalSigma(word: String, statedNeuter: Boolean = false): String {
    val ends = word.last().lowercaseChar() in SIGMA
    if (!ends || word.length < 2) return word
    // Typed in a hurry: «ΚΑΦΕΣ» and «κρεας» have to be recognised as the words they are.
    val bare = Greek.stripAccents(Greek.normalize(word))
    // A neuter that ends in sigma keeps it: «τρώω κρέας». The list knows the ones the seed holds;
    // the row knows the one the caregiver has just typed.
    if (statedNeuter || bare in NEUTER_IN_SIGMA) return word
    // A plural is already the accusative: «πατάτες», «γυναίκες», «πόλεις». [Greek.plural] is the one
    // place that decides it — the curated list first, then the accent, which is what separates
    // «πατάτες» from «καφές».
    if (Greek.plural(word)) return word
    // What is left ending in -ές carries the accent there, and only a **named** handful of those is
    // a masculine singular ([nounForm]'s -ές branch). Everything else is a feminine plural stressed
    // on the ending — «ελιές», «καρδιές», «δουλειές» — and it keeps its sigma. Keeping a sigma that
    // should have gone is «θέλω μεζές» for a word nobody listed; dropping one that should have
    // stayed is «θέλω ελιέ», which is not a Greek word at all.
    if (bare.endsWith("ες") && !masculineInEs(bare)) return word
    return word.dropLast(1)
}

/** Both sigmas: a word typed in capitals ends in Σ, and one typed properly in ς. */
private val SIGMA = setOf('ς', 'σ')

/**
 * The neuters that end in sigma and keep it: «τρώω κρέας», «κλείσε το φως». Lower-cased and without
 * accents, the way a caregiver's typing is compared. A neuter missing from this list loses its
 * ending on a sentence card, so it is worth adding to rather than clever about — the -ος and -ας
 * neuters are a closed class, and these are the ones a kitchen, a house and a body are made of.
 *
 * Shared with [nounForm], which has to make the same call for the same reason: a neuter that ends in
 * sigma keeps its sigma *and* takes «το».
 */
internal val NEUTER_IN_SIGMA = setOf(
    "κρεας", "τερας", "περας", "γηρας",
    "φως", "λαθος", "μερος", "τελος", "ειδος", "μεγεθος", "βαρος", "υψος", "μηκος", "πλατος", "βαθος",
    "δασος", "κρατος", "ετος", "πληθος", "χρεος", "στηθος", "ηθος", "εδαφος", "γεγονος",
    "αγχος", "παθος", "κοστος", "βρεφος", "σκευος", "θρασος", "ψυχος", "θαρρος", "μισος",
)
