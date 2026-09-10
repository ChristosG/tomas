package gr.dimitris.app.core.judge

import com.google.gson.JsonParser
import gr.dimitris.app.core.data.Adapt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves that can be wrong while everything compiles: what goes up, and what is made of what
 * comes back. A language model is the other end of both, so the parser is tested against the shapes
 * models actually produce — a fence, a sentence of preamble, a stringified boolean — and not only
 * against the one the prompt asks for.
 */
class JudgeContractTest {

    private val dialogue = Ask(Kind.DIALOGUE, prompt = "Τι θα πάρεις;", heard = "καφέ", difficulty = 3)

    // --- what goes up ---------------------------------------------------------------------------

    @Test fun `the ask goes up as one JSON object with all six fields`() {
        val json = JudgeContract.userMessage(
            Ask(Kind.SENTENCE, prompt = "Τι κάνεις;", target = "Πίνω καφέ", heard = "πινω καφε", difficulty = 4)
        )
        val o = JsonParser.parseString(json).asJsonObject
        assertEquals("SENTENCE", o.get("kind").asString)
        assertEquals("Τι κάνεις;", o.get("prompt").asString)
        assertEquals("Πίνω καφέ", o.get("target").asString)
        assertEquals("πινω καφε", o.get("heard").asString)
        assertEquals(4, o.get("difficulty").asInt)
        assertTrue("intent key missing", o.has("intent"))
        // Written out as null rather than left off: a stable shape is one less thing for the model to
        // interpret. An ask *built* without an intent sends none — which is every board of
        // «Προτάσεις» (its own test pins that) — while «Γράψε»'s typed level does send one, and the
        // SENTENCE rule reads it where it is there. See [Ask.intent].
        assertTrue("an ask built without an intent invented one", o.get("intent").isJsonNull)
    }

    /**
     * What makes an open question open without making it a guessing game: the target is one right
     * answer, the intent is what they all have in common. It is written by a caregiver about the
     * *exercise* — «λέει τι θέλει και πόσο» — never about him, so §13's privacy rule is untouched.
     */
    @Test fun `a dialogue sends what a good answer has to convey`() {
        val o = JsonParser.parseString(
            JudgeContract.userMessage(
                Ask(
                    Kind.DIALOGUE, prompt = "Πού είσαι;", target = "Στο σπίτι είμαι.", heard = "σπίτι",
                    intent = "  λέει πού είναι  ",
                )
            )
        ).asJsonObject
        assertEquals("λέει πού είναι", o.get("intent").asString)
        // And the prompt has to describe the field, or the model is being sent a key it never met.
        assertTrue("the prompt does not describe intent", JudgeContract.SYSTEM_PROMPT.contains("intent"))
    }

    /** Blank is absent here too: a «Σκοπός» nobody filled in is nothing to say, not an empty demand. */
    @Test fun `a blank intent goes up as null`() {
        val o = JsonParser.parseString(
            JudgeContract.userMessage(Ask(Kind.DIALOGUE, target = "Ναι", heard = "ναι", intent = "   "))
        ).asJsonObject
        assertTrue(o.get("intent").isJsonNull)
    }

    /** A stable shape: an absent prompt or target is JSON null, never a missing key. */
    @Test fun `a missing prompt and target go up as null, not as absent keys`() {
        val o = JsonParser.parseString(JudgeContract.userMessage(Ask(Kind.WORD, heard = "καφές"))).asJsonObject
        assertTrue("prompt key missing", o.has("prompt"))
        assertTrue("target key missing", o.has("target"))
        assertTrue(o.get("prompt").isJsonNull)
        assertTrue(o.get("target").isJsonNull)
        assertEquals(1, o.get("difficulty").asInt)
    }

    /** Blank is the same as absent: the recogniser and the database both produce "" for "nothing". */
    @Test fun `a blank target goes up as null`() {
        val o = JsonParser.parseString(
            JudgeContract.userMessage(Ask(Kind.DIALOGUE, prompt = "  ", target = "", heard = " καφέ "))
        ).asJsonObject
        assertTrue(o.get("prompt").isJsonNull)
        assertTrue(o.get("target").isJsonNull)
        assertEquals("καφέ", o.get("heard").asString)
    }

    // --- the prompt -----------------------------------------------------------------------------

    /** All four judgements must be described, or one of them is being judged by the other three. */
    @Test fun `the system prompt names every kind and the JSON contract`() {
        Kind.entries.forEach { assertTrue("${it.name} not in the prompt", JudgeContract.SYSTEM_PROMPT.contains(it.name)) }
        listOf("accept", "expanded", "feedback", "score").forEach {
            assertTrue("$it not in the prompt", JudgeContract.SYSTEM_PROMPT.contains("\"$it\""))
        }
    }

    /**
     * Spec §13: "Nothing about his health beyond what §1 states enters prompts" — and the ruling for
     * this task narrowed even that to three sentences. This is the test that keeps the prompt from
     * quietly growing a medical history the next time somebody tunes it.
     */
    @Test fun `the system prompt says nothing about his health beyond the three sentences`() {
        // «απραξία» is deliberately **not** on this list. The prompt's «η άρθρωση του κοστίζει» is §1's
        // "Likely apraxia of speech: what he says is hard to articulate", restated — inside §1 and so
        // permitted, and judging his articulation is the whole point of a WORD turn. What is checked
        // here is everything that is *not* needed to judge one Greek sentence.
        listOf("ακαλκουλ", "ημιπάρεση", "ημιπαρεσ", "καρωτ", "μνήμη", "τραγούδ", "φυσιοθερα", "χιούμορ")
            .forEach { word ->
                assertFalse("the prompt mentions «$word»", JudgeContract.SYSTEM_PROMPT.contains(word))
            }
    }

    /**
     * A refused turn has to come back with something for him to say.
     *
     * The dialogue screen shows the `expanded` and says it once, and that sentence is the whole of
     * what his next «Μίλα» has to go on. Before this clause the prompt asked for an expansion only
     * on an *accepted* telegraphic answer, so the refusal branch — the one that needs it most — was
     * the branch the model was never asked to fill: he answered «καλημέρα» to «πού είσαι;», got a
     * warm line and «Δοκίμασε ξανά», and his second go had no more to go on than his first.
     */
    @Test fun `the prompt asks for a whole sentence on a refused dialogue too`() {
        val dialogueParagraph = JudgeContract.SYSTEM_PROMPT
            .substringAfter("DIALOGUE:").substringBefore("WORD:")
        assertTrue("a refused dialogue is not asked for an expansion", dialogueParagraph.contains("accept false"))
        assertTrue(
            "the refusal clause does not ask for expanded",
            dialogueParagraph.substringAfter("Βάλε accept false").contains("expanded"),
        )
    }

    /** Never «λάθος», never «όχι» — the rule the prompt has to carry, stated in the prompt. */
    @Test fun `the system prompt forbids the two words he must never hear`() {
        assertTrue(JudgeContract.SYSTEM_PROMPT.contains("«λάθος»"))
        assertTrue(JudgeContract.SYSTEM_PROMPT.contains("«όχι»"))
    }

    // --- what comes back ------------------------------------------------------------------------

    @Test fun `a clean reply is read field for field`() {
        val v = JudgeContract.parse(
            """{"accept":true,"expanded":"Θέλω έναν καφέ.","feedback":"Ωραία, το είπες!","score":0.8}""",
            dialogue,
        )
        assertNotNull(v)
        assertTrue(v!!.accept)
        assertEquals("Θέλω έναν καφέ.", v.expanded)
        assertEquals("Ωραία, το είπες!", v.feedback)
        assertEquals(0.8f, v.score, 0.001f)
        assertEquals(Source.JUDGE, v.source)
    }

    @Test fun `a fenced reply is read`() {
        val v = JudgeContract.parse(
            "```json\n{\"accept\":true,\"expanded\":null,\"feedback\":null,\"score\":1}\n```",
            dialogue,
        )
        assertNotNull(v)
        assertTrue(v!!.accept)
        assertNull(v.expanded)
        assertNull(v.feedback)
        assertEquals(1f, v.score, 0.001f)
    }

    @Test fun `a reply wrapped in prose is read`() {
        val v = JudgeContract.parse(
            "Ορίστε η κρίση μου:\n{\"accept\":false,\"expanded\":\"Πίνω καφέ.\",\"score\":0.2}\nΕλπίζω να βοηθά.",
            Ask(Kind.SENTENCE, target = "Πίνω καφέ", heard = "καφε"),
        )
        assertNotNull(v)
        assertFalse(v!!.accept)
        assertEquals("Πίνω καφέ.", v.expanded)
        assertEquals(0.2f, v.score, 0.001f)
    }

    /** Everything but `accept` has a default, because everything but `accept` is decoration. */
    @Test fun `missing expanded, feedback and score fall back to defaults`() {
        val yes = JudgeContract.parse("""{"accept":true}""", dialogue)
        assertNotNull(yes)
        assertNull(yes!!.expanded)
        assertNull(yes.feedback)
        assertEquals(1f, yes.score, 0.001f)

        val no = JudgeContract.parse("""{"accept":false}""", dialogue)
        assertEquals(0f, no!!.score, 0.001f)
    }

    @Test fun `a stringified boolean and a stringified score are read`() {
        val v = JudgeContract.parse("""{"accept":"true","expanded":"null","score":"0.55"}""", dialogue)
        assertNotNull(v)
        assertTrue(v!!.accept)
        // The literal word "null" in a string field is nothing, not an expansion to read to him.
        assertNull(v.expanded)
        assertEquals(0.55f, v.score, 0.001f)
    }

    @Test fun `a score outside nought to one is clamped`() {
        assertEquals(1f, JudgeContract.parse("""{"accept":true,"score":9}""", dialogue)!!.score, 0.001f)
        assertEquals(0f, JudgeContract.parse("""{"accept":false,"score":-3}""", dialogue)!!.score, 0.001f)
    }

    @Test fun `extra keys are ignored`() {
        val v = JudgeContract.parse("""{"accept":true,"score":0.5,"reason":"ό,τι θέλει","tokens":12}""", dialogue)
        assertNotNull(v)
        assertEquals(0.5f, v!!.score, 0.001f)
    }

    @Test fun `a blank expanded or feedback is nothing`() {
        val v = JudgeContract.parse("""{"accept":true,"expanded":"   ","feedback":""}""", dialogue)
        assertNull(v!!.expanded)
        assertNull(v.feedback)
    }

    // --- and what is not a verdict at all -------------------------------------------------------

    @Test fun `prose with no object at all is not a verdict`() {
        assertNull(JudgeContract.parse("Ναι, το είπε σωστά.", dialogue))
        assertNull(JudgeContract.parse("", dialogue))
    }

    @Test fun `a bare number and a truncated object are not verdicts`() {
        assertNull(JudgeContract.parse("0.9", dialogue))
        // A reply cut off by max_tokens mid-string: the object is never closed.
        assertNull(JudgeContract.parse("""{"accept":true,"feedback":"κόπ""", dialogue))
        assertNull(JudgeContract.parse("""{"accept":true""", dialogue))
    }

    /**
     * The first object and only the first, found by counting depth rather than by running to the last
     * `}` in the reply. First-brace-to-last-brace threw all three of these away — each was a verdict
     * the model really did give, turned into a local fallback and a row in the caregiver's log.
     */
    @Test fun `the first whole object is found whatever follows it`() {
        // A second object after the first.
        assertTrue(JudgeContract.parse("""{"accept":true,"score":0.7}{"accept":false}""", dialogue)!!.accept)
        // A closing brace in trailing prose.
        assertTrue(JudgeContract.parse("""{"accept":true} — έτσι το έκρινα }""", dialogue)!!.accept)
        // One object wrapped in a list, and a list of two.
        assertTrue(JudgeContract.parse("""[{"accept":true,"score":0.7}]""", dialogue)!!.accept)
        assertTrue(JudgeContract.parse("""[{"accept":true},{"accept":false}]""", dialogue)!!.accept)
    }

    /** A brace inside a Greek string is text, not structure. */
    @Test fun `a brace inside a string does not end the object`() {
        val v = JudgeContract.parse("""{"accept":true,"feedback":"Ωραία } μπράβο","score":1}""", dialogue)
        assertEquals("Ωραία } μπράβο", v!!.feedback)
    }

    /** A nested object, should the model ever volunteer one, is walked through rather than cut. */
    @Test fun `a nested object does not end the outer one`() {
        val v = JudgeContract.parse("""{"accept":true,"meta":{"a":{"b":1}},"score":0.4}""", dialogue)
        assertEquals(0.4f, v!!.score, 0.001f)
    }

    /**
     * The one strict rule. A reply that did not say whether it accepted the turn is not a verdict,
     * and guessing either way would either wall him or confirm a word he never said.
     */
    @Test fun `an object without accept is not a verdict`() {
        assertNull(JudgeContract.parse("""{"expanded":"Θέλω καφέ.","score":0.9}""", dialogue))
        assertNull(JudgeContract.parse("""{"accept":"μάλλον"}""", dialogue))
        assertNull(JudgeContract.parse("""{"accept":null}""", dialogue))
    }

    /**
     * Reading the reply and deciding whether it is *usable* are two jobs. An EXPAND with no sentence
     * in it parses perfectly well — it simply carries no expansion — and `TurnJudge` is what turns
     * that into the local fallback, so the log can say what actually happened. See
     * `TurnJudgeTest.an expansion that came back empty falls back to his own words`.
     */
    @Test fun `an EXPAND with no sentence parses, and leaves the usability question to the judge`() {
        val expand = Ask(Kind.EXPAND, heard = "φάρμακα πρέπει πάρω")
        val v = JudgeContract.parse("""{"accept":true,"expanded":null,"score":1}""", expand)
        assertNotNull(v)
        assertNull(v!!.expanded)
        assertEquals(
            "Πρέπει να πάρω τα φάρμακα.",
            JudgeContract.parse("""{"accept":true,"expanded":"Πρέπει να πάρω τα φάρμακα.","score":1}""", expand)!!.expanded,
        )
    }

    // --- his own words are not an expansion of his own words ------------------------------------

    /**
     * A model that echoes him back hands the callers an "expansion" identical to what he said, and
     * the screen then offers «here is the full form, say it again» over the sentence he has just
     * finished saying.
     */
    @Test fun `an expansion that is only his own words echoed back is nothing`() {
        val ask = Ask(Kind.DIALOGUE, prompt = "Τι θα πάρεις;", heard = "θέλω καφέ")
        assertNull(JudgeContract.parse("""{"accept":true,"expanded":"θέλω καφέ"}""", ask)!!.expanded)
        // Compared as leniently as the gentle check compares: punctuation, case and accents are not
        // an expansion either.
        assertNull(JudgeContract.parse("""{"accept":true,"expanded":"Θέλω καφέ!"}""", ask)!!.expanded)
        // A real expansion survives.
        assertEquals(
            "Θέλω έναν καφέ.",
            JudgeContract.parse("""{"accept":true,"expanded":"Θέλω έναν καφέ."}""", ask)!!.expanded,
        )
    }

    /**
     * And EXPAND is exempt, which is the whole difference between judging a turn and expanding one.
     * Nothing was judged here: the question was "what sentence do these words make?", and «θέλω» +
     * «καφέ» really does make «Θέλω καφέ.». The answer coming back nearly unchanged means the words
     * were already whole — a success, and the tidied form is what he should see and hear.
     */
    @Test fun `an EXPAND keeps a sentence that is nearly the words it was given`() {
        val expand = Ask(Kind.EXPAND, heard = "θέλω καφέ")
        assertEquals(
            "Θέλω καφέ.",
            JudgeContract.parse("""{"accept":true,"expanded":"Θέλω καφέ."}""", expand)!!.expanded,
        )
    }

    // --- the feedback gate ----------------------------------------------------------------------

    /**
     * The prompt forbids «λάθος» and «όχι»; a prompt is an instruction, not a guarantee, and a refused
     * SENTENCE is exactly the turn a model opens with «Όχι ακριβώς…». TTS would then say it to the one
     * man the whole app is built never to tell no.
     */
    @Test fun `feedback containing the two forbidden words is dropped`() {
        listOf(
            "Όχι ακριβώς, δοκίμασε πάλι.",
            "οχι, σχεδόν!",
            "ΌΧΙ ΑΚΡΙΒΩΣ",
            "Μικρό λάθος, πάμε ξανά.",
            "ΛΑΘΟΣ",
            "Λάθος!",
        ).forEach { line ->
            assertNull(
                "«$line» reached him",
                JudgeContract.parse("""{"accept":false,"feedback":${quote(line)}}""", dialogue)!!.feedback,
            )
        }
    }

    /** Word by word, so a word that merely contains the letters is not the word. */
    @Test fun `a warm line that only looks like a forbidden word survives`() {
        listOf("Μπράβο, πολύ καλά!", "Σε άκουσα καθαρά.", "Η κόχη του ήχου ήταν σωστή.").forEach { line ->
            assertEquals(
                line,
                JudgeContract.parse("""{"accept":true,"feedback":${quote(line)}}""", dialogue)!!.feedback,
            )
        }
    }

    /** Twelve Greek words is encouragement; thirteen is a paragraph read at a man with aphasia. */
    @Test fun `feedback longer than twelve words is dropped`() {
        val twelve = (1..JudgeContract.MAX_FEEDBACK_WORDS).joinToString(" ") { "καλά" }
        assertEquals(
            twelve,
            JudgeContract.parse("""{"accept":true,"feedback":${quote(twelve)}}""", dialogue)!!.feedback,
        )

        val thirteen = "$twelve ακόμη"
        assertNull(JudgeContract.parse("""{"accept":true,"feedback":${quote(thirteen)}}""", dialogue)!!.feedback)
    }

    /** Dropping the line leaves a complete verdict: `feedback` was always optional. */
    @Test fun `dropping the feedback does not drop the verdict`() {
        val v = JudgeContract.parse("""{"accept":false,"expanded":"Πίνω καφέ.","feedback":"Λάθος.","score":0.3}""", dialogue)
        assertNotNull(v)
        assertFalse(v!!.accept)
        assertEquals("Πίνω καφέ.", v.expanded)
        assertNull(v.feedback)
        assertEquals(0.3f, v.score, 0.001f)
    }

    @Test fun `the gate is the same whichever way it is reached`() {
        assertNull(JudgeContract.warm("Όχι ακριβώς."))
        assertNull(JudgeContract.warm("   "))
        assertNull(JudgeContract.warm(null))
        assertEquals("Μπράβο!", JudgeContract.warm(" Μπράβο! "))
    }

    // --- the difficulty that goes up ------------------------------------------------------------

    /** The prompt promises the model 1–5; a caller outside that must not silently redefine it. */
    @Test fun `difficulty is clamped to the scale the prompt describes`() {
        fun sent(d: Int) = JsonParser
            .parseString(JudgeContract.userMessage(Ask(Kind.WORD, heard = "καφές", difficulty = d)))
            .asJsonObject.get("difficulty").asInt

        assertEquals(1, sent(0))
        assertEquals(1, sent(-7))
        assertEquals(5, sent(9))
        assertEquals(3, sent(3))
    }

    // --- the row every caller writes ------------------------------------------------------------

    /**
     * One shape, decided here, so Tasks 3, 6 and 7 cannot drift on key names or on whether `expanded`
     * is included — and so the caregiver's progress reader has one shape to support.
     */
    @Test fun `detail carries source, accept and ms, and expanded only when there is one`() {
        val judged = Verdict(accept = true, expanded = "Θέλω έναν καφέ.", feedback = "Μπράβο!", score = 0.9f, source = Source.JUDGE)
        assertEquals(
            mapOf("source" to "JUDGE", "accept" to true, "ms" to 642L, "expanded" to "Θέλω έναν καφέ."),
            judged.detail(642),
        )

        val local = Verdict(accept = false, score = 0f, source = Source.LOCAL)
        assertEquals(mapOf("source" to "LOCAL", "accept" to false, "ms" to 3L), local.detail(3))
    }

    /** A clock that went backwards is not a negative measurement. */
    @Test fun `a negative elapsed time is written as zero`() {
        assertEquals(0L, Verdict(accept = true, source = Source.LOCAL).detail(-5)["ms"])
    }

    /**
     * An attempt row leaves the phone twice — it syncs to the father's server and it goes to Claude
     * inside the journey report — so the one free-text field in it obeys the same cap as every other
     * detail string.
     */
    @Test fun `a very long expansion is capped like any other detail string`() {
        val long = "α".repeat(Adapt.MAX_TEXT + 50)
        val kept = Verdict(accept = true, expanded = long, source = Source.JUDGE).detail(1)["expanded"] as String
        assertEquals(Adapt.MAX_TEXT, kept.length)
    }

    /** JSON string literal for a Greek line with no escaping of its own. */
    private fun quote(s: String) = "\"$s\""
}
