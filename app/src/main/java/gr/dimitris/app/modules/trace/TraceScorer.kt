package gr.dimitris.app.modules.trace

import kotlin.math.ceil
import kotlin.math.hypot

/** A point on the canvas, in pixels. Its own type so the scorer stays free of Compose and Android. */
data class Pt(val x: Float, val y: Float)

/**
 * One point of the letter's outline, and which piece of the letter it belongs to.
 *
 * The piece is what makes a shape a shape. Distance alone says how near his ink came to *some* part
 * of the letter, and a big enough scribble is always near some part of it; the segments ask the
 * other question — did he go over *this* piece, and this one, and this one. A «Κ» drawn over an «Η»
 * never touches the crossbar, and that is the sentence "it is not an Η" written as a number.
 *
 * [letter] is which letter of the word this point belongs to. A word is marked letter by letter —
 * eight Greek letters fill the same eight places whatever they are, so a word taken as one shape
 * cannot tell «Δημήτρης» from «Καλημέρα» — and every point has to know whose it is.
 *
 * [accent] is true on the points of a **mark on** the letter rather than a stroke of it: the tonos
 * over an «ή», the two dots of an «ϊ». Those pieces are drawn and they are measured, but they are
 * not pieces he has to go over — see [TraceScorer.segments].
 */
data class TemplatePoint(val pt: Pt, val segment: Int, val letter: Int = 0, val accent: Boolean = false)

/**
 * One letter of what he was asked to write, as everything but the font sees it: the character
 * itself, how tall it came out, the slice of the paper it stands on, and how long it is as a line.
 *
 * [left] and [right] are what his ink is shared out by. A stroke that crosses from one letter into
 * the next is split at the boundary, point by point, so the «Κ» he drew over the «η» is the «η»'s
 * problem and not the «μ»'s.
 *
 * [inside] is this letter's own ink, and it is what precision is measured against. The word's mask
 * says "there is ink here" anywhere in the word, so a point given to the «η» but sitting on the
 * «μ» beside it used to count as precise; against the letter's own mask it does not. Null means the
 * letter has no mask of its own — a hand-built target, or a glyph too thin to fill — and the word's
 * is used instead, which is what the marking did before per-letter masks existed.
 */
data class GlyphLetter(
    val text: String,
    val height: Float,
    val left: Float,
    val right: Float,
    val skeleton: Float,
    val inside: ((Pt) -> Boolean)? = null,
)

/**
 * How one letter of the word came out. [text] is the letter as it is shown, so it can be said.
 *
 * [ink] is how much line he drew on this letter against how long the letter is — the same ratio
 * [TraceScore.inkRatio] is for the whole word, per letter, so that scribbling over one letter of
 * eight can be seen and refused where the word's own average hides it.
 */
data class LetterScore(
    val text: String,
    val coverage: Float,
    val precision: Float,
    val passed: Boolean,
    val ink: Float = 0f,
    /**
     * Whether he put ink on the letter's accent — null on a letter that has none.
     *
     * Nothing is decided by it: a tonos is spelling and not shape, and refusing a correctly formed
     * «ι» because he did not put the mark over it would fail almost every word of the dictation
     * level, where he has nothing on the paper to copy the mark from. It is here because
     * `docs/ADAPTATION.md` has a real question to ask of it — whether he *writes* the accents once
     * he can hear the words — and that question cannot be asked of a row that never recorded it.
     */
    val accent: Boolean? = null,
)

/**
 * Everything he was asked to write, as the scorer needs it: the outline in pieces, the letters those
 * pieces belong to, how long the whole thing is as a line, and where the ink is.
 */
data class Target(
    val points: List<TemplatePoint>,
    val letters: List<GlyphLetter>,
    val skeleton: Float = 0f,
    /** True where the ink is. Counters are not ink. See [Glyphs]. */
    val inside: (Pt) -> Boolean = { false },
)

/**
 * How close one traced letter came.
 *
 * [coverage] is how much of the letter he went over, 0..1, counted in pieces rather than in points:
 * the fraction of the letter's segments his ink reached. [precision] is the other half — how much of
 * what he drew was on the letter at all, 0..1 — and it is the half that refuses a wrong letter form.
 * Both are needed: a careful stroke down one stem of an «Α» is all precision and a quarter of a
 * letter, and a fast loop around the whole thing covers everything and is nowhere near it.
 *
 * [meanDistance] is how far off the letter he was, in pixels. Nothing is decided by it any more —
 * it is kept because it is the one number a person reading the attempt rows can picture.
 *
 * [tooMuchInk] is the one refusal that is not about shape: he drew far more line than the letter is
 * long. Colouring a letter in reaches every piece of it and stays on the ink, so it scores 1.00 and
 * 1.00 without a letter ever having been written; and because precision is a ratio, enough scribble
 * inside the ink dilutes a wrong letter until it passes. It is a different sentence on the screen,
 * because "do less" is not the same advice as "look at the shape". [inkRatio] is how much line he
 * drew against how long the letter is, kept on every row so the budget can be set from real hands.
 * The budget is measured **per letter**: scribbling over one letter of eight is well inside the
 * word's own budget, and it is still not writing. On that refusal [letters] carries every letter
 * with its own [LetterScore.ink] and nothing else — nothing was marked — and `passed` is false on
 * exactly the letters that were flooded, so the paper can mark them and the nudge can name them.
 *
 * [letters] is the word marked letter by letter, and [passed] is the *worst* of them, not the
 * average: a word is eight letters he is learning to write, and seven of them being right is seven
 * of them being right. [coverage] and [precision] above are the whole word, for the record.
 */
data class TraceScore(
    val coverage: Float,
    val precision: Float,
    val meanDistance: Float,
    val passed: Boolean,
    val tooMuchInk: Boolean = false,
    val letters: List<LetterScore> = emptyList(),
    val inkRatio: Float = 0f,
) {
    /** The letters he has to look at again, in the order they are written. */
    val failed: List<LetterScore> get() = letters.filter { !it.passed }
}

/**
 * How hard the writing module marks, as a caregiver sets it: «Χαλαρό», «Κανονικό», «Αυστηρό».
 *
 * The tolerances are in dp, not in fractions of the letter, because they are about the size of his
 * fingertip and his fingertip is the same size on a capital and on a word of eight letters. That is
 * the whole of the field-test bug: a «Κ» drawn over an «Η» passed, because on a big letter a
 * tolerance of a tenth of its height is most of the paper.
 *
 * A fingertip is not the whole answer either, and [Strictness.of] holds the other half: on a word of
 * eight letters, 12 dp is a quarter of a letter, and the wrong letter would pass again — so the two
 * distances are also capped by the letter's own size. Big letters are marked in fingertips, small
 * ones in letters, and neither is marked in whichever unit happens to be kinder.
 */
enum class TraceStrictness(
    val toleranceDp: Float,
    val coverRadiusDp: Float,
    val minCoverage: Float,
    val minPrecision: Float,
) {
    LOOSE(16f, 18f, 0.75f, 0.75f),
    NORMAL(12f, 14f, 0.80f, 0.80f),
    STRICT(8f, 10f, 0.90f, 0.90f);

    companion object {
        /** What he is marked at until somebody says otherwise. */
        val DEFAULT = NORMAL

        /** The stored name read back, with anything else — an old backup, a newer version — as [DEFAULT]. */
        fun named(name: String?): TraceStrictness = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}

/**
 * One strictness worked out for one screen: the two distances in pixels, and the two lines he has to
 * clear. Made by [of] and handed to [TraceScorer.score], so the scorer itself knows nothing about
 * densities, levels or settings.
 */
data class Strictness(
    val tolerancePx: Float,
    val coverRadiusPx: Float,
    val minCoverage: Float,
    val minPrecision: Float,
) {
    companion object {
        /**
         * What writing from memory is worth. Level 5 shows the word once and takes it away; what
         * comes back is the shape he remembers, not the shape he can follow, and it is marked as
         * such. The *distances* are not loosened — a wrong letter is still a wrong letter — only how
         * much of it he has to get.
         */
        const val RECALL_ALLOWANCE = 0.15f

        /** How near "near" may ever mean, as a fraction of the letter's own height. */
        const val CAP_FRACTION = 0.09f

        /**
         * [templateHeight] is how tall the letter came out, in the same pixels. It is a ceiling on
         * both distances, and it is what keeps the marking honest at word scale.
         *
         * A capital fills the paper: 12 dp is 5 % of it, the cap never binds, and he is marked in
         * fingertips as he should be. «Δημήτρης» in the same box is eight letters wide, each of them
         * a fifth of that height — and there 12 dp is a quarter of a letter and 14 dp of reach is
         * wider than one, so every wrong shape lands "near enough" to something. The cap is one
         * segment's length, the same twelfth of the letter that a piece of it is: whatever he is
         * writing, "near" can never mean further than a fraction of the letter itself. It is a
         * little under a piece of the letter ([CAP_FRACTION] against [TraceScorer.SEGMENT_FRACTION]),
         * because at a twelfth a «Κ» over an «Η» of a word still cleared «Χαλαρό».
         *
         * At word scale this is each *letter's* own height, not the word's: the «τ» of «Δημήτρης» is
         * half the height of its «Δ», and a fifth of a «τ» is not the same distance as a fifth of a
         * «Δ».
         */
        fun of(level: TraceStrictness, density: Float, templateHeight: Float, recall: Boolean): Strictness {
            // A density of zero is a screen nobody can write on; 1 keeps the numbers meaning dp.
            val scale = if (density > 0f) density else 1f
            val give = if (recall) RECALL_ALLOWANCE else 0f
            // No letter, no ceiling: there is nothing to be judged at the scale of.
            val cap = if (templateHeight > 0f) templateHeight * CAP_FRACTION else Float.MAX_VALUE
            return Strictness(
                tolerancePx = minOf(level.toleranceDp * scale, cap),
                coverRadiusPx = minOf(level.coverRadiusDp * scale, cap),
                minCoverage = (level.minCoverage - give).coerceAtLeast(0f),
                minPrecision = (level.minPrecision - give).coerceAtLeast(0f),
            )
        }
    }
}

/**
 * Is that letter, near enough? Pure: no Android, no Compose, no clock. It is handed the finger's
 * path, the letter's outline cut into segments and the letter's ink, all in canvas pixels.
 *
 * Two numbers answer it, and they answer different halves. **Coverage** — did he go over every piece
 * of the letter? — is what a «Κ» over an «Η» fails: the crossbar is never touched. **Precision** —
 * was what he drew on the letter? — is what a big shape over a small letter fails: the diagonals of
 * that «Κ» are out in the white. Neither alone is enough, which is why the old scorer, which had
 * only a mean distance and a coverage counted in points, passed both of Chris's wrong letters.
 *
 * The ink — [inside] — is what makes precision fair. A man told «γράψε Κ» draws one line down the
 * middle of each stroke; the outline is the two *edges* of every stroke, so measured against the
 * outline alone his line is half a stem out everywhere and the better he writes the worse he scores.
 * Measured against the ink, a line down the middle costs nothing, which is what the exercise asks
 * for, and the outline is left doing the job it is good at: saying which pieces he went over.
 */
object TraceScorer {
    /**
     * How long one segment of the letter is, as a fraction of the letter's height. A twelfth of the
     * height is about the length of the shortest thing anyone would call a stroke — the crossbar of
     * an «Η» is a few of them, the dot of an accent is one — so a letter is cut into pieces the size
     * of the movements it is made of.
     */
    const val SEGMENT_FRACTION = 0.12f

    /**
     * How many pieces long a contour has to be before it counts as a stroke of the letter at all.
     *
     * Eight, which for every Greek letter is the line between a **stroke** and a **mark**: the
     * shortest real contour in the alphabet — the middle bar of a «Ξ», the stem of an «ι» — is
     * seventeen pieces or more, and the only things below the line are the diacritics (the tonos,
     * the dialytika). Above it a contour is cut into pieces of a twelfth of the letter, so the ring
     * of an «Ο» gone a third of the way round reads as a third of a letter; below it a contour is
     * one piece, and one he does not have to go over. See [segments].
     */
    const val MIN_SEGMENTS = 8

    /**
     * How much more line than the letter itself he may draw before it stops being writing. The
     * letter as a line is about half its outline (up one side of every stroke and back down the
     * other), and two and a half times that leaves room for a hand that goes over a stroke twice,
     * overshoots every corner, and joins letters — while a scribble that colours the letter in, or a
     * wrong letter padded out until its bad ink is a minority, is several times over it.
     */
    const val INK_BUDGET = 2.5f

    /**
     * How far apart his path is cut into points before it is judged, as a fraction of the tolerance.
     * Without resampling a slow finger — hundreds of samples in one corner — would weigh that corner
     * a hundred times, and a fast one would be judged on six points. A third of the tolerance is fine
     * enough that no piece of the letter is stepped over and coarse enough to stay cheap.
     */
    const val STEP_OF_TOLERANCE = 1f / 3f

    /** The same, for the arcade's line-following game, which is measured against a path's own size. */
    const val RESAMPLE_FRACTION = 0.03f

    /** Nothing to judge: no coverage, no precision, the worst distance there is, and never a pass. */
    private val NOTHING = TraceScore(0f, 0f, Float.MAX_VALUE, false)

    /**
     * The letter's outline, cut into pieces. [contours] is every closed line of the glyph in walk
     * order — the outside of an «Ο» and then the hole in it — and each is split by arc length into
     * `ceil(length / (height * SEGMENT_FRACTION))` pieces.
     *
     * A contour shorter than [MIN_SEGMENTS] pieces is not a stroke of the letter at all — in Greek it
     * is a **diacritic**, the tonos over an «ή» or the two dots of an «ϊ» — and it is cut into one
     * piece and marked [TemplatePoint.accent]. The mark is still drawn on the paper and his ink on it
     * is still measured; what it is not is a piece he has to go over. Phase 11's floor made it eight
     * required pieces, which measured out at a fifth of an «ί», so a correctly formed letter written
     * without its accent scored 0.65 and was refused — and at the dictation level, where the letter
     * is not on the paper to copy the mark from, that is almost every word in his vocabulary. The
     * accent is spelling; the two lines this scorer holds him to are about shape.
     *
     * The ids run on across contours, so the hole in an «Ο» is pieces of its own to be gone over and
     * not more of the outside. Kept here, away from the font, so the rule can be read and tested
     * without a device.
     */
    fun segments(contours: List<List<Pt>>, height: Float): List<TemplatePoint> {
        if (height <= 0f) return emptyList()
        val target = height * SEGMENT_FRACTION
        val out = ArrayList<TemplatePoint>()
        var next = 0
        for (contour in contours) {
            if (contour.isEmpty()) continue
            // Where each point falls along its own contour, and how long that contour is.
            val at = FloatArray(contour.size)
            for (i in 1 until contour.size) at[i] = at[i - 1] + dist(contour[i - 1], contour[i])
            val length = at.last()
            // Too short to be a stroke of the letter — a tonos, a dot, or a hundred points on the
            // same pixel: one piece, and a mark rather than a piece of the letter.
            val accent = length < target * MIN_SEGMENTS
            val pieces = if (accent) 1 else ceil(length / target).toInt()
            for (i in contour.indices) {
                val piece = if (length <= 0f) 0 else ((at[i] / length) * pieces).toInt().coerceIn(0, pieces - 1)
                out += TemplatePoint(contour[i], next + piece, accent = accent)
            }
            next += pieces
        }
        return out
    }

    /**
     * Roughly how long the letter is *as a line* — what a pen would travel to write it once.
     *
     * It is estimated as half the outline, and the estimate is the whole of it: a glyph's outline is
     * a closed loop round every stroke, so it goes up one side of the stem and back down the other,
     * and half of it is one trip along the middle. It over-estimates a little (the caps at the ends
     * of a stroke are counted, and both sides of a stroke are longer than the middle of a curve) and
     * it is generous where it is wrong, which is the right way round for a budget nobody should hit
     * by writing carefully. Zero when there is no letter, which switches the budget off.
     */
    fun skeleton(contours: List<List<Pt>>): Float {
        var total = 0f
        for (contour in contours) for (i in 1 until contour.size) total += dist(contour[i - 1], contour[i])
        return total / 2f
    }

    /**
     * Everything he drew, judged as what it is: separate strokes, against one letter.
     *
     * Each stroke is resampled on its own, so the gap between lifting his finger and putting it down
     * again is never walked over — a «Κ» written as a stem and two diagonals must not be marked as
     * though he had dragged a line back across the letter between them.
     *
     * Coverage is counted over the pieces of the letter and not over his strokes: what is being asked
     * is how much of the letter he went over, so a finger that went round the same corner twenty
     * times covers one corner, however many points it left behind. Only pieces that have outline
     * points in them are counted — a short contour is cut into [MIN_SEGMENTS] pieces whether or not
     * there are points enough to fill them, and a piece with nothing in it is not a piece he missed.
     * And where his finger is on the ink, the radius is measured from the edge of the letter rather
     * than from his finger: both edges of a stem are the one stroke he is drawing down the middle of.
     *
     * A word is marked letter by letter and passes only when every letter of it does. Each letter
     * gets his ink from its own slice of the paper, its own ceiling on the two distances, and its own
     * pieces to be gone over; the numbers on [TraceScore] itself are the whole word, for the record.
     *
     * [GlyphLetter.skeleton] is how long one letter is as a line (see [skeleton]); drawing more than
     * [INK_BUDGET] times that **on any one letter** is refused as [TraceScore.tooMuchInk] before the
     * shape is judged at all. Per letter, because a scribble over one letter of eight is a seventh
     * of the word's own budget and is no more writing than a scribble over a capital.
     * A [Target.skeleton] of zero switches the budget off, for a caller with no letter to measure
     * against.
     *
     * Long enough to be worth a background thread on a big word: it is one distance per ink point
     * per outline point, and the caller runs it off the main one.
     */
    fun score(
        strokes: List<List<Pt>>,
        target: Target,
        level: TraceStrictness,
        density: Float,
        recall: Boolean,
    ): TraceScore {
        val letters = target.letters
        if (target.points.isEmpty() || letters.isEmpty()) return NOTHING

        // One strictness per letter, because the ceiling on both distances is the letter's own
        // height: the «τ» of «Δημήτρης» is half the height of its «Δ».
        val bars = letters.map { Strictness.of(level, density, it.height, recall) }
        // Fine enough for the smallest letter, so no piece of it is stepped over.
        val step = (bars.minOf { it.tolerancePx } * STEP_OF_TOLERANCE).coerceAtLeast(MIN_STEP)

        // Evenly spaced along each stroke, so speed stops being part of the mark — and shared out
        // among the letters as it is walked. A stroke that runs from one letter into the next is
        // split where they meet, point by point, and so is its *length*: half of every step goes to
        // the letter at each end of it, so the ink is divided exactly as the points are.
        val walked = ArrayList<Pt>()
        val hisInk = Array(letters.size) { ArrayList<Pt>() }
        val drawnPer = FloatArray(letters.size)
        var drawn = 0f
        for (stroke in strokes) {
            if (stroke.isEmpty()) continue
            val even = resample(stroke, step)
            var previous = -1
            for (i in even.indices) {
                val owner = nearestLetter(even[i], letters)
                hisInk[owner] += even[i]
                // Measured along the path he actually walked, so a slow finger is not more ink.
                // The gap between two strokes is never walked: this only ever runs inside one.
                if (i > 0) {
                    val d = dist(even[i - 1], even[i])
                    drawn += d
                    drawnPer[previous] += d / 2f
                    drawnPer[owner] += d / 2f
                }
                previous = owner
            }
            walked += even
        }
        // Nothing drawn is not a bad attempt, it is no attempt.
        if (walked.isEmpty()) return NOTHING
        val ratio = if (target.skeleton > 0f) drawn / target.skeleton else 0f
        val used = FloatArray(letters.size) { if (letters[it].skeleton > 0f) drawnPer[it] / letters[it].skeleton else 0f }
        // Refused before the shape is judged: colouring the letter in reaches every piece of it and
        // never leaves the ink, and no number below could tell it from writing.
        //
        // Per letter, not per word: a scribble over one letter of eight is a seventh of the word's
        // own budget and every bit as much not-writing as a scribble over a capital. A word skeleton
        // of zero is a caller with no letter to measure against, and switches the whole budget off.
        if (target.skeleton > 0f && letters.indices.any { used[it] > INK_BUDGET }) {
            return TraceScore(
                0f, 0f, Float.MAX_VALUE, passed = false, tooMuchInk = true, inkRatio = ratio,
                // Nothing was marked, so there are no shape numbers to give; what each letter does
                // carry is its own ink, and whether that letter is one of the flooded ones. The
                // paper marks those and the nudge names them, exactly as it does for a wrong shape.
                letters = letters.indices.map {
                    LetterScore(letters[it].text, 0f, 0f, passed = used[it] <= INK_BUDGET, ink = used[it])
                },
            )
        }

        // Each letter's own outline, so a piece of the «η» is not a piece of the «μ».
        val outlines = Array(letters.size) { ArrayList<TemplatePoint>() }
        for (t in target.points) if (t.letter in outlines.indices) outlines[t.letter] += t

        var coveredAll = 0
        var presentAll = 0
        var onLetterAll = 0
        var distanceAll = 0.0
        val scores = ArrayList<LetterScore>(letters.size)
        for (i in letters.indices) {
            val outline = outlines[i]
            // A letter with nothing to trace — a space between two words — is not one he can miss.
            if (outline.isEmpty()) continue
            val mine = hisInk[i]
            val bar = bars[i]
            // This letter's own ink. The word's mask is true anywhere in the word, so against it a
            // point given to the «η» but standing on the «μ» next to it was precise; against the
            // «η»'s own it is not. A letter with no mask of its own falls back to the word's, which
            // is what every target built by hand has.
            val ink = letters[i].inside ?: target.inside

            // The pieces of the letter he has to go over, and the marks *on* it that he does not.
            // A shape made of nothing but marks is not a letter with nothing to trace, it is a
            // caller with a very short contour: hold him to it rather than divide by zero.
            val present = HashSet<Int>()
            val marks = HashSet<Int>()
            for (t in outline) if (t.accent) marks += t.segment else present += t.segment
            if (present.isEmpty()) { present += marks; marks.clear() }
            val covered = HashSet<Int>()
            val away = FloatArray(outline.size)
            var onLetter = 0
            var total = 0.0
            // Whether any of his ink is really *on the mark* rather than merely within reach of it.
            // The reach is a fingertip wide by design, and on an «ί» the tonos sits a fingertip above
            // the stem — so "covered" would say he wrote the accent every time he wrote the letter.
            // What answers it honestly is whose piece his ink is nearest to: the stem's ink is nearer
            // the stem, the tonos's ink is nearer the tonos, and that holds however close they sit.
            var onMark = false

            for (p in mine) {
                // On the ink costs nothing: a line down the middle of a stroke is the letter, written.
                val onInk = ink(p)
                var best = Float.MAX_VALUE
                var nearest = -1
                if (onInk) {
                    // The radius is measured from the edge of the letter, not from the middle of it:
                    // a man told «γράψε Η» draws one line down the middle of the stem, and the *far*
                    // edge of that stem is not a piece of the letter he missed — it is the other side
                    // of the very stroke he is standing on. Without this the stem of a big capital,
                    // 17 dp of ink across on the device's own font, would be wider than the 14 dp he
                    // is allowed, and writing the letter correctly would fail. It costs the second
                    // pass below, because the reach is not known until the nearest edge is.
                    for (j in outline.indices) {
                        val d = dist(p, outline[j].pt)
                        away[j] = d
                        if (d < best) { best = d; nearest = j }
                    }
                    val reach = best + bar.coverRadiusPx
                    for (j in outline.indices) if (away[j] <= reach) covered += outline[j].segment
                    onLetter++
                } else {
                    // Off the letter: the reach is the plain radius, so one pass answers both.
                    for (j in outline.indices) {
                        val d = dist(p, outline[j].pt)
                        if (d < best) { best = d; nearest = j }
                        if (d <= bar.coverRadiusPx) covered += outline[j].segment
                    }
                    total += best
                    if (best <= bar.tolerancePx) onLetter++
                }
                if (!onMark && nearest >= 0 && best <= bar.coverRadiusPx && outline[nearest].accent) onMark = true
            }

            // No ink at all on this letter is not a letter he wrote. Only the pieces he *has* to go
            // over are counted, on both sides of the fraction: the tonos is measured and reported,
            // never required, so a coverage can never come out above 1 either.
            val went = covered.count { it in present }
            val coverage = went.toFloat() / present.size
            val precision = if (mine.isEmpty()) 0f else onLetter.toFloat() / mine.size
            scores += LetterScore(
                text = letters[i].text,
                coverage = coverage,
                precision = precision,
                passed = coverage >= bar.minCoverage && precision >= bar.minPrecision,
                ink = used[i],
                // Only where there is one to hit. See [LetterScore.accent].
                accent = if (marks.isEmpty()) null else onMark,
            )
            coveredAll += went
            presentAll += present.size
            onLetterAll += onLetter
            distanceAll += total
        }

        if (scores.isEmpty()) return NOTHING
        return TraceScore(
            coverage = coveredAll.toFloat() / presentAll,
            precision = onLetterAll.toFloat() / walked.size,
            meanDistance = (distanceAll / walked.size).toFloat(),
            // The worst letter, not the average of them: seven letters right out of eight is seven
            // letters right, and the eighth is the one he is practising.
            passed = scores.all { it.passed },
            letters = scores,
            inkRatio = ratio,
        )
    }

    /**
     * Whose ink this point is. The letters stand side by side on the paper, so the slice of it a
     * point falls in says which letter he was writing; ink beyond both ends of the word belongs to
     * the letter it is nearest.
     */
    private fun nearestLetter(p: Pt, letters: List<GlyphLetter>): Int {
        var best = 0
        var away = Float.MAX_VALUE
        for (i in letters.indices) {
            val letter = letters[i]
            if (p.x >= letter.left && p.x <= letter.right) return i
            val d = if (p.x < letter.left) letter.left - p.x else p.x - letter.right
            if (d < away) { away = d; best = i }
        }
        return best
    }

    /**
     * One stroke against a bare line, marked the old way: how far off it he was on average, and how
     * much of it he went over. This is the arcade's line-following game — a road, not a letter, with
     * no ink to be inside of and no shape to get wrong — and it is the only caller left.
     *
     * [tolerancePx] is how far off the line he may be on average and [coverageRadiusPx] how near a
     * point of it he has to have come for it to count as gone over.
     */
    fun score(
        user: List<Pt>,
        template: List<Pt>,
        templateHeight: Float,
        tolerancePx: Float,
        coverageRadiusPx: Float,
        minCoverage: Float = 0.6f,
    ): TraceScore {
        if (template.isEmpty() || templateHeight <= 0f) return NOTHING
        val step = (templateHeight * RESAMPLE_FRACTION).coerceAtLeast(MIN_STEP)
        val walked = if (user.isEmpty()) emptyList() else resample(user, step)
        if (walked.isEmpty()) return NOTHING

        var total = 0.0
        var near = 0
        for (p in walked) {
            val d = nearest(p, template)
            total += d
            if (d <= tolerancePx) near++
        }
        val meanDistance = (total / walked.size).toFloat()
        val covered = template.count { t -> walked.any { dist(it, t) <= coverageRadiusPx } }
        val coverage = covered.toFloat() / template.size
        return TraceScore(
            coverage = coverage,
            // Said for the record only: what passes a line here is the mean distance, as it always was.
            precision = near.toFloat() / walked.size,
            meanDistance = meanDistance,
            passed = meanDistance <= tolerancePx && coverage >= minCoverage,
        )
    }

    /**
     * [points] walked as a polyline, with a point every [step] pixels: the first point, then one at
     * every [step] of path length, and the far end only when it happens to land on one.
     *
     * The finger reports points as fast as the screen can read it, so what comes in is a crowd where
     * it went slowly and a scattering where it went fast. This makes the path even, which is what
     * lets every count above mean anything at all.
     */
    fun resample(points: List<Pt>, step: Float): List<Pt> {
        if (points.size < 2 || step <= 0f) return points
        val out = ArrayList<Pt>(points.size)
        out += points.first()
        // How far past the last emitted point we already are.
        var carried = 0f
        for (i in 1 until points.size) {
            var from = points[i - 1]
            val to = points[i]
            var remaining = dist(from, to)
            // A segment long enough to hold one or more steps is cut where they fall, and what is
            // left over is carried into the next one — the spacing is along the path, not per segment.
            while (carried + remaining >= step) {
                val t = (step - carried) / remaining
                val cut = Pt(from.x + t * (to.x - from.x), from.y + t * (to.y - from.y))
                out += cut
                from = cut
                remaining = dist(from, to)
                carried = 0f
            }
            carried += remaining
        }
        return out
    }

    /** Distance from [p] to the nearest point of [template]. Brute force: a letter is ~700 points. */
    private fun nearest(p: Pt, template: List<Pt>): Float {
        var best = Float.MAX_VALUE
        for (t in template) {
            val d = dist(p, t)
            if (d < best) best = d
        }
        return best
    }

    private fun dist(a: Pt, b: Pt): Float = hypot(a.x - b.x, a.y - b.y)

    /** A floor under the resample step, so a degenerate box cannot ask for an infinite number of points. */
    private const val MIN_STEP = 0.5f
}
