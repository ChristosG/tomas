package gr.dimitris.app.core.data

import com.google.gson.Gson
import com.google.gson.JsonObject
import gr.dimitris.app.modules.arcade.ArcadePlay
import gr.dimitris.app.modules.arcade.arcadeDetail
import gr.dimitris.app.modules.numbers.NumberExercise
import gr.dimitris.app.modules.numbers.numbersDetail
import gr.dimitris.app.modules.scripts.scriptsDetail
import gr.dimitris.app.modules.sentences.sentencesDetail
import gr.dimitris.app.modules.singsay.Key
import gr.dimitris.app.modules.singsay.Tempo
import gr.dimitris.app.modules.singsay.singSayDetail
import gr.dimitris.app.modules.talkboard.expandDetail
import gr.dimitris.app.modules.talkboard.talkBoardDetail
import gr.dimitris.app.modules.trace.LetterScore
import gr.dimitris.app.modules.trace.Pt
import gr.dimitris.app.modules.trace.TraceScore
import gr.dimitris.app.modules.trace.TraceState
import gr.dimitris.app.modules.trace.TraceStrictness
import gr.dimitris.app.modules.trace.traceDetail
import gr.dimitris.app.modules.wordcoach.wordCoachDetail
import gr.dimitris.app.today.sessionSummaryDetail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What every exercise writes down about itself, read back.
 *
 * Two things are being pinned here, and the second is the one that matters in a year.
 *
 * The first is that the new keys are there and are the type they claim to be — a `ms` that arrives
 * as `"1200"` is a key nobody can average, and it would be found the day someone tried, which is
 * after the year of rows has been written.
 *
 * The second is that **nothing that was already on these rows moved**. The modules have been writing
 * details since phase 3, the caregiver's reports and the sync read them, and the instrumented flow
 * tests assert on the exact text of some of them. So the old keys are asserted at their old names,
 * with their old values, and in their old *place*: a detail is a string, and a reader that looked
 * for `{"listened":1` still finds it.
 */
class TelemetryTest {
    private val gson = Gson()

    private fun parse(json: String): JsonObject = gson.fromJson(json, JsonObject::class.java)

    private fun assertNumber(o: JsonObject, key: String) {
        assertTrue("$key is missing: $o", o.has(key))
        assertTrue("$key is not a plain number: ${o[key]}", o[key].isJsonPrimitive && o[key].asJsonPrimitive.isNumber)
    }

    private fun assertBool(o: JsonObject, key: String) {
        assertTrue("$key is missing: $o", o.has(key))
        assertTrue("$key is not a boolean: ${o[key]}", o[key].isJsonPrimitive && o[key].asJsonPrimitive.isBoolean)
    }

    // ---------------------------------------------------------------- Adapt itself

    @Test fun `a detail with nothing in it is the empty object the column defaults to`() {
        assertEquals("{}", Adapt.detail { })
    }

    @Test fun `a key with nothing to say is absent, never present and null`() {
        val o = parse(Adapt.detail { put("a", null as Int?); put("b", 1) })
        assertFalse("a null was written: $o", o.has("a"))
        assertEquals(1, o["b"].asInt)
    }

    @Test fun `keys are written in the order they were put, so an old prefix stays an old prefix`() {
        assertEquals("""{"first":1,"second":2}""", Adapt.detail { put("first", 1); put("second", 2) })
    }

    @Test fun `a measurement keeps three decimals and no more`() {
        assertEquals("""{"dp":12.346}""", Adapt.detail { put("dp", 12.345678f) })
    }

    @Test fun `a measurement that is not a number at all is not written`() {
        val o = parse(Adapt.detail { put("dp", Float.NaN); put("also", Float.POSITIVE_INFINITY) })
        assertEquals("{}", o.toString())
    }

    /**
     * The same rule for the values that are kept whole. Gson throws on a non-finite float, and that
     * exception would come out of the builder — before the write is wrapped — and take the attempt
     * with it. A structure with one bad number in it is dropped whole rather than in part: an array
     * of per-letter marks one letter short would be read as a word one letter short.
     */
    @Test fun `a kept value with a number that is not a number is not written either`() {
        assertEquals("{}", Adapt.detail { kept("dp", Float.NaN) })
        assertEquals("{}", Adapt.detail { kept("dp", Double.NEGATIVE_INFINITY) })
        assertEquals(
            "{}",
            Adapt.detail { kept("letters", listOf(mapOf("c" to "η", "coverage" to 1f, "precision" to Float.NaN))) },
        )
        // And a sound one is kept exactly as it was handed over.
        assertEquals(
            """{"letters":[{"c":"η","coverage":0.5,"precision":0.25}]}""",
            Adapt.detail { kept("letters", listOf(mapOf("c" to "η", "coverage" to 0.5f, "precision" to 0.25f))) },
        )
    }

    /**
     * And the guard sees inside an *object*, which is the shape it used to be blind to — the one
     * non-collection thing anything keeps today is the numbers module's exercise, a data class.
     *
     * The question is put to Gson rather than to a list of types this file happens to know about:
     * the value is serialised exactly as the row will serialise it, and every number in the tree is
     * checked. A field, a field of a field, an array, a list of objects — all of them, by
     * construction rather than by a walk somebody has to remember to extend.
     */
    @Test fun `a kept object hiding a number that is not a number is dropped whole`() {
        assertEquals("{}", Adapt.detail { kept("reading", Reading("η", Float.NaN, listOf(1f))) })
        assertEquals("{}", Adapt.detail { kept("reading", Reading("η", 1f, listOf(1f, Float.POSITIVE_INFINITY))) })
        // Two levels down, inside an object inside a list inside an object.
        assertEquals("{}", Adapt.detail { kept("page", mapOf("rows" to listOf(Reading("η", Double.NaN.toFloat(), emptyList())))) })
        // A primitive array is not an Iterable either, and it is covered too.
        assertEquals("{}", Adapt.detail { kept("parts", floatArrayOf(1f, Float.NaN)) })

        // And the sound one goes in whole, fields and all.
        assertEquals(
            """{"reading":{"name":"η","value":1.5,"parts":[0.25]}}""",
            Adapt.detail { kept("reading", Reading("η", 1.5f, listOf(0.25f))) },
        )
    }

    /** A shape like the numbers module's exercise: an object with numbers in its fields. */
    private data class Reading(val name: String, val value: Float, val parts: List<Float>)

    /**
     * The one nested object a new caller may write: what the turn judge decided about a turn. It goes
     * in whole and in order, under its own key, so that the caregiver's reader has one shape to read
     * for all three modules that write it.
     */
    @Test fun `the judge's verdict goes in as one object under one key`() {
        assertEquals(
            """{"judge":{"source":"JUDGE","accept":true,"ms":640,"expanded":"Πρέπει να πάρω τα φάρμακα."}}""",
            Adapt.detail {
                put(
                    "judge",
                    linkedMapOf<String, Any?>(
                        "source" to "JUDGE", "accept" to true, "ms" to 640L,
                        "expanded" to "Πρέπει να πάρω τα φάρμακα.",
                    ),
                )
            },
        )
    }

    /** The absence rule, one level down and one level up: no nulls inside, and no empty object at all. */
    @Test fun `an object with nothing left to say is absent rather than empty`() {
        assertEquals("{}", Adapt.detail { put("judge", emptyMap<String, Any?>()) })
        assertEquals("{}", Adapt.detail { put("judge", null as Map<String, Any?>?) })
        assertEquals("{}", Adapt.detail { put("judge", mapOf("expanded" to null)) })
        assertEquals(
            """{"judge":{"accept":false}}""",
            Adapt.detail { put("judge", linkedMapOf("accept" to false, "expanded" to null)) },
        )
    }

    /** And it goes through [Adapt.Detail.kept], so the finite guard covers it like everything else. */
    @Test fun `an object hiding a number that is not a number is dropped whole`() {
        assertEquals("{}", Adapt.detail { put("judge", mapOf("accept" to true, "score" to Float.NaN)) })
        assertEquals("{}", Adapt.detail { put("judge", mapOf("inside" to listOf(Double.POSITIVE_INFINITY))) })
    }

    @Test fun `text is cut to a length nobody can keep notes in`() {
        val long = "α".repeat(Adapt.MAX_TEXT * 2)
        assertEquals(Adapt.MAX_TEXT, parse(Adapt.detail { put("heard", long) })["heard"].asString.length)
    }

    @Test fun `Greek is written as Greek, not as escapes`() {
        assertEquals("""{"heard":"θέλω καφέ"}""", Adapt.detail { put("heard", "θέλω καφέ") })
    }

    @Test fun `the median of an even list is the middle of the two middles`() {
        assertEquals(3000L, Adapt.medianMs(listOf(1000L, 2000L, 4000L, 8000L)))
        assertEquals(2000L, Adapt.medianMs(listOf(4000L, 1000L, 2000L)))
        assertEquals(null, Adapt.medianMs(emptyList()))
        assertEquals(null, Adapt.medianDp(listOf(Float.NaN)))
    }

    // ---------------------------------------------------------------- Word coach

    @Test fun `the word coach writes its old keys unchanged and its new ones beside them`() {
        val json = wordCoachDetail(
            listened = 2, sttOn = true, heard = "νερό", matched = true, sttTries = 1, peak = 9000,
            ms = 12_000, hintMsFirst = 4_500, takeMs = 1_800,
        )
        assertTrue("the old prefix moved: $json", json.startsWith("""{"listened":2,"sttHeard":"νερό","sttMatched":true,"sttTries":1,"peak":9000"""))
        val o = parse(json)
        listOf("listened", "sttTries", "peak", "ms", "hintMsFirst", "takeMs", "sttWaitMs").forEach { assertNumber(o, it) }
        listOf("sttMatched", "sttOn").forEach { assertBool(o, it) }
        assertEquals("νερό", o["sttHeard"].asString)
    }

    @Test fun `with recognition off the word coach says nothing about a recogniser`() {
        val o = parse(wordCoachDetail(0, sttOn = false, heard = "νερό", matched = true, sttTries = 3, peak = null, ms = 900, hintMsFirst = null, takeMs = null))
        listOf("sttHeard", "sttMatched", "sttTries", "sttWaitMs", "peak", "hintMsFirst", "takeMs").forEach {
            assertFalse("$it belongs to a run with recognition on: $o", o.has(it))
        }
        assertFalse(o["sttOn"].asBoolean)
        assertEquals(900L, o["ms"].asLong)
    }

    // ---------------------------------------------------------------- Sing then say

    @Test fun `sing-then-say writes the stage, the melody and what each stage cost him`() {
        val json = singSayDetail(
            stage = 5, listened = 1, tempo = Tempo.SLOW, key = Key.LOW, sttOn = true, heard = "θέλω καφέ",
            matched = true, sttTries = 0, peak = 12_000, ms = 40_000,
            repsPerStage = listOf(1, 3, 3, 1, 0), msPerStage = listOf(4_000L, 12_000L, 18_000L, 5_000L, 1_000L),
            sung = true, takeMs = 2_400,
        )
        assertTrue("the old prefix moved: $json", json.startsWith("""{"stage":5,"listened":1,"tempo":"SLOW","key":"LOW""""))
        val o = parse(json)
        assertEquals(5, o["repsPerStage"].asJsonArray.size())
        assertEquals(3, o["repsPerStage"].asJsonArray[1].asInt)
        assertEquals(5, o["msPerStage"].asJsonArray.size())
        assertEquals(12_000L, o["msPerStage"].asJsonArray[1].asLong)
        assertBool(o, "sung")
        assertNumber(o, "ms")
        assertNumber(o, "takeMs")
    }

    // ---------------------------------------------------------------- Dialogues

    @Test fun `a dialogue turn still says which dialogue and which turn it was`() {
        val json = scriptsDetail(
            scriptId = "script-1", position = 3, listened = 0, sttOn = false, heard = null, matched = false,
            sttTries = 0, peak = 400, ms = 7_000, hintMsFirst = 2_000, takeMs = 1_100,
        )
        assertTrue("the old prefix moved: $json", json.startsWith("""{"scriptId":"script-1","position":3,"listened":0"""))
        val o = parse(json)
        assertEquals("script-1", o["scriptId"].asString)
        listOf("position", "listened", "peak", "ms", "hintMsFirst", "takeMs").forEach { assertNumber(o, it) }
        assertFalse("a run with recognition off must not claim a wait: $o", o.has("sttWaitMs"))
    }

    // ---------------------------------------------------------------- Numbers

    @Test fun `a number exercise says what he was asked, what he tapped and how far off it was`() {
        val e = NumberExercise.Compare(level = 2, a = 7, b = 4, showDots = true)
        val json = numbersDetail(e, given = 4, retries = 1, ms = 5_500)
        assertTrue("the old prefix moved: $json", json.startsWith("""{"type":"compare","exercise":{"""))
        val o = parse(json)
        assertEquals("compare", o["type"].asString)
        assertEquals(7, o["answer"].asInt)
        assertEquals(4, o["given"].asInt)
        listOf("level", "optionCount", "retries", "ms").forEach { assertNumber(o, it) }
        assertEquals(2, o["optionCount"].asInt)
        assertTrue("the exercise itself is still whole: $o", o["exercise"].isJsonObject)
    }

    /**
     * A level-15 row keeps the story, once. The exercise carries the same sentence twice — digits for
     * the screen, words for the voice — and a year of rows does not need the second copy of a hundred
     * characters it can derive from the first.
     */
    @Test fun `a word problem writes the story it showed him and not the one it said`() {
        val shown = "Έχεις 3 κουτιά με 6 αυγά το καθένα. Σπάνε 4. Πόσα μένουν;"
        val said = "Έχεις τρία κουτιά με έξι αυγά το καθένα. Σπάνε τέσσερα. Πόσα μένουν;"
        val json = numbersDetail(
            NumberExercise.WordProblem(15, shown, said, listOf(14, 18, 22, 2), 14),
            given = 18, retries = 0, ms = 4_000,
        )
        assertTrue("the question he was asked has to survive: $json", json.contains(shown))
        assertFalse("the spoken copy is derivable and does not belong in the row: $json", json.contains(said))
        val o = parse(json)
        assertEquals("problem", o["type"].asString)
        assertEquals(14, o["answer"].asInt)
        assertEquals(15, o["level"].asInt)
    }

    @Test fun `an exercise he passed on carries no answer of his`() {
        val o = parse(numbersDetail(NumberExercise.Compare(1, 3, 9, false), given = null, retries = 0, ms = 900))
        assertFalse("a skip must not invent a tap: $o", o.has("given"))
    }

    // ---------------------------------------------------------------- Sentences

    @Test fun `a sentence keeps the order he built it in, and how much he took back`() {
        val json = sentencesDetail(
            tiles = listOf("θέλω", "καφέ"), chosen = listOf("καφέ", "θέλω"), firstTry = false, listened = 1,
            level = 2, retries = 1, undo = 2, ms = 15_000,
        )
        assertTrue("the old prefix moved: $json", json.startsWith("""{"tiles":["θέλω","καφέ"],"chosen":["καφέ","θέλω"],"firstTry":false,"listened":1"""))
        val o = parse(json)
        assertEquals(2, o["tiles"].asJsonArray.size())
        assertBool(o, "firstTry")
        listOf("listened", "level", "retries", "undo", "ms").forEach { assertNumber(o, it) }
    }

    @Test fun `a sentence he passed on still writes the empty board he left`() {
        val o = parse(sentencesDetail(listOf("θέλω"), emptyList(), firstTry = false, listened = 0, level = 1, retries = 0, undo = 0, ms = 100))
        assertEquals(0, o["chosen"].asJsonArray.size())
    }

    // ---------------------------------------------------------------- Trace

    @Test fun `a traced letter says how big it was, how many strokes and how long it took`() {
        val s = TraceState(
            level = 3, hand = "RIGHT", text = "Δ", templateHeight = 240f, strictness = TraceStrictness.STRICT,
            strokes = listOf(listOf(Pt(0f, 0f)), listOf(Pt(1f, 1f)), listOf(Pt(2f, 2f))), judged = 1, tries = 2,
        )
        val score = TraceScore(
            coverage = 0.9f, precision = 0.8f, meanDistance = 6f, passed = true,
            letters = listOf(LetterScore("Δ", 0.9f, 0.8f, true, ink = 1.4f)), inkRatio = 1.2f,
        )
        val json = traceDetail(s, score, ms = 9_000)
        assertTrue("the old prefix moved: $json", json.startsWith("""{"text":"Δ","level":3,"tries":2,"hand":"RIGHT""""))
        val o = parse(json)
        assertEquals("STRICT", o["strictness"].asString)
        listOf("coverage", "precision", "meanDistance", "inkRatio", "ms", "strokes", "templateHeightPx").forEach { assertNumber(o, it) }
        // Only the try that was marked, not the ink still on the paper from the one before it.
        assertEquals(2, o["strokes"].asInt)
        assertEquals(240f, o["templateHeightPx"].asFloat, 0.001f)
        assertTrue("the per-letter marks are still whole: $o", o["letters"].isJsonArray)
        // Every letter carries the ink it took, which is what the budget is applied to and the only
        // way it can ever be set from his own hand: see docs/ADAPTATION.md.
        val letter = o["letters"].asJsonArray.single().asJsonObject
        assertEquals("Δ", letter["c"].asString)
        assertEquals(1.4f, letter["ink"].asFloat, 0.001f)
    }

    @Test fun `a letter he passed on has no marks at all, only the ones about the paper`() {
        val skipped = TraceState(
            text = "Α", templateHeight = 200f,
            // Ink he had drawn and never handed in: it belongs to no try that was ever marked.
            strokes = listOf(listOf(Pt(0f, 0f)), listOf(Pt(1f, 1f))),
        )
        val o = parse(traceDetail(skipped, score = null, ms = 400))
        listOf("coverage", "precision", "meanDistance", "letters", "inkRatio", "strokes").forEach {
            assertFalse("$it cannot exist without a score: $o", o.has(it))
        }
        assertNumber(o, "ms")
        assertNumber(o, "templateHeightPx")
    }

    // ---------------------------------------------------------------- Arcade

    @Test fun `a game says the middle of its times and of how far his misses landed`() {
        val play = ArcadePlay(
            msPerTarget = listOf(1_000L, 2_000L, 9_000L),
            missDistanceDp = listOf(4f, 6f, 20f),
        )
        val json = arcadeDetail(hits = 12, misses = 3, sizeDp = 88.5f, play = play, ms = 60_000)
        assertTrue("the old prefix moved: $json", json.startsWith("""{"hits":12,"misses":3,"sizeDp":88.5"""))
        val o = parse(json)
        assertEquals(2_000L, o["msPerTarget"].asLong)
        assertEquals(6f, o["missDistanceDp"].asFloat, 0.001f)
        assertNumber(o, "ms")
    }

    @Test fun `a game with nothing to measure writes no medians rather than zeroes`() {
        val o = parse(arcadeDetail(0, 0, 96f, ArcadePlay(), ms = 200))
        assertFalse("a zero here would read as an instant hit: $o", o.has("msPerTarget"))
        assertFalse("a zero here would read as a perfect aim: $o", o.has("missDistanceDp"))
        assertEquals(0, o["hits"].asInt)
    }

    // ---------------------------------------------------------------- Talk board

    @Test fun `a word tapped into the strip says how long the sentence had grown`() {
        val o = parse(talkBoardDetail(inStrip = true, stripLen = 3))
        assertTrue(o["strip"].asBoolean)
        assertEquals(3, o["stripLen"].asInt)
    }

    @Test fun `a word said off the strip writes what it always wrote, which is nothing`() {
        assertEquals("{}", talkBoardDetail(inStrip = false, stripLen = 5))
    }

    /**
     * An expansion says what it was made of, what came back, where that came from, and what he then
     * made of saying it. The recognition keys carry the word coach's names so that one reader does
     * both.
     */
    @Test fun `an expansion says the words, the sentence and who wrote it`() {
        val o = parse(
            expandDetail(
                words = "φάρμακα πρέπει πάρω",
                expanded = "Πρέπει να πάρω τα φάρμακα.",
                stripLen = 3,
                sttOn = true,
                heard = "πρέπει να πάρω τα φάρμακα",
                matched = true,
                sttTries = 0,
                ms = 9_000,
                judge = linkedMapOf<String, Any?>("source" to "JUDGE", "accept" to true, "ms" to 640L),
            )
        )
        assertEquals("expand", o["kind"].asString)
        assertEquals("φάρμακα πρέπει πάρω", o["words"].asString)
        assertEquals("Πρέπει να πάρω τα φάρμακα.", o["expanded"].asString)
        assertEquals("JUDGE", o["judge"].asJsonObject["source"].asString)
        assertEquals(640L, o["judge"].asJsonObject["ms"].asLong)
        assertEquals(3, o["stripLen"].asInt)
        assertEquals("πρέπει να πάρω τα φάρμακα", o["sttHeard"].asString)
        assertBool(o, "sttMatched")
        listOf("sttTries", "ms").forEach { assertNumber(o, it) }
    }

    @Test fun `an expansion with no recogniser behind it says nothing about one`() {
        val o = parse(
            expandDetail(
                words = "καφές θέλω", expanded = "Θέλω έναν καφέ.", stripLen = 2, sttOn = false,
                heard = null, matched = false, sttTries = 0, ms = 4_000, judge = emptyMap(),
            )
        )
        assertFalse("nothing heard, nothing to say about hearing: $o", o.has("sttHeard"))
        assertFalse(o.has("sttTries"))
        // A verdict that was never recorded is absent, not an empty object nobody can read.
        assertFalse("an empty object says less than no key at all: $o", o.has("judge"))
        assertEquals("Θέλω έναν καφέ.", o["expanded"].asString)
    }

    // ---------------------------------------------------------------- The sitting

    @Test fun `the session summary says what was planned, what was done and whether he stayed`() {
        val json = sessionSummaryDetail(
            plannedModules = listOf("WORDCOACH", "TRACE"),
            plannedCount = 12, completed = 5, leftEarly = true, ms = 300_000,
            msPerModule = linkedMapOf("TRACE" to 40_000L, "WORDCOACH" to 120_000L),
        )
        val o = parse(json)
        assertEquals(listOf("WORDCOACH", "TRACE"), o["plannedModules"].asJsonArray.map { it.asString })
        listOf("plannedCount", "completed", "ms").forEach { assertNumber(o, it) }
        assertBool(o, "leftEarly")
        assertEquals(120_000L, o["msPerModule"].asJsonObject["WORDCOACH"].asLong)
    }

    @Test fun `a sitting that wrote no rows still says it planned some`() {
        val o = parse(sessionSummaryDetail(listOf("NUMBERS"), 3, 0, leftEarly = true, ms = 1_000, msPerModule = emptyMap()))
        assertEquals(0, o["completed"].asInt)
        assertFalse("an empty object says less than no key at all: $o", o.has("msPerModule"))
    }
}
