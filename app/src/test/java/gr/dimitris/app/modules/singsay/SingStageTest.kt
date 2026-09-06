package gr.dimitris.app.modules.singsay

import gr.dimitris.app.core.data.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SingStageTest {
    @Test fun `gain is full for together and fades over three repetitions`() {
        assertEquals(1f, SingStage.gainFor(SingStage.TOGETHER, 0))
        assertEquals(0.6f, SingStage.gainFor(SingStage.FADING, 0)); assertEquals(0.3f, SingStage.gainFor(SingStage.FADING, 1)); assertEquals(0f, SingStage.gainFor(SingStage.FADING, 2))
        assertEquals(0f, SingStage.gainFor(SingStage.TAPS_ONLY, 0)); assertEquals(0f, SingStage.gainFor(SingStage.SPEAK, 5))
    }
    @Test fun `cue level is five minus stage`() { assertEquals(0, SingStage.cueLevelFor(5)); assertEquals(1, SingStage.cueLevelFor(4)); assertEquals(2, SingStage.cueLevelFor(3)); assertEquals(3, SingStage.cueLevelFor(2)); assertEquals(4, SingStage.cueLevelFor(1)) }

    /** The whole of I2: what he did has to reach the row, or four stages of work read as no work. */
    @Test fun `only the last stage is his own, the earlier ones are assisted`() {
        assertEquals(Outcome.CORRECT, SingStage.outcomeFor(SingStage.SPEAK, skipped = false))
        assertEquals(Outcome.ASSISTED, SingStage.outcomeFor(SingStage.TAPS_ONLY, skipped = false))
        assertEquals(Outcome.ASSISTED, SingStage.outcomeFor(SingStage.FADING, skipped = false))
        assertEquals(Outcome.ASSISTED, SingStage.outcomeFor(SingStage.LISTEN, skipped = false))
    }

    @Test fun `a phrase passed over is skipped whatever stage it reached`() {
        assertEquals(Outcome.SKIPPED, SingStage.outcomeFor(SingStage.LISTEN, skipped = true))
        assertEquals(Outcome.SKIPPED, SingStage.outcomeFor(SingStage.SPEAK, skipped = true))
    }

    /** «Το έκανα» at stage 3: assisted, and the cue level says how much backing he still had. */
    @Test fun `stopping at the fading stage is assisted with cue two`() {
        assertEquals(Outcome.ASSISTED, SingStage.outcomeFor(SingStage.FADING, skipped = false))
        assertEquals(2, SingStage.cueLevelFor(SingStage.FADING))
    }

    /**
     * «Άκου» is live at every stage, the last one included (spec §12), so a phrase said alone after
     * asking to hear it is assisted work at the listening level — not the clean CORRECT at cue 0 it
     * would otherwise be. The button is never taken away; the row is what carries the honesty.
     */
    @Test fun `hearing the model is assisted work at the listening level`() {
        assertEquals(3, SingStage.cueLevelFor(SingStage.SPEAK, listened = true))
        assertEquals(Outcome.ASSISTED, SingStage.outcomeFor(SingStage.SPEAK, skipped = false, listened = true))
        // Where the stage already scored higher, listening changes nothing: stage 1 is cue 4.
        assertEquals(4, SingStage.cueLevelFor(SingStage.LISTEN, listened = true))
        // And a phrase passed over is passed over, however many times he heard it first.
        assertEquals(Outcome.SKIPPED, SingStage.outcomeFor(SingStage.SPEAK, skipped = true, listened = true))
    }

    @Test fun `labels are Greek`() {
        assertEquals("Άκου", SingStage.label(SingStage.LISTEN))
        assertEquals("Τραγούδα μαζί", SingStage.label(SingStage.TOGETHER))
        assertEquals("Τραγούδα, η μουσική σβήνει", SingStage.label(SingStage.FADING))
        assertEquals("Πες το με χτύπους", SingStage.label(SingStage.TAPS_ONLY))
        assertEquals("Πες το", SingStage.label(SingStage.SPEAK))
    }

    /** Spoken, so they must read as one adult talking to another — and never be empty. */
    @Test fun `every stage has a spoken prompt of its own`() {
        val prompts = (SingStage.LISTEN..SingStage.SPEAK).map { SingStage.prompt(it) }
        assertEquals(listOf("Άκου.", "Τραγούδα μαζί μου.", "Τραγούδα το μόνος σου.", "Πες το τραγουδιστά.", "Πες το κανονικά."), prompts)
        assertEquals("no two stages may sound the same", prompts.size, prompts.toSet().size)
        assertTrue("a prompt is spoken, so it cannot be blank", prompts.none { it.isBlank() })
    }
}
