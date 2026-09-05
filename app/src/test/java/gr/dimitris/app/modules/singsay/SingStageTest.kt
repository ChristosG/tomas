package gr.dimitris.app.modules.singsay

import org.junit.Assert.assertEquals
import org.junit.Test

class SingStageTest {
    @Test fun `gain is full for together and fades over three repetitions`() {
        assertEquals(1f, SingStage.gainFor(SingStage.TOGETHER, 0))
        assertEquals(0.6f, SingStage.gainFor(SingStage.FADING, 0)); assertEquals(0.3f, SingStage.gainFor(SingStage.FADING, 1)); assertEquals(0f, SingStage.gainFor(SingStage.FADING, 2))
        assertEquals(0f, SingStage.gainFor(SingStage.TAPS_ONLY, 0)); assertEquals(0f, SingStage.gainFor(SingStage.SPEAK, 5))
    }
    @Test fun `cue level is five minus stage`() { assertEquals(0, SingStage.cueLevelFor(5)); assertEquals(3, SingStage.cueLevelFor(2)); assertEquals(4, SingStage.cueLevelFor(1)) }
    @Test fun `labels are Greek`() = assertEquals("Άκου", SingStage.label(SingStage.LISTEN))
}
