package gr.dimitris.app.core.audio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** 16-bit mono PCM helpers. Pure Kotlin so the shapes can be unit-tested. */
object Pcm {
    const val SAMPLE_RATE = 44_100
    private const val ATTACK_MS = 20
    private const val RELEASE_MS = 60

    fun tone(hz: Double, ms: Int, gain: Float): ShortArray {
        val n = SAMPLE_RATE * ms / 1000
        val attack = SAMPLE_RATE * ATTACK_MS / 1000
        val release = SAMPLE_RATE * RELEASE_MS / 1000
        val amp = 32_000.0 * gain.coerceIn(0f, 1f)
        return ShortArray(n) { i ->
            val env = min(1.0, min(i.toDouble() / attack, (n - 1 - i).toDouble() / release)).coerceAtLeast(0.0)
            (sin(2 * PI * hz * i / SAMPLE_RATE) * amp * env).toInt().toShort()
        }
    }

    fun silence(ms: Int): ShortArray = ShortArray(SAMPLE_RATE * ms / 1000)

    fun concat(parts: List<ShortArray>): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        parts.forEach { it.copyInto(out, pos); pos += it.size }
        return out
    }
}
