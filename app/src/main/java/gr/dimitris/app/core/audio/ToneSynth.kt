package gr.dimitris.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import gr.dimitris.app.modules.singsay.Melody
import gr.dimitris.app.modules.singsay.Pitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Plays a sequence of two-pitch notes and calls [onNote] as each one starts, so the screen can
 * light the syllable. Like every other entry point in the audio stack ([Player.play],
 * [gr.dimitris.app.core.speech.TextToSpeech.speak]), [play] never throws: a device with no usable
 * audio output, or any other `AudioTrack` failure, comes back as a [Result.failure] carrying
 * [PLAYBACK_FAILED] instead of crashing the caller's coroutine.
 *
 * [current] identifies whichever call is currently allowed to reach [AudioTrack.play] — set before
 * that call's `AudioTrack` even exists. [stop] clears it, so a call still building its track on
 * [Dispatchers.IO] notices as soon as that work returns and discards what it built instead of
 * starting it. Without this, a [stop] landing in the narrow window between "building" and
 * "assigned to [track]" would find nothing to stop, and the note would play anyway.
 */
class ToneSynth {
    @Volatile private var track: AudioTrack? = null

    @Volatile private var current: Any? = null

    suspend fun play(
        notes: List<Pitch>,
        noteMs: Int = Melody.NOTE_MS,
        gapMs: Int = Melody.GAP_MS,
        gain: Float = 1f,
        onNote: (Int) -> Unit = {},
    ): Result<Unit> {
        if (notes.isEmpty()) return Result.success(Unit)
        stop()
        val self = Any()
        current = self

        // `t` and `written` are assigned as side effects *inside* the IO block below, not read
        // from its return value: if the caller's job is cancelled while suspended waiting to
        // resume from Dispatchers.IO, withContext discards the block's return value and throws
        // CancellationException instead of returning it — but the assignment already ran, so `t`
        // still reaches `finally` and the native track is released there, never leaked.
        var t: AudioTrack? = null
        var written = -1
        try {
            val pcm = Pcm.concat(notes.flatMap { listOf(Pcm.tone(it.hz, noteMs, gain), Pcm.silence(gapMs)) })
            withContext(Dispatchers.IO) {
                val built = AudioTrack.Builder()
                    .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                    .setBufferSizeInBytes(pcm.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()
                t = built
                // A MODE_STATIC track reports STATE_NO_STATIC_DATA until its buffer has been
                // filled: STATE_INITIALIZED is what it *becomes* once the whole melody is written,
                // never what it starts as. Only STATE_UNINITIALIZED means the platform refused.
                if (built.state != AudioTrack.STATE_UNINITIALIZED) written = built.write(pcm, 0, pcm.size)
            }

            val built = t
            if (built == null || built.state != AudioTrack.STATE_INITIALIZED || written != pcm.size) {
                return Result.failure(IllegalStateException(PLAYBACK_FAILED))
            }
            if (current !== self) return Result.success(Unit) // stopped while we were building/writing

            track = built
            val played = runCatching { built.play() }
            if (played.isFailure) {
                track = null
                return Result.failure(IllegalStateException(PLAYBACK_FAILED))
            }
            for (i in notes.indices) {
                if (current !== self) return Result.success(Unit)
                onNote(i)
                delay((noteMs + gapMs).toLong())
            }
            return Result.success(Unit)
        } finally {
            withContext(NonCancellable) {
                if (track === t) track = null
                releaseQuietly(t)
            }
        }
    }

    /** Stops (or cancels the in-flight build of) whatever is playing. Idempotent: safe before the first [play] and safe to call any number of times. */
    fun stop() {
        current = null
        releaseQuietly(track)
        track = null
    }

    /** pause+flush+release, each independently best-effort so a throw from one never skips the others. */
    private fun releaseQuietly(t: AudioTrack?) {
        if (t == null) return
        runCatching { t.pause() }
        runCatching { t.flush() }
        runCatching { t.release() }
    }

    companion object {
        /** The one place the output sample rate is defined; [Pcm] generates the PCM at this rate too. */
        const val SAMPLE_RATE = Pcm.SAMPLE_RATE

        /** Said when a note couldn't be played — no usable audio output, or the platform refused the track. */
        const val PLAYBACK_FAILED = "Δεν παίζει ο ήχος"
    }
}
