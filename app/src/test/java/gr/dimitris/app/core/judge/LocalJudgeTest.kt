package gr.dimitris.app.core.judge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The judge he gets on a phone in flight mode, which is the one the app promises will always be
 * there (spec §2 rule 9). Every test here is also a test that the offline app did not get stricter
 * when phase 12 added a cleverer one.
 */
class LocalJudgeTest {

    @Test fun `every local verdict says it is local`() {
        Kind.entries.forEach { kind ->
            val v = LocalJudge.judge(Ask(kind, target = "καφές", heard = "καφές"))
            assertEquals("$kind", Source.LOCAL, v.source)
        }
    }

    // --- one word -------------------------------------------------------------------------------

    @Test fun `a word that matches is accepted`() {
        val v = LocalJudge.judge(Ask(Kind.WORD, target = "καφές", heard = "καφές"))
        assertTrue(v.accept)
        assertEquals(1f, v.score, 0.001f)
        assertNull(v.expanded)
    }

    /** The same leniency the gentle check has always had: an accent elsewhere is the word. */
    @Test fun `a word said close enough is accepted`() {
        assertTrue(LocalJudge.judge(Ask(Kind.WORD, target = "καφές", heard = "καφες")).accept)
        assertTrue(LocalJudge.judge(Ask(Kind.WORD, target = "ψωμί", heard = "ψωμι.")).accept)
    }

    @Test fun `a different word is not accepted`() {
        val v = LocalJudge.judge(Ask(Kind.WORD, target = "καφές", heard = "αυτοκίνητο"))
        assertFalse(v.accept)
        assertEquals(0f, v.score, 0.001f)
    }

    /** Nothing heard is not a judgement against him either — but it is not an accepted word. */
    @Test fun `nothing heard is not accepted`() {
        assertFalse(LocalJudge.judge(Ask(Kind.WORD, target = "καφές", heard = "  ")).accept)
    }

    /**
     * No target, no opinion. A WORD whose target arrived blank is the phone having nothing to compare
     * with, and a phone with nothing to compare with may not disagree with him.
     */
    @Test fun `a word with no target is accepted on his word`() {
        assertTrue(LocalJudge.judge(Ask(Kind.WORD, target = null, heard = "καφές")).accept)
        assertTrue(LocalJudge.judge(Ask(Kind.WORD, target = "", heard = "καφές")).accept)
    }

    // --- a whole sentence -----------------------------------------------------------------------

    /** «Θέλω έναν καφέ» said as «θέλω καφέ» is the man doing the exercise, not failing it. */
    @Test fun `a sentence with most of the target in it is accepted`() {
        assertTrue(LocalJudge.judge(Ask(Kind.SENTENCE, target = "Θέλω έναν καφέ", heard = "θέλω καφέ")).accept)
    }

    @Test fun `a sentence about something else is not accepted`() {
        assertFalse(LocalJudge.judge(Ask(Kind.SENTENCE, target = "Θέλω έναν καφέ", heard = "πάμε σπίτι τώρα")).accept)
    }

    // --- an open answer -------------------------------------------------------------------------

    /**
     * The wall §13 removed. The phone cannot tell a sensible answer from a silly one, so an answer he
     * gave counts — refusing it because a string did not match is the one thing this must never do.
     */
    @Test fun `any answer he actually gave is accepted in a dialogue`() {
        val v = LocalJudge.judge(
            Ask(Kind.DIALOGUE, prompt = "Τι θα πάρεις;", target = "Θέλω έναν καφέ", heard = "τσάι")
        )
        assertTrue(v.accept)
    }

    /** The uncertainty is recorded in the score, not paid for by him. */
    @Test fun `a dialogue answer nobody can judge scores half, one on the example line`() {
        val unknown = LocalJudge.judge(
            Ask(Kind.DIALOGUE, prompt = "Τι θα πάρεις;", target = "Θέλω έναν καφέ", heard = "τσάι")
        )
        assertEquals(LocalJudge.UNJUDGED, unknown.score, 0.001f)

        val onTarget = LocalJudge.judge(
            Ask(Kind.DIALOGUE, prompt = "Τι θα πάρεις;", target = "Θέλω έναν καφέ", heard = "θέλω καφέ")
        )
        assertEquals(1f, onTarget.score, 0.001f)

        val noTarget = LocalJudge.judge(Ask(Kind.DIALOGUE, prompt = "Τι θα πάρεις;", heard = "καφέ"))
        assertEquals(LocalJudge.UNJUDGED, noTarget.score, 0.001f)
    }

    @Test fun `an empty dialogue answer is not accepted`() {
        val v = LocalJudge.judge(Ask(Kind.DIALOGUE, prompt = "Τι θα πάρεις;", heard = ""))
        assertFalse(v.accept)
        assertEquals(0f, v.score, 0.001f)
    }

    // --- the expansion --------------------------------------------------------------------------

    /**
     * The local judge never builds a sentence. Guessing at Greek grammar would teach him wrong forms,
     * so his own words come back — which is what the talk board showed before any of this existed.
     */
    @Test fun `an expansion locally is his own words, unchanged`() {
        val v = LocalJudge.judge(Ask(Kind.EXPAND, heard = " φάρμακα πρέπει πάρω "))
        assertTrue(v.accept)
        assertEquals("φάρμακα πρέπει πάρω", v.expanded)
    }

    @Test fun `an expansion of nothing is nothing, and still not a refusal`() {
        val v = LocalJudge.judge(Ask(Kind.EXPAND, heard = ""))
        assertTrue(v.accept)
        assertNull(v.expanded)
        assertEquals(0f, v.score, 0.001f)
    }

    /** It says nothing at all: the three screens already own their Greek. */
    @Test fun `the local judge never speaks`() {
        Kind.entries.forEach { kind ->
            assertNull("$kind", LocalJudge.judge(Ask(kind, target = "καφές", heard = "νερό")).feedback)
        }
    }
}
