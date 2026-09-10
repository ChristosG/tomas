package gr.dimitris.app.modules.sentences

import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.greek.Case
import gr.dimitris.app.core.greek.Gender
import gr.dimitris.app.core.greek.Greek
import gr.dimitris.app.core.greek.NounForm
import gr.dimitris.app.core.greek.accusative
import gr.dimitris.app.core.greek.article
import gr.dimitris.app.core.greek.contracted
import gr.dimitris.app.core.greek.nounForm
import kotlin.random.Random

/**
 * One picture card on the board: the item it came from, and the word printed on it.
 *
 * [madeUp] is a card the module minted rather than one of his own vocabulary — the small words a
 * sentence needs («ο», «στο», «να», «γιατί»), and the odd verb form no picture card carries
 * («πεινάω»). It has no picture behind it and the screen draws it as a word.
 */
data class Tile(val item: Item, val label: String, val madeUp: Boolean = false)

/**
 * Which of the three ways one board asks its question.
 *
 * [BUILD] is the module as it always was: the cards, in the order they have to go down. [GAP] shows
 * the sentence with one small word taken out of it and three to choose between — the same grammar,
 * asked of a man who is reading rather than assembling. [TYPED] shows a picture and a keyboard and
 * asks for the whole sentence, which is the only thing in this app that asks him to produce every
 * word himself.
 */
enum class Variant { BUILD, GAP, TYPED }

/**
 * A sentence with one function word taken out of it: [at] is which tile is missing, and [options]
 * are the three cards offered in its place — the right one and two articles that agree with nothing
 * on the board, so there is exactly one answer.
 */
data class Gap(val at: Int, val options: List<String>)

/**
 * One sentence to build, in the order it has to come out in. [distractor] is a card on the board
 * that could not fill any slot of this sentence, whichever way he arranges it — the level-4 way of
 * asking "which words does this need?", and the reason there is only ever one right answer.
 *
 * [ending] is the question mark of a level-8 board and nothing anywhere else: it belongs to the
 * sentence, not to the last card, because «φαρμακείο;» is not a word he has a picture of.
 */
data class Sentence(
    val level: Int,
    val tiles: List<Tile>,
    val distractor: Tile? = null,
    val ending: String = "",
    val variant: Variant = Variant.BUILD,
    val gap: Gap? = null,
    /** The one picture a typed board shows: the thing the sentence is about. */
    val picture: Tile? = null,
) {
    val text: String = tiles.joinToString(" ") { it.label } + ending

    /**
     * Whether what the board wants is a **question** rather than a statement.
     *
     * A typed board has to say so. With a picture of a chemist's and «Γράψε την πρόταση» on the
     * screen, «θέλω να πάω στο φαρμακείο» is faultless Greek and is not what level 8 asked for, and
     * the judge — which is told to accept only when the meaning agrees with the target — would
     * answer it with «Σχεδόν.» A man cannot be marked down for not guessing which of two good
     * sentences the app had in mind; see the level-4 distractor rule, which is the same principle
     * one level of the sentence up.
     */
    val question: Boolean = ending == QUESTION_MARK

    /** The word the blank is waiting for, on a [Variant.GAP] board. */
    val answer: String? = gap?.let { tiles.getOrNull(it.at)?.label }

    /** The sentence as it is shown on a gap board: the missing word written as a blank. */
    fun withBlank(blank: String): String =
        tiles.mapIndexed { i, t -> if (i == gap?.at) blank else t.label }.joinToString(" ") + ending
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
 *  5. the articles — «ο μπαμπάς πίνει τον καφέ»
 *  6. a preposition and where it takes him — «πάω στο φαρμακείο με το λεωφορείο»
 *  7. a clause — «θέλω να φάω ψωμί γιατί πεινάω»
 *  8. a question — «πού είναι το φαρμακείο;»
 *
 * Levels 1–4 were the whole ladder until Dimitris told us, in September, that they were trivial.
 * They were: two picture cards in the right order is not what he is missing. What he is missing is
 * everything *between* the pictures — the article, the preposition, the «να» and the «γιατί» — and
 * from level 5 up those are cards of their own, with the grammar right in every one of them
 * ([gr.dimitris.app.core.greek.article]) or the sentence not built at all.
 *
 * The πάμε shape is the way level 2 still works on a device whose PEOPLE cards no longer include
 * «εγώ» — a caregiver may delete any card in the app.
 *
 * A verb takes the objects it can really take: he drinks water and eats bread, and no shuffling of
 * the pictures will offer him «πίνω ψωμί». [generate] returns null rather than bending that rule
 * when the vocabulary cannot fill a shape, and the module drops a level and asks again. The article
 * levels add one more thing it will not bend on: a noun whose gender this app cannot read is a noun
 * it does not use, because «η ρούχα» on the screen is worse than one card fewer to choose from.
 */
class SentenceTemplates(private val random: Random = Random.Default) {

    /**
     * One sentence at [level] out of [pool], or null when the vocabulary cannot fill the shape.
     *
     * [variant] is what the board will *ask*; a shape that cannot carry it — nothing to blank out,
     * no picture to write about — comes back as an ordinary [Variant.BUILD] board rather than as a
     * null, because a sitting must not lose an exercise to a vocabulary accident.
     */
    fun generate(level: Int, pool: List<Item>, variant: Variant = Variant.BUILD): Sentence? {
        if (level < MIN_LEVEL) return null
        val wanted = level.coerceAtMost(MAX_LEVEL)
        val roles = Roles(pool, random)
        val shapes = when (wanted) {
            1 -> listOfNotNull(roles.verbObject())
            2 -> listOfNotNull(roles.subjectVerbObject(), roles.goPlaceTime())
            3, 4 -> listOfNotNull(roles.subjectVerbObjectTime(), roles.goPlaceTime())
            5 -> listOfNotNull(roles.articledSentence())
            6 -> listOfNotNull(roles.goByWay())
            7 -> listOfNotNull(roles.becauseClause())
            else -> listOfNotNull(roles.whereQuestion())
        }
        val shape = shapes.randomOrNull(random) ?: return null
        // A level whose whole point is the odd card out is not that level without one. From level 5
        // the odd card is a small word rather than a picture, which is the same question asked of
        // the part of the sentence he actually drops.
        val distractor = when {
            wanted >= ARTICLE_LEVEL -> roles.oddArticle(shape) ?: return null
            wanted >= DISTRACTOR_LEVEL -> roles.distractor(shape.tiles) ?: return null
            else -> null
        }
        val gap = if (variant == Variant.GAP) shape.blanks.randomOrNull(random)?.let(::offer) else null
        val asked = when {
            gap != null -> Variant.GAP
            variant == Variant.TYPED && shape.picture != null -> Variant.TYPED
            else -> Variant.BUILD
        }
        return Sentence(
            level = wanted, tiles = shape.tiles, distractor = distractor, ending = shape.ending,
            variant = asked, gap = gap, picture = shape.picture,
        )
    }

    /**
     * [count] sentences for one sitting, skipping what could not be built. It comes back short — or
     * empty — rather than looping over a pool that cannot fill this shape at all; the caller drops a
     * level and asks again.
     *
     * The same sentence may come round twice: with a pool of two words, "say it again" is the only
     * honest exercise there is, and a run that quietly returned fewer than it was asked for would
     * leave the session promising exercises it never ran.
     *
     * [judged] says whether «Έλεγχος με Claude» will really answer. Only it can read a sentence he
     * typed, so with it off there are no typed boards at all — see [variantFor].
     *
     * [from] is where in the sitting these boards begin, and it exists so that a caller topping a
     * short run back up does not restart the every-third rhythm at zero and put two variants next to
     * each other.
     */
    fun session(
        level: Int,
        pool: List<Item>,
        count: Int = SENTENCES_PER_SESSION,
        judged: Boolean = false,
        from: Int = 0,
    ): List<Sentence> {
        val out = mutableListOf<Sentence>()
        var tries = 0
        while (out.size < count && tries < count * TRIES_PER_SENTENCE) {
            tries++
            generate(level, pool, variantFor(from + out.size, level, judged))?.let { out += it }
        }
        return out
    }

    /**
     * How the board at [index] of a sitting asks its question: every third one is the odd one out,
     * and what it turns into depends on the level.
     *
     * Every third and not every second, because the variants are the harder ask and the building is
     * still what the module teaches — and not a random one in three either, since a man who is being
     * asked to type has a right to know it is coming.
     */
    fun variantFor(index: Int, level: Int, judged: Boolean): Variant {
        if ((index + 1) % EVERY_THIRD != 0) return Variant.BUILD
        return when {
            level in GAP_LEVELS -> Variant.GAP
            // Nothing on the phone can read a sentence he typed. With the judge off the board would
            // have to accept anything or refuse everything, and both are worse than not asking.
            level in TYPED_LEVELS && judged -> Variant.TYPED
            else -> Variant.BUILD
        }
    }

    /** The blank, with its three cards: the right one, and two the sentence cannot take. */
    private fun offer(blank: Blank): Gap {
        val wrong = blank.wrong.shuffled(random).take(OPTIONS - 1)
        return Gap(blank.at, (wrong + blank.answer).shuffled(random))
    }

    companion object {
        const val MIN_LEVEL = 1

        /** Eight since phase 12: 5 the articles, 6 the prepositions, 7 a clause, 8 a question. */
        const val MAX_LEVEL = 8

        /** From this level on, one card on the board belongs to no sentence. */
        const val DISTRACTOR_LEVEL = 4

        /** From this level on the sentence carries its own articles, and so does the odd card out. */
        const val ARTICLE_LEVEL = 5

        /** Where a board may come up as the sentence with one small word missing. */
        val GAP_LEVELS = 5..6

        /** Where a board may come up as a picture and a keyboard. */
        val TYPED_LEVELS = 7..8

        /** How many cards a gap board offers. Three: enough to be a choice, few enough to read. */
        const val OPTIONS = 3

        /** One board in three is the variant; the other two are the cards, as they always were. */
        const val EVERY_THIRD = 3

        /** One sitting of sentence building, in free practice or as a module's share of a session. */
        const val SENTENCES_PER_SESSION = 8
    }
}

/** One place a function word could be taken out of the sentence, and what would not fit in its place. */
private class Blank(val at: Int, val answer: String, val wrong: List<String>)

/** One sentence shape, before it is decided what the board will ask about it. */
private class Shape(
    val tiles: List<Tile>,
    /** Where a small word could be taken out and asked for. Empty on a shape that has none. */
    val blanks: List<Blank> = emptyList(),
    /** The card whose picture a typed board shows: the thing the sentence is about. */
    val picture: Tile? = null,
    val ending: String = "",
)

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
    private val allPlaces = of(Category.PLACES)
    private val places = allPlaces.filterNot { key(it) in NOT_DESTINATIONS }
    private val times = of(Category.TIME).filter { key(it) in TIME_WORDS }
    private val subjects = of(Category.PEOPLE).filter { key(it) == I }
    private val verbs = of(Category.VERBS)
    private val go = verbs.filter { key(it) == GO }
    private val takers = verbs.filter { key(it) in SENTENCE_VERBS && objectsOf(it).isNotEmpty() }

    /** The people a sentence can be *about*: everyone but the pronouns, and only if we know the gender. */
    private val named = of(Category.PEOPLE).filter { key(it) !in PRONOUNS && readable(it) }

    /** Where «πάω σε» can take him. With the preposition, the rooms and the street are back in. */
    private val destinations = allPlaces.filter { key(it) !in VEHICLES && readable(it) }

    /** What takes him there: the three the seed holds, and any vehicle a caregiver adds to them. */
    private val vehicles = allPlaces.filter { key(it) in VEHICLES && readable(it) }

    /**
     * What a level-8 question asks after: something with a place in the world and a readable article.
     *
     * Not everything with a picture is somewhere. «πού είναι ο ήλιος;» and «πού είναι η βροχή;» are
     * grammatical and are not questions anybody asks, and a model sentence nobody would say is a
     * model sentence he has to unlearn — the same reason [NOT_DESTINATIONS] exists.
     */
    private val locatable = (things + allPlaces).filter { readable(it) && key(it) !in NOT_SOMEWHERE }

    /** What a verb can really take. Anything else would be teaching him a sentence he must unlearn. */
    private fun objectsOf(verb: Item): List<Item> = when (key(verb)) {
        WANT -> food + things
        EAT -> meals
        DRINK -> drinks
        else -> emptyList()
    }

    fun verbObject(): Shape? {
        val verb = takers.randomOrNull(random) ?: return null
        val obj = objectsOf(verb).randomOrNull(random) ?: return null
        val card = objectTile(obj)
        return Shape(listOf(Tile(verb, verb.text), card), picture = card)
    }

    fun subjectVerbObject(): Shape? {
        val subject = subjects.randomOrNull(random) ?: return null
        val rest = verbObject() ?: return null
        return Shape(listOf(Tile(subject, subject.text)) + rest.tiles)
    }

    fun subjectVerbObjectTime(): Shape? {
        val time = times.randomOrNull(random) ?: return null
        val rest = subjectVerbObject() ?: return null
        return Shape(rest.tiles + Tile(time, time.text))
    }

    fun goPlaceTime(): Shape? {
        val verb = go.randomOrNull(random) ?: return null
        val place = places.randomOrNull(random) ?: return null
        val time = times.randomOrNull(random) ?: return null
        return Shape(listOf(Tile(verb, verb.text), objectTile(place), Tile(time, time.text)))
    }

    /**
     * Level 5 — «ο μπαμπάς πίνει τον καφέ». The subject in the nominative with its article, the verb
     * in the third person, the object in the accusative with its own.
     *
     * The third person and not his own «θέλω», because an article on the subject is the whole point
     * and «εγώ» does not take one. The three verbs the module is built round are a closed set, so
     * the conjugation is a table rather than a guess: see [THIRD_PERSON].
     */
    fun articledSentence(): Shape? {
        val subject = named.randomOrNull(random) ?: return null
        val verb = takers.randomOrNull(random) ?: return null
        val third = THIRD_PERSON[key(verb)] ?: return null
        val obj = objectsOf(verb).filter { readable(it) }.randomOrNull(random) ?: return null
        val subjectArticle = articleOf(subject, Case.NOMINATIVE) ?: return null
        val objectArticle = articleOf(obj, Case.ACCUSATIVE) ?: return null
        val objectCard = objectTile(obj)
        val tiles = listOf(
            small(subjectArticle), Tile(subject, subject.text),
            Tile(verb, third), small(objectArticle), objectCard,
        )
        return Shape(
            tiles = tiles,
            blanks = listOfNotNull(
                blank(0, subject, Case.NOMINATIVE), blank(3, obj, Case.ACCUSATIVE),
            ),
            picture = objectCard,
        )
    }

    /**
     * Level 6 — «πάω στο φαρμακείο με το λεωφορείο». Where he is going, and what takes him there.
     *
     * «σε» never stands on its own in Greek: it is written into the article as one word, and «στο /
     * στη / στον» is exactly the choice this level teaches. With the preposition in hand the rooms
     * and the street come back as destinations — «πάμε κρεβάτι» was the sentence level 2 could not
     * say, and «πάω στο κρεβάτι» is the sentence that fixes it.
     */
    fun goByWay(): Shape? {
        val verb = go.randomOrNull(random) ?: return null
        val first = FIRST_PERSON[key(verb)] ?: return null
        val place = destinations.randomOrNull(random) ?: return null
        val ride = vehicles.randomOrNull(random) ?: return null
        val to = contractedOf(place) ?: return null
        val by = articleOf(ride, Case.ACCUSATIVE) ?: return null
        val placeCard = objectTile(place)
        val tiles = listOf(
            Tile(verb, first), small(to), placeCard,
            small(WITH), small(by), objectTile(ride),
        )
        return Shape(
            tiles = tiles,
            blanks = listOfNotNull(
                blank(1, place, Case.ACCUSATIVE, contracted = true), blank(4, ride, Case.ACCUSATIVE),
            ),
            picture = placeCard,
        )
    }

    /**
     * Level 7 — «θέλω να φάω ψωμί γιατί πεινάω». Two clauses, and the two words that join them.
     *
     * «να» and «γιατί» are the whole exercise; the object stays bare because that is how the
     * sentence is really said. «φάω» is the eating card with the subjunctive written on it — the
     * picture is right, and the form is the one that follows «να» — and «πεινάω» is a card the
     * module makes up, because the reason he is eating is not a word his talk board holds.
     */
    fun becauseClause(): Shape? {
        val want = verbs.firstOrNull { key(it) == WANT } ?: return null
        // The form, not the card's text: a caregiver who renames the card to «θέλει» must not turn
        // this into «θέλει να φάω…». The card is matched by what it *means*, and written by the table.
        val wants = FIRST_PERSON[WANT] ?: return null
        val verb = takers.filter { key(it) in setOf(EAT, DRINK) }.randomOrNull(random) ?: return null
        val subjunctive = SUBJUNCTIVE[key(verb)] ?: return null
        val because = BECAUSE_VERB[key(verb)] ?: return null
        val obj = objectsOf(verb).randomOrNull(random) ?: return null
        val objectCard = objectTile(obj)
        val tiles = listOf(
            Tile(want, wants), small(TO), Tile(verb, subjunctive), objectCard,
            small(BECAUSE), small(because),
        )
        return Shape(tiles = tiles, picture = objectCard)
    }

    /** Level 8 — «πού είναι το φαρμακείο;». The question word, the verb to be, and the article. */
    fun whereQuestion(): Shape? {
        val thing = locatable.randomOrNull(random) ?: return null
        val article = articleOf(thing, Case.NOMINATIVE) ?: return null
        val card = Tile(thing, thing.text)
        val tiles = listOf(small(WHERE), small(IS), small(article), card)
        return Shape(
            tiles = tiles,
            blanks = listOfNotNull(blank(2, thing, Case.NOMINATIVE)),
            picture = card,
            ending = QUESTION_MARK,
        )
    }

    /**
     * The odd card out: a word that could not fill any slot of this sentence, whichever way he
     * arranges the board. It is drawn from the far side of the sentence's own filler set — a place
     * next to «θέλω/τρώω/πίνω», something to eat or to hold next to «πάμε».
     *
     * Drawing it from the same set as the object was the defect this is written to stop. With
     * «εγώ θέλω νερό τώρα» on the board and «καφέ» beside it, nothing on the screen said which of
     * the two the app had in mind: he built «εγώ θέλω καφέ τώρα», faultless Greek, and was answered
     * with «Σχεδόν.» and an assisted mark. A correction he could not have avoided teaches him
     * nothing about word order, which is the one thing this module is for.
     */
    fun distractor(tiles: List<Tile>): Tile? {
        val verb = tiles.firstOrNull { it.item.category == Category.VERBS } ?: return null
        val elsewhere = if (key(verb.item) == GO) food + things else places
        val used = tiles.mapTo(mutableSetOf()) { it.item.id }
        val said = tiles.mapTo(mutableSetOf()) { it.label }
        return elsewhere.asSequence()
            .filterNot { it.id in used }.map(::objectTile).filterNot { it.label in said }
            .toList().randomOrNull(random)
    }

    /**
     * The same question at the article levels, asked about the small words: an article for a gender
     * or a number that **no noun on this board has**.
     *
     * That is what makes it unusable rather than merely unused. An article can only stand in front
     * of a noun, and the only nouns he has are the sentence's own; one that agrees with none of them
     * cannot be put anywhere at all, however he arranges the cards. «τα» next to «ο μπαμπάς πίνει
     * τον καφέ» has nowhere to go, and looking for where it goes is exactly the reading this level
     * is for.
     */
    fun oddArticle(shape: Shape): Tile? {
        // His own cards only: a small word the module minted is not a noun, and «στο» read as one
        // would say the board holds a neuter singular when it holds an article.
        val onBoard = shape.tiles.filterNot { it.madeUp }
            .mapNotNullTo(mutableSetOf()) { formOf(it.item) }
        // Banned by how it is *written*, not only by what it agrees with: «οι» is the nominative of
        // a masculine plural and of a feminine plural both, so a board holding «τις πατάτες» must
        // not be handed «οι» on the grounds that it has no masculine plural in it.
        val banned = ARTICLE_SHAPES.filter { it.form in onBoard }.mapTo(mutableSetOf()) { it.label }
        val said = shape.tiles.mapTo(mutableSetOf()) { it.label }
        return ARTICLE_SHAPES.asSequence()
            .filterNot { it.form in onBoard }
            .map { it.label }
            .filterNot { it in banned || it in said }
            .distinct().toList().randomOrNull(random)?.let(::small)
    }

    /**
     * One place the sentence could be asked about instead of built: the article at [at], and the two
     * or more articles of another gender or number that would not agree with [noun] if he chose one.
     */
    private fun blank(at: Int, noun: Item, case: Case, contracted: Boolean = false): Blank? {
        val form = formOf(noun) ?: return null
        val answer = if (contracted) contractedOf(noun) else articleOf(noun, case)
        if (answer == null) return null
        val wrong = ARTICLE_SHAPES
            .filter { it.case == case && it.contracted == contracted && it.form != form }
            .map { it.label }.distinct().filterNot { it == answer }
        return if (wrong.size < SentenceTemplates.OPTIONS - 1) null else Blank(at, answer, wrong)
    }

    /**
     * What a card's word is, gender column first.
     *
     * Since phase 13 a row can say its own gender, and where it does it is the answer: that is what
     * lets a word the caregiver typed — «ραντεβού», «ο λογαριασμός», her sister's name — stand at
     * levels 5–8 at all. Where it does not, this is exactly the ending-and-list inference the module
     * has always run on. See [gr.dimitris.app.core.greek.nounForm].
     */
    private fun formOf(item: Item): NounForm? = Greek.nounForm(item.text, item.gender)

    /** Whether this app can say what a card's word is, which is whether it may take an article. */
    private fun readable(item: Item): Boolean = formOf(item) != null

    /**
     * The article that goes in front of a card. The word that follows it is what decides the
     * feminine's final «ν» — «τη θάλασσα» but «την τράπεζα» — and after a verb that word is the
     * accusative, not the card's own nominative.
     */
    private fun articleOf(item: Item, case: Case): String? = Greek.article(
        item.text, case,
        before = if (case == Case.ACCUSATIVE) accusativeOf(item) else item.text,
        gender = item.gender,
    )

    private fun contractedOf(item: Item): String? =
        Greek.contracted(item.text, before = accusativeOf(item), gender = item.gender)

    /** A noun after a verb is its object, and an object is written in the accusative: «θέλω καφέ». */
    private fun objectTile(item: Item) = Tile(item, accusativeOf(item))

    /**
     * The card as it is written after a verb or a preposition. The row's gender goes with the word:
     * a stated neuter keeps its final sigma («θέλω το άνθος»), and a card whose article says neuter
     * while its ending says masculine would be teaching the mistake this module exists to unteach.
     */
    private fun accusativeOf(item: Item): String = Greek.accusative(item.text, item.gender)

    /** One of the small words, on a card the module made up: it is nobody's vocabulary row. */
    private fun small(label: String) =
        Tile(Item(text = label, kind = ItemKind.WORD, category = Category.CUSTOM), label, madeUp = true)
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

/** The two PEOPLE cards that are pronouns: they are a subject, but they never take an article. */
private val PRONOUNS = setOf(I, "εσυ")

/**
 * The same three verbs in the third person, for the level whose subject is somebody else. A table
 * and not a rule, because Greek conjugation is not a rule and because this set is closed: a verb
 * card that is not one of these three is not a sentence verb here in the first place.
 */
private val THIRD_PERSON = mapOf(WANT to "θέλει", EAT to "τρώει", DRINK to "πίνει", GO to "πάει")

/** «πάμε» is the card; «πάω» is what one man going to the chemist says. Same table, same reason. */
private val FIRST_PERSON = mapOf(WANT to "θέλω", EAT to "τρώω", DRINK to "πίνω", GO to "πάω")

/** What follows «να»: «να φάω», «να πιω». The one form of these verbs that is not their own stem. */
private val SUBJUNCTIVE = mapOf(EAT to "φάω", DRINK to "πιω", GO to "πάω")

/** Why he wants it. The reason belongs to the verb, so no board ever says «πίνω γιατί πεινάω». */
private val BECAUSE_VERB = mapOf(EAT to "πεινάω", DRINK to "διψάω")

private const val TO = "να"
private const val BECAUSE = "γιατί"
private const val WITH = "με"
private const val WHERE = "πού"
private const val IS = "είναι"

/** The Greek question mark. Written as itself, on the sentence rather than on the last card. */
private const val QUESTION_MARK = ";"

/**
 * What «πίνω» takes, and what «τρώω» does not.
 *
 * Everything FOOD holds that is not here is a meal, so a drink missing from this line is a board
 * that says «τρώω λεμονάδα» — which is why the two the phase 13 vocabulary added are on it.
 */
private val DRINKABLE = setOf("νερο", "καφες", "τσαι", "γαλα", "μπυρα", "κρασι", "χυμος", "λεμοναδα", "πορτοκαλαδα")

/** The time words that end a sentence. The seed's other TIME cards are days and parts of the day. */
private val TIME_WORDS = setOf("τωρα", "σημερα", "αυριο", "μετα")

/**
 * The PLACES cards also hold what takes him to a place and where he goes once he is inside it, and
 * neither follows a bare «πάμε»: «πάμε αυτοκίνητο», «πάμε κρεβάτι» want a preposition he is not
 * being asked for here, and a module for a man whose deficit is dropped function words must not
 * model one. «πάμε σπίτι», «πάμε καφετέρια», «πάμε δουλειά», «πάμε θάλασσα», «πάμε μπάνιο» are all
 * the whole sentence as they stand.
 *
 * Swept once against the seed's twenty-two PLACES words: three vehicles, the street, and the three
 * rooms of a house are the whole of it; the other fifteen are bare-noun goals.
 *
 * Level 6 has the preposition, so it uses [VEHICLES] alone and every other place is a destination.
 *
 * Swept again over the fourteen places phase 13 added: «πάμε φούρνο», «πάμε περίπτερο», «πάμε
 * παραλία», «πάμε πλατεία» are all whole sentences as they stand, and the two that are not are the
 * two that name where one already *is* rather than somewhere to go — «πάμε στη γειτονιά», «πάμε
 * στην πόλη» — so they wait for the preposition at level 6.
 */
private val NOT_DESTINATIONS = setOf(
    "ταξι", "λεωφορειο", "αυτοκινητο", "δρομος", "κουζινα", "κρεβατι", "μπαλκονι", "γειτονια", "πολη",
)

/** The PLACES cards that are not somewhere to be but something to go in: «με το λεωφορείο». */
private val VEHICLES = setOf("ταξι", "λεωφορειο", "αυτοκινητο")

/**
 * The THINGS cards that are not anywhere: the weather and the sky, and a thing you hear rather than
 * find. «πού είναι ο ήλιος;» parses and nobody says it. See [Roles.locatable].
 *
 * And the two PLACES that name where one already **is** rather than a place to find: «πού είναι η
 * γειτονιά;» is the same sentence as «πού είναι ο ήλιος;». They are in [NOT_DESTINATIONS] for the
 * neighbouring reason — one list is about going there, this one about looking for it, and these two
 * words fail both.
 */
private val NOT_SOMEWHERE = setOf("ηλιος", "βροχη", "μουσικη", "γειτονια", "πολη")

/** One article, and what it agrees with. See [Roles.oddArticle] and [Roles.blank]. */
private class ArticleShape(val form: NounForm, val case: Case, val contracted: Boolean, val label: String)

/**
 * Every definite article this module can put on a card, with the gender, number and case each one
 * belongs to. Written in full — «την», never «τη» — because a card that is standing on its own is
 * not standing before anything, and the final «ν» is a rule about what comes next.
 */
private val ARTICLE_SHAPES: List<ArticleShape> = buildList {
    for (gender in Gender.entries) for (plural in listOf(false, true)) {
        val form = NounForm(gender, plural)
        for (case in Case.entries) add(ArticleShape(form, case, contracted = false, label = Greek.article(form, case)))
        add(ArticleShape(form, Case.ACCUSATIVE, contracted = true, label = Greek.contracted(form)))
    }
}

/** Enough goes at a shape to fill a sitting, few enough that a pool which cannot fill it gives up. */
private const val TRIES_PER_SENTENCE = 4
