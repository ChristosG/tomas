package gr.dimitris.app.modules.singsay

import gr.dimitris.app.core.greek.Greek
import gr.dimitris.app.core.greek.Syllabifier
import java.text.Normalizer
import kotlin.math.abs

enum class Pitch(val hz: Double) { LOW(196.0), HIGH(246.94) }

/**
 * How fast the melody moves, as a caregiver sets it. NORMAL is the pace [Melody] has always sung
 * at ([Melody.NOTE_MS], [Melody.GAP_MS]); SLOW gives every note longer to sound and a wider
 * silence after it, for a tired ear that needs the tune to move more slowly than his speech does.
 */
enum class Tempo(val noteMs: Int, val gapMs: Int) {
    NORMAL(550, 80),
    SLOW(750, 110);

    companion object {
        /** The pace nobody has ever asked to change. */
        val DEFAULT = NORMAL

        /** The stored name read back, with anything else — an old backup, a newer version — as [DEFAULT]. */
        fun named(name: String?): Tempo = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * Which two frequencies HIGH and LOW mean, as a caregiver sets it. NORMAL is [Pitch]'s own two
 * frequencies — the register the melody has always sung in; LOW drops both notes for a voice, or a
 * day, a lower key sits easier under. [hz] is how the synth is meant to read a note's frequency —
 * never [Pitch.hz] directly — so the whole tune moves together when the key changes.
 */
enum class Key(val lowHz: Double, val highHz: Double) {
    NORMAL(196.0, 246.94),
    LOW(146.83, 185.0);

    /** The frequency this key gives one [Pitch]. */
    fun hz(pitch: Pitch): Double = if (pitch == Pitch.LOW) lowHz else highHz

    companion object {
        /** The key nobody has ever asked to change. */
        val DEFAULT = NORMAL

        /** The stored name read back, with anything else — an old backup, a newer version — as [DEFAULT]. */
        fun named(name: String?): Key = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * One sung syllable.
 *
 * [breathBefore] is set on the first note of every breath group after the first (see
 * [Melody.forPhrase]): a place to take a breath before singing on. It is false on every note of a
 * phrase short enough to be sung in one breath, which is every phrase the module had before phase
 * 13 — nothing about the short cards changed.
 */
data class Note(val syllable: String, val pitch: Pitch, val wordIndex: Int, val breathBefore: Boolean = false)

/**
 * Two-note melody for Melodic Intonation Therapy: the stressed syllable of each word is HIGH,
 * everything else LOW. Monosyllables are LOW, even if they happen to carry a stray tonos. A
 * multi-syllable word with no written accent (e.g. an all-capitals word) gets HIGH on its last
 * syllable so it still has a peak. If none of that gives the phrase any HIGH note at all — a run
 * of unaccented monosyllables, say — the very last note is raised to HIGH so the melody he hears
 * always has a shape, never a flat line.
 *
 * Since phase 13 a phrase is also cut into **breath groups**. Dimitris judged the tile pointless at
 * word length — he can say «νερό» — and melodic intonation therapy is for what he cannot say: whole
 * everyday sentences. A twelve-syllable sentence cannot be sung in one breath at
 * [NOTE_MS] a note, so [forPhrase] marks where to breathe and the rest of the module follows that
 * mark: the synth rests three gaps there ([gapAfter]) and the syllable row shows the group boundary
 * as a little extra space. Nothing new is on the screen and no prompt changed — the breath is a
 * property of the phrase, not a control.
 */
object Melody {
    /** [Tempo.NORMAL]'s own timing, kept as this object's default (an enum constant is not a compile-time constant). */
    const val NOTE_MS = 550
    const val GAP_MS = 80

    /**
     * The longest a breath group may be. Six syllables at [NOTE_MS] plus [GAP_MS] is under four
     * seconds of singing — a phrase of that length is what the module was already asking of him, and
     * it is what a man who is short of breath after a stroke can hold in one go. Longer phrases are
     * cut into groups of at most this many syllables; a single word longer than this is left whole,
     * because a breath taken inside a word is not a breath group, it is a stutter.
     */
    const val BREATH_SYLLABLES = 6

    /**
     * How many ordinary gaps long the rest between two breath groups is. Three, so that the silence
     * is unmistakably a place to breathe rather than the gap between two syllables — and derived
     * from the gap rather than fixed in milliseconds, so it grows with the caregiver's [Tempo].
     */
    const val BREATH_GAPS = 3

    /**
     * The punctuation a sentence already breathes at. Written Greek marks exactly where the voice
     * stops, so these come first: a comma in «Θα πάω στο φαρμακείο, και μετά στην τράπεζα» is the
     * author of the phrase telling us where the breath belongs.
     *
     * Both question marks are here — the Greek one is usually typed as the ASCII semicolon, but a
     * Greek keyboard can produce U+037E too, and a caregiver's copy-paste can carry either.
     */
    private val BREATH_MARKS = setOf(',', ';', '\u037E', '.', '!', '?', '·')

    /**
     * The words a Greek sentence is most naturally broken **before** when it has no punctuation to
     * break at: the conjunction and the particle that start a new clause. «Θα ήθελα να κλείσω ένα
     * ραντεβού» breathes before «να», not in the middle of «ραντεβού».
     *
     * «κι» is «και» before a vowel («ψωμί κι ένα γάλα») and starts exactly the same clause; written
     * accent-stripped, because that is how they are compared.
     */
    private val BREATH_WORDS = setOf("και", "κι", "να")

    /**
     * The words a breath group must not **end** on when there is another cut as good: the articles,
     * the prepositions and the particles that lean forward onto the word after them. Greek does not
     * pause between «το» and «φαρμακείο» — they are one thing to say — and a rest there is the one
     * way a breath group can make a sentence harder to sing than it was whole.
     *
     * It is a *cost* ([PROCLITIC_COST]) rather than a veto, so it decides ties and one-syllable
     * differences and never drags a group past [BREATH_SYLLABLES]. Only the unambiguous leaners are
     * here: «μου», «του», «με» and their like are left out, because at the end of a group they are
     * far more often the clitic that leans **back** («το κινητό μου») than the article that leans on.
     * Accent-stripped, as [BREATH_WORDS] is.
     */
    private val PROCLITICS = setOf(
        "ο", "η", "το", "οι", "τα", "τον", "την", "ενα", "εναν", "ενας", "μια",
        "σε", "στο", "στη", "στην", "στον", "στους", "στις", "στα", "απο", "για", "προς", "χωρις",
        "να", "θα", "δεν", "μην", "και", "κι", "ας",
    )

    /**
     * What ending a group on a [PROCLITICS] word costs, in the units [imbalance] is measured in —
     * where one syllable of imbalance is worth two. Three, so it outranks a cut that is one syllable
     * better balanced and gives way to one that is two syllables better: a breath in the wrong place
     * is worth about a syllable of evenness, and no more than that.
     */
    private const val PROCLITIC_COST = 3

    /**
     * The melody of [text], one note per syllable, with [Note.breathBefore] marking where the
     * breath groups start.
     *
     * A phrase of at most [BREATH_SYLLABLES] syllables is one group and carries no mark at all, so
     * every short card behaves exactly as it did before phase 13. A longer one is cut in two at the
     * best boundary available and each half is cut again until no group is too long:
     *
     *  1. after a word that carries [BREATH_MARKS], because the sentence says so itself;
     *  2. else before «και», «κι» or «να» ([BREATH_WORDS]), the start of the next clause;
     *  3. else at the word boundary that leaves the two halves most even in syllables, plus
     *     [PROCLITIC_COST] if the first half would end on a word that leans onto the next one.
     *
     * Where several boundaries of the same kind exist the cheapest is taken, and an exact tie goes
     * to the earlier boundary — so the same phrase is always cut the same way.
     */
    fun forPhrase(text: String): List<Note> {
        val words = wordsOf(text)
        val notes = words.flatMap(::notesFor)
        if (notes.isEmpty()) return notes
        val shaped = if (notes.any { it.pitch == Pitch.HIGH }) notes
        else notes.toMutableList().also { it[it.lastIndex] = it.last().copy(pitch = Pitch.HIGH) }
        return breathed(shaped, words)
    }

    /** Where the breath groups of [notes] start, as note indices — what the synth is handed. */
    fun breaths(notes: List<Note>): Set<Int> =
        notes.indices.filterTo(mutableSetOf()) { notes[it].breathBefore }

    /**
     * [notes] split into its breath groups — one group when the phrase is sung in one breath.
     *
     * The one place this walk is written. The screen does not need it (a note knows whether a breath
     * comes before it), but everything that reasons *about* the groups does: what the attempt row
     * records ([gr.dimitris.app.modules.singsay.singSayDetail]'s `groups` and `groupSyllables`) and
     * the tests that argue about the shape of a phrase.
     */
    fun groups(notes: List<Note>): List<List<Note>> {
        if (notes.isEmpty()) return emptyList()
        val out = mutableListOf(mutableListOf<Note>())
        notes.forEachIndexed { i, n ->
            if (n.breathBefore && i > 0) out.add(mutableListOf())
            out.last().add(n)
        }
        return out
    }

    /**
     * The silence after the note at [index], in milliseconds: [BREATH_GAPS] gaps where the next note
     * starts a breath group, one gap everywhere else.
     *
     * One function for both halves of playing a melody — the PCM that is synthesised and the wait
     * between the lit syllables — so the rest he hears and the rest the screen counts can never
     * disagree. [breathBefore] is [breaths]' output: the indices of the notes that start a group.
     */
    fun gapAfter(index: Int, gapMs: Int, breathBefore: Set<Int>): Int =
        if (index + 1 in breathBefore) gapMs * BREATH_GAPS else gapMs

    /** One word of the phrase, with what decides where a breath goes beside it. */
    private data class Word(
        /**
         * Its syllables — never empty: a word [Syllabifier] cannot split counts as one, exactly as
         * [gr.dimitris.app.core.difficulty.Difficulty.syllablesOf] counts it.
         */
        val syllables: List<String>,
        /** Its position among the words of the phrase, which is what [Note.wordIndex] carries. */
        val index: Int,
        /** Punctuation follows it: the strongest place to end a breath group. */
        val closesGroup: Boolean,
        /** It is «και», «κι» or «να»: the word a group is most naturally started with. */
        val opensGroup: Boolean,
        /** It leans onto the word after it ([PROCLITICS]): a group had better not end here. */
        val leansOn: Boolean,
    )

    /**
     * The words of [text], punctuation read before it is thrown away.
     *
     * The letters are what is sung — a trailing comma is not a syllable — but *that* there was a
     * comma is exactly what tells us where he breathes, so it is recorded here rather than lost in
     * the trim. Only marks that follow the word count: an opening quotation mark says nothing about
     * the voice.
     *
     * A token of pure punctuation — the space before the comma in «Θέλω γάλα , παρακαλώ», which a
     * caregiver typing in a hurry leaves behind — is not a word and has no note, but it still says
     * where the voice stops, so its mark is handed to the word in front of it rather than dropped.
     */
    private fun wordsOf(text: String): List<Word> {
        val words = mutableListOf<Word>()
        for (token in text.trim().split(WHITESPACE)) {
            val letters = Greek.normalize(token.trim { !it.isLetter() })
            val marked = token.takeLastWhile { !it.isLetter() }.any { it in BREATH_MARKS }
            if (letters.isEmpty()) {
                if (marked && words.isNotEmpty()) words[words.lastIndex] = words.last().copy(closesGroup = true)
                continue
            }
            val bare = Greek.stripAccents(letters)
            words += Word(
                // A word with no vowel in it comes back whole rather than split, and a blank one
                // cannot reach here, so this list is never empty — see [Word.syllables].
                syllables = Syllabifier.syllables(letters) ?: listOf(letters),
                index = words.size,
                closesGroup = marked,
                opensGroup = bare in BREATH_WORDS,
                leansOn = bare in PROCLITICS,
            )
        }
        return words
    }

    /** The notes of one word: its stressed syllable HIGH, the rest LOW; a monosyllable LOW. */
    private fun notesFor(word: Word): List<Note> {
        val syl = word.syllables
        if (syl.isEmpty()) return emptyList()
        if (syl.size == 1) return listOf(Note(syl[0], Pitch.LOW, word.index))
        val stressed = syl.indexOfFirst { hasTonos(it) }.let { if (it == -1) syl.lastIndex else it }
        return syl.mapIndexed { i, s -> Note(s, if (i == stressed) Pitch.HIGH else Pitch.LOW, word.index) }
    }

    /** [notes] with the first note of every group after the first marked. [words] is the phrase, in order. */
    private fun breathed(notes: List<Note>, words: List<Word>): List<Note> {
        if (notes.size <= BREATH_SYLLABLES) return notes
        val starts = mutableSetOf<Int>()
        var at = 0
        for (group in groupsOf(words)) {
            if (at > 0) starts += at
            at += group.sumOf { it.syllables.size }
        }
        if (starts.isEmpty()) return notes
        return notes.mapIndexed { i, n -> if (i in starts) n.copy(breathBefore = true) else n }
    }

    /**
     * [words] cut into breath groups, each at most [BREATH_SYLLABLES] syllables long where the words
     * allow it. One word on its own is always one group however long it is: «ευχαριστώ» is four
     * syllables of one breath, and a six-syllable word would be sung whole rather than broken.
     */
    private fun groupsOf(words: List<Word>): List<List<Word>> {
        if (words.size < 2 || syllables(words) <= BREATH_SYLLABLES) return listOf(words)
        val cut = cutPoint(words)
        return groupsOf(words.subList(0, cut)) + groupsOf(words.subList(cut, words.size))
    }

    /**
     * Where to cut [words] in two: the index of the word the second half starts with. Punctuation
     * first, then «και»/«κι»/«να», then any boundary — and within one kind, the cheapest cut, ties
     * to the earlier one (see [forPhrase]). [words] has at least two entries, so there is always at
     * least one boundary to choose from.
     */
    private fun cutPoint(words: List<Word>): Int {
        val boundaries = (1 until words.size).toList()
        val marked = boundaries.filter { words[it - 1].closesGroup }
        val joined = boundaries.filter { words[it].opensGroup }
        val candidates = marked.ifEmpty { joined }.ifEmpty { boundaries }
        return candidates.minBy { cost(words, it) }
    }

    /**
     * What a cut costs: how uneven it leaves the two halves, in half-syllables, plus
     * [PROCLITIC_COST] when the first half would end on a word that leans onto the next one.
     */
    private fun cost(words: List<Word>, cut: Int): Int =
        abs(syllables(words) - 2 * syllables(words.subList(0, cut))) +
            if (words[cut - 1].leansOn) PROCLITIC_COST else 0

    private fun syllables(words: List<Word>): Int = words.sumOf { it.syllables.size }

    /** True when the syllable carries a written accent (tonos, U+0301 in NFD). */
    private fun hasTonos(s: String): Boolean = Normalizer.normalize(s, Normalizer.Form.NFD).contains('́')

    private val WHITESPACE = Regex("\\s+")
}
