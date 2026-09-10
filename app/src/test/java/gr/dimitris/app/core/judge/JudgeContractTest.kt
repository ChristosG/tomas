package gr.dimitris.app.core.judge

import com.google.gson.JsonParser
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

    @Test fun `the ask goes up as one JSON object with all five fields`() {
        val json = JudgeContract.userMessage(
            Ask(Kind.SENTENCE, prompt = "Τι κάνεις;", target = "Πίνω καφέ", heard = "πινω καφε", difficulty = 4)
        )
        val o = JsonParser.parseString(json).asJsonObject
        assertEquals("SENTENCE", o.get("kind").asString)
        assertEquals("Τι κάνεις;", o.get("prompt").asString)
        assertEquals("Πίνω καφέ", o.get("target").asString)
        assertEquals("πινω καφε", o.get("heard").asString)
        assertEquals(4, o.get("difficulty").asInt)
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
        listOf("ακαλκουλ", "ημιπάρεση", "ημιπαρεσ", "απραξ", "καρωτ", "δυσαρθρ", "μνήμη", "τραγούδ").forEach { word ->
            assertFalse("the prompt mentions «$word»", JudgeContract.SYSTEM_PROMPT.contains(word))
        }
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

    @Test fun `a bare number, a truncated object and a list of objects are not verdicts`() {
        assertNull(JudgeContract.parse("0.9", dialogue))
        // A reply cut off by max_tokens mid-string. Braces to braces finds no closing one.
        assertNull(JudgeContract.parse("""{"accept":true,"feedback":"κόπ""", dialogue))
        assertNull(JudgeContract.parse("""[{"accept":true},{"accept":false}]""", dialogue))
    }

    /**
     * A single object inside brackets *is* read: braces to braces finds it, and a model that wrapped
     * one verdict in a list still judged the turn. Written down because it is a consequence of the
     * extraction rather than a decision anybody made, and the next reader should not have to guess.
     */
    @Test fun `one object inside a list is still read`() {
        assertTrue(JudgeContract.parse("""[{"accept":true,"score":0.7}]""", dialogue)!!.accept)
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

    /** An EXPAND asks exactly one thing. A reply without it answered nothing. */
    @Test fun `an EXPAND with no sentence in it is not a verdict`() {
        val expand = Ask(Kind.EXPAND, heard = "φάρμακα πρέπει πάρω")
        assertNull(JudgeContract.parse("""{"accept":true,"expanded":null,"score":1}""", expand))
        assertNotNull(
            JudgeContract.parse("""{"accept":true,"expanded":"Πρέπει να πάρω τα φάρμακα.","score":1}""", expand)
        )
    }
}
