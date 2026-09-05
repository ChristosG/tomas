package gr.dimitris.app.core.audio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** 16-bit mono PCM helpers. Pure Kotlin so the shapes can be unit-tested. */
object Pcm {
    /**
     * 44.1kHz mono 16-bit: universally supported, so this is not read from the device's native
     * rate / `getMinBufferSize` — the platform resamples if a particular output needs something
     * else. The single source of truth: [ToneSynth.SAMPLE_RATE] reads this constant rather than
     * repeating it, so the generated PCM and the `AudioTrack` it is played through never disagree.
     */
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
