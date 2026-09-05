package gr.dimitris.app.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmTest {
    @Test fun `length matches duration`() = assertEquals(44_100 / 2, Pcm.tone(440.0, 500, 1f).size)
    @Test fun `silence is zeros`() = assertTrue(Pcm.silence(10).all { it == 0.toShort() })
    @Test fun `starts and ends near zero because of the envelope`() {
        val t = Pcm.tone(440.0, 200, 1f)
        assertTrue(abs(t[0].toInt()) < 200 && abs(t[t.lastIndex].toInt()) < 200)
    }
    @Test fun `peak scales with gain`() {
        val loud = Pcm.tone(440.0, 200, 1f).maxOf { abs(it.toInt()) }
        val soft = Pcm.tone(440.0, 200, 0.5f).maxOf { abs(it.toInt()) }
        assertTrue(loud > 20_000 && soft in (loud / 2 - 600)..(loud / 2 + 600))
    }
    @Test fun `zero gain is silent`() = assertTrue(Pcm.tone(440.0, 50, 0f).all { it == 0.toShort() })
    @Test fun `concat joins in order`() {
        val a = shortArrayOf(1, 2); val b = shortArrayOf(3)
        assertEquals(listOf<Short>(1, 2, 3), Pcm.concat(listOf(a, b)).toList())
    }
}
