package gr.dimitris.app.caregiver.insights

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.modules.numbers.NumberProgression
import gr.dimitris.app.modules.sentences.SentenceTemplates
import gr.dimitris.app.modules.sql.SqlPuzzles
import gr.dimitris.app.modules.trace.TraceViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import gr.dimitris.app.core.data.Advice as AdviceRow

/**
 * The other end of this parser is a language model, so every test here is about being forgiving
 * without being credulous: read whatever shape the answer arrives in, and then act only on the
 * parts that are true of this phone — words that exist, levels the modules have.
 */
class FocusTest {
    private val known = listOf("καφές", "ψωμί", "νερό", "καλημέρα")

    private fun advice(json: String, at: Long = 1_000) =
        AdviceRow(at = at, model = "m", report = "", caregivers = "", dimitris = "", focusJson = json)

    private fun word(text: String, sound: String = "") = Item(text = text, firstSound = sound)

    @Test fun `a whole focus is read`() {
        val focus = Focus.parse(
            """{"items":["καφές","ψωμί"],"sounds":["π"],"modules":["WORDCOACH"],"levels":{"numbers":3},"why":"τα ψώνια"}""",
            known, at = 5,
        )

        assertEquals(listOf("καφές", "ψωμί"), focus.items)
        assertEquals(listOf("π"), focus.sounds)
        assertEquals(listOf(ModuleId.WORDCOACH), focus.modules)
        assertEquals(mapOf(Focus.NUMBERS to 3), focus.levels)
        assertEquals("τα ψώνια", focus.why)
        assertEquals(5L, focus.at)
    }

    @Test fun `every key is optional and a missing one is empty`() {
        val focus = Focus.parse("""{"items":["καφές"]}""", known)

        assertEquals(listOf("καφές"), focus.items)
        assertTrue(focus.sounds.isEmpty())
        assertTrue(focus.modules.isEmpty())
        assertTrue(focus.levels.isEmpty())
        assertEquals("", focus.why)
        assertFalse(focus.isEmpty)
    }

    @Test fun `an empty object, prose, or nothing at all is an empty focus`() {
        listOf("{}", "", "   ", "Δεν έχω εστίαση αυτή τη φορά.", "[1,2,3]", "{όχι JSON").forEach { raw ->
            val focus = Focus.parse(raw, known)
            assertTrue(raw, focus.isEmpty)
        }
    }

    /** A word the model invented cannot be practised, so it is dropped rather than planned. */
    @Test fun `words that are not in the vocabulary are dropped`() {
        val focus = Focus.parse("""{"items":["καφές","αυτοκίνητο","ψωμί"]}""", known)
        assertEquals(listOf("καφές", "ψωμί"), focus.items)
    }

    /** Matched unaccented and case-insensitively, and given back as the vocabulary spells it. */
    @Test fun `a word written differently is still the same word`() {
        val focus = Focus.parse("""{"items":["ΚΑΦΕΣ","Καλημερα"]}""", known)
        assertEquals(listOf("καφές", "καλημέρα"), focus.items)
    }

    /** Null means "do not filter": the session builder matches against its own pool anyway. */
    @Test fun `without a vocabulary the words are kept as written`() {
        val focus = Focus.parse("""{"items":["καφές","αυτοκίνητο"]}""", known = null)
        assertEquals(listOf("καφές", "αυτοκίνητο"), focus.items)
    }

    @Test fun `levels are clamped into each module's own range`() {
        val focus = Focus.parse("""{"levels":{"numbers":99,"sentences":0,"trace":-4,"φαντασία":3}}""", known)

        assertEquals(NumberProgression.MAX_LEVEL, focus.levels[Focus.NUMBERS])
        assertEquals(SentenceTemplates.MIN_LEVEL, focus.levels[Focus.SENTENCES])
        assertEquals(TraceViewModel.MIN_LEVEL, focus.levels[Focus.TRACE])
        assertEquals("only the keys the app owns", 3, focus.levels.size)
    }

    /**
     * Phase 13's two tiles have dots like everything else, and the prompt tells the model that «levels»
     * moves a dot — so it has to be able to name them. Without these keys the advisor could say «SQL:
     * πήγαινε στο 4» in its prose and the app would act on nothing, which is the gap the whole-phase
     * review found.
     *
     * In both of them the number *is* the dot, so the range is 1..5 and anything outside it is clamped
     * rather than refused: «steps: 9» is a model saying "move him up", and the app knows its own top.
     */
    @Test fun `the two tiles of phase 13 have level keys of their own`() {
        val focus = Focus.parse("""{"levels":{"sql":4,"steps":9}}""", known)

        assertEquals(4, focus.levels[Focus.SQL])
        assertEquals(Difficulty.MAX, focus.levels[Focus.STEPS])
        assertEquals(2, focus.levels.size)

        val low = Focus.parse("""{"levels":{"sql":0,"steps":0}}""", known)
        assertEquals(SqlPuzzles.MIN_LEVEL, low.levels[Focus.SQL])
        assertEquals(Difficulty.MIN, low.levels[Focus.STEPS])
    }

    @Test fun `a level that is not a number is ignored rather than fatal`() {
        val focus = Focus.parse("""{"levels":{"numbers":"τρία","trace":2}}""", known)
        assertEquals(mapOf(Focus.TRACE to 2), focus.levels)
    }

    @Test fun `an unknown module name is dropped and the rest are kept`() {
        val focus = Focus.parse("""{"modules":["WORDCOACH","MAGIC","trace"]}""", known)
        assertEquals(listOf(ModuleId.WORDCOACH, ModuleId.TRACE), focus.modules)
    }

    /** Models write a bare string where an array was asked for. Refusing it throws away advice. */
    @Test fun `a bare string where a list was asked for is a list of one`() {
        val focus = Focus.parse("""{"items":"καφές","sounds":"π"}""", known)
        assertEquals(listOf("καφές"), focus.items)
        assertEquals(listOf("π"), focus.sounds)
    }

    /** Prose around the object, or a fence, is not a reason to lose it. */
    @Test fun `the object is found inside whatever the model wrapped it in`() {
        val focus = Focus.parse("""Ορίστε: ```json {"items":["ψωμί"]} ``` ελπίζω να βοηθάει""", known)
        assertEquals(listOf("ψωμί"), focus.items)
    }

    @Test fun `a word is matched by its text or by its first sound`() {
        val focus = Focus.parse("""{"items":["καφές"],"sounds":["ψ"]}""", known)

        assertTrue(focus.matches(word("καφές", "κ")))
        assertTrue("a word she adds tomorrow starting with ψ is in the focus too", focus.matches(word("ψάρι", "ψ")))
        assertFalse(focus.matches(word("νερό", "ν")))
        assertFalse("a blank first sound never matches", focus.matches(word("νερό", "")))
    }

    // ---- the seven-day window --------------------------------------------------------------

    private val week = Focus.WINDOW_MS

    @Test fun `a focus from today is live and one from a fortnight ago is not`() {
        val now = 10 * week
        assertTrue(Focus(items = listOf("καφές"), at = now - 1000).isLive(now))
        assertTrue("exactly seven days still counts", Focus(items = listOf("καφές"), at = now - week).isLive(now))
        assertFalse(Focus(items = listOf("καφές"), at = now - week - 1).isLive(now))
        assertFalse("an advice that was never stamped is not live", Focus(items = listOf("καφές"), at = 0).isLive(now))
    }

    @Test fun `active takes the newest advice, and only while it is fresh`() {
        val now = 10 * week
        val old = advice("""{"items":["ψωμί"]}""", at = now - 2 * week)
        val fresh = advice("""{"items":["καφές"]}""", at = now - 1000)

        assertEquals(listOf("καφές"), Focus.active(listOf(old, fresh), known, now)?.items)
        assertNull("only a stale one is no focus at all", Focus.active(listOf(old), known, now))
        assertNull(Focus.active(emptyList(), known, now))
    }

    @Test fun `a deleted advice does not steer anything`() {
        val now = 10 * week
        val kept = advice("""{"items":["ψωμί"]}""", at = now - 2000)
        val removed = advice("""{"items":["καφές"]}""", at = now - 1000).copy(deleted = true)

        assertEquals(listOf("ψωμί"), Focus.active(listOf(kept, removed), known, now)?.items)
    }

    /** An advice whose focus said nothing the app can act on is not a focus. */
    @Test fun `an advice with an empty focus is no focus`() {
        val now = 10 * week
        assertNull(Focus.active(listOf(advice("", at = now)), known, now))
        assertNull(Focus.active(listOf(advice("{}", at = now)), known, now))
        assertNull(Focus.of(null))
    }

    /** Enough is enough: a focus is a handful of words, not the vocabulary. */
    @Test fun `the lists are capped`() {
        val many = (1..200).joinToString(",") { "\"λ$it\"" }
        val focus = Focus.parse("""{"items":[$many],"sounds":[$many]}""", known = null)
        assertEquals(Focus.MAX_ITEMS, focus.items.size)
        assertEquals(Focus.MAX_SOUNDS, focus.sounds.size)
    }
}
