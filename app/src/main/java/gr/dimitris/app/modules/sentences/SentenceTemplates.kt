package gr.dimitris.app.modules.sentences

import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.greek.Greek
import gr.dimitris.app.core.greek.accusative
import kotlin.random.Random

/** One picture card on the board: the item it came from, and the word printed on it. */
data class Tile(val item: Item, val label: String)

/**
 * One sentence to build, in the order it has to come out in. [distractor] is a card on the board
 * that belongs to no sentence at all — the level-4 way of asking "which words does this need?".
 */
data class Sentence(val level: Int, val tiles: List<Tile>, val distractor: Tile?) {
    val text: String = tiles.joinToString(" ") { it.label }
}

/**
 * CHRIS: rewrite me. Which sentences should Dimitris practise, and in what order do the levels go?
 * Claude wrote a first version so nothing blocks; SentenceTemplatesTest describes the contract.
 *
 * Word order is the thing agrammatism takes away, so the module gives it back one slot at a time,
 * out of the vocabulary the device actually holds rather than a fixed list of sentences:
 *
 *  1. verb + object — «θέλω καφέ»
 *  2. who + verb + object — «εγώ θέλω νερό» — or where and when — «πάμε καφετέρια τώρα»
 *  3. the same, with when it happens on the end — «εγώ θέλω νερό τώρα»
 *  4. the same sentence, with one card on the board that has no place in it
 *
 * The πάμε shape is the way level 2 still works on a device whose PEOPLE cards no longer include
 * «εγώ» — a caregiver may delete any card in the app.
 *
 * A verb takes the objects it can really take: he drinks water and eats bread, and no shuffling of
 * the pictures will offer him «πίνω ψωμί». [generate] returns null rather than bending that rule
 * when the vocabulary cannot fill a shape, and the module drops a level and asks again.
 */
class SentenceTemplates(private val random: Random = Random.Default) {

    /** One sentence at [level] out of [pool], or null when the vocabulary cannot fill the shape. */
    fun generate(level: Int, pool: List<Item>): Sentence? {
        if (level < MIN_LEVEL) return null
        val wanted = level.coerceAtMost(MAX_LEVEL)
        val roles = Roles(pool, random)
        val shapes = when (wanted) {
            1 -> listOfNotNull(roles.verbObject())
            2 -> listOfNotNull(roles.subjectVerbObject(), roles.goPlaceTime())
            else -> listOfNotNull(roles.subjectVerbObjectTime(), roles.goPlaceTime())
        }
        val tiles = shapes.randomOrNull(random) ?: return null
        // A level whose whole point is the odd card out is not that level without one.
        val distractor = if (wanted >= DISTRACTOR_LEVEL) roles.distractor(tiles) ?: return null else null
        return Sentence(wanted, tiles, distractor)
    }

    /**
     * [count] sentences for one sitting, skipping what could not be built. It comes back short — or
     * empty — rather than looping over a pool that cannot fill this shape at all; the caller drops a
     * level and asks again.
     *
     * The same sentence may come round twice: with a pool of two words, "say it again" is the only
     * honest exercise there is, and a run that quietly returned fewer than it was asked for would
     * leave the session promising exercises it never ran.
     */
    fun session(level: Int, pool: List<Item>, count: Int = SENTENCES_PER_SESSION): List<Sentence> {
        val out = mutableListOf<Sentence>()
        var tries = 0
        while (out.size < count && tries < count * TRIES_PER_SENTENCE) {
            tries++
            generate(level, pool)?.let { out += it }
        }
        return out
    }

    companion object {
        const val MIN_LEVEL = 1
        const val MAX_LEVEL = 4

        /** From this level on, one card on the board belongs to no sentence. */
        const val DISTRACTOR_LEVEL = 4

        /** One sitting of sentence building, in free practice or as a module's share of a session. */
        const val SENTENCES_PER_SESSION = 8
    }
}

/**
 * The pool sorted into the parts of a sentence. Everything is chosen by what a card *says*, not by
 * its row id: the seed's «θέλω» and a caregiver's «θελω» are the same verb, and a card she deleted
 * simply leaves its role empty.
 */
private class Roles(pool: List<Item>, private val random: Random) {
    // Phrase cards are whole sentences already («θέλω καφέ»), and a sentence inside a sentence is
    // not a word order exercise. Only single words become tiles.
    private val words = pool.filter { it.kind == ItemKind.WORD && it.text.isNotBlank() }

    private fun of(vararg categories: Category) = words.filter { it.category in categories }

    private val food = of(Category.FOOD)
    private val drinks = food.filter { key(it) in DRINKABLE }
    private val meals = food.filterNot { key(it) in DRINKABLE }
    private val things = of(Category.THINGS)
    private val places = of(Category.PLACES).filterNot { key(it) in NOT_DESTINATIONS }
    private val times = of(Category.TIME).filter { key(it) in TIME_WORDS }
    private val subjects = of(Category.PEOPLE).filter { key(it) == I }
    private val verbs = of(Category.VERBS)
    private val go = verbs.filter { key(it) == GO }
    private val takers = verbs.filter { key(it) in SENTENCE_VERBS && objectsOf(it).isNotEmpty() }

    /** What a verb can really take. Anything else would be teaching him a sentence he must unlearn. */
    private fun objectsOf(verb: Item): List<Item> = when (key(verb)) {
        WANT -> food + things
        EAT -> meals
        DRINK -> drinks
        else -> emptyList()
    }

    fun verbObject(): List<Tile>? {
        val verb = takers.randomOrNull(random) ?: return null
        val obj = objectsOf(verb).randomOrNull(random) ?: return null
        return listOf(Tile(verb, verb.text), objectTile(obj))
    }

    fun subjectVerbObject(): List<Tile>? {
        val subject = subjects.randomOrNull(random) ?: return null
        val rest = verbObject() ?: return null
        return listOf(Tile(subject, subject.text)) + rest
    }

    fun subjectVerbObjectTime(): List<Tile>? {
        val time = times.randomOrNull(random) ?: return null
        val rest = subjectVerbObject() ?: return null
        return rest + Tile(time, time.text)
    }

    fun goPlaceTime(): List<Tile>? {
        val verb = go.randomOrNull(random) ?: return null
        val place = places.randomOrNull(random) ?: return null
        val time = times.randomOrNull(random) ?: return null
        return listOf(Tile(verb, verb.text), objectTile(place), Tile(time, time.text))
    }

    /** A card that looks like it belongs — same kind of word, same accusative — and does not. */
    fun distractor(tiles: List<Tile>): Tile? {
        val used = tiles.mapTo(mutableSetOf()) { it.item.id }
        val said = tiles.mapTo(mutableSetOf()) { it.label }
        return (food + things + places).asSequence()
            .filterNot { it.id in used }.map(::objectTile).filterNot { it.label in said }
            .toList().randomOrNull(random)
    }

    /** A noun after a verb is its object, and an object is written in the accusative: «θέλω καφέ». */
    private fun objectTile(item: Item) = Tile(item, Greek.accusative(item.text))
}

/** How a card's word is matched: trimmed, lower-cased, without accents. */
private fun key(item: Item): String = Greek.stripAccents(Greek.normalize(item.text))

private const val I = "εγω"
private const val GO = "παμε"
private const val WANT = "θελω"
private const val EAT = "τρωω"
private const val DRINK = "πινω"

/** The three verbs a sentence is built round. Every other verb card is a talk board word here. */
private val SENTENCE_VERBS = setOf(WANT, EAT, DRINK)

/** What «πίνω» takes, and what «τρώω» does not. */
private val DRINKABLE = setOf("νερο", "καφες", "τσαι", "γαλα", "μπυρα", "κρασι", "χυμος")

/** The time words that end a sentence. The seed's other TIME cards are days and parts of the day. */
private val TIME_WORDS = setOf("τωρα", "σημερα", "αυριο", "μετα")

/**
 * The PLACES cards also hold what takes him to a place, and «πάμε αυτοκίνητο» is not a sentence —
 * a vehicle needs a preposition he is not being asked for here, and this module must not model an
 * error. «πάμε σπίτι», «πάμε καφετέρια», «πάμε δουλειά» are all the whole sentence as they stand.
 */
private val NOT_DESTINATIONS = setOf("ταξι", "λεωφορειο", "αυτοκινητο", "δρομος")

/** Enough goes at a shape to fill a sitting, few enough that a pool which cannot fill it gives up. */
private const val TRIES_PER_SENTENCE = 4
