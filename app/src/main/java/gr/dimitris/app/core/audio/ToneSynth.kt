package gr.dimitris.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import gr.dimitris.app.modules.singsay.Melody
import gr.dimitris.app.modules.singsay.Pitch
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Plays a sequence of two-pitch notes and calls [onNote] as each one starts, so the screen can
 * light the syllable. Like every other entry point in the audio stack ([Player.play],
 * [gr.dimitris.app.core.speech.TextToSpeech.speak]), [play] **never throws**: a device with no
 * usable audio output, or any other `AudioTrack` failure, comes back as a [Result.failure] carrying
 * [PLAYBACK_FAILED]. That includes `AudioTrack.Builder.build()`, which does not return an
 * unusable track — it checks the state itself and throws `UnsupportedOperationException` — so every
 * platform call here is wrapped. Only [CancellationException] passes through, because a cancelled
 * caller is not a failed one.
 *
 * One melody at a time, enforced by [gate]: at most one `AudioTrack` exists at any moment, and the
 * newest caller wins — it claims the output *before* queueing for the gate, so whoever holds it
 * stops instead of making the new melody wait for the old one. [current] identifies whichever call
 * is allowed to reach [AudioTrack.play] — set before that call's `AudioTrack` even exists — so a
 * [stop] landing in the window between "building" and "assigned to [track]" still prevents the note.
 * [track] is an [AtomicReference] rather than a plain field because the release must happen exactly
 * once: whoever takes the track out of it releases it, and the loser of that race does nothing.
 */
class ToneSynth {
    /** One melody at a time. Held for the whole of a call's build-play-wait, released on any exit. */
    private val gate = Mutex()

    /** The track currently owned by the playing call, or null. Taken out by exactly one releaser. */
    private val track = AtomicReference<AudioTrack?>(null)

    @Volatile private var current: Any? = null

    suspend fun play(
        notes: List<Pitch>,
        noteMs: Int = Melody.NOTE_MS,
        gapMs: Int = Melody.GAP_MS,
        gain: Float = 1f,
        onNote: (Int) -> Unit = {},
    ): Result<Unit> {
        if (notes.isEmpty()) return Result.success(Unit)
        val self = Any()
        // Claimed before queueing, not after the gate is granted: a caller arriving while another
        // melody plays has to invalidate it *now*, or the newest melody would politely wait for the
        // one it was meant to replace.
        claim(self)
        return gate.withLock { playHoldingGate(self, notes, noteMs, gapMs, gain, onNote) }
    }

    private suspend fun playHoldingGate(
        self: Any,
        notes: List<Pitch>,
        noteMs: Int,
        gapMs: Int,
        gain: Float,
        onNote: (Int) -> Unit,
    ): Result<Unit> {
        // Superseded while we waited for the gate: the caller behind us already owns the output.
        if (current !== self) return Result.success(Unit)

        // `t`, `written` and `published` are assigned as side effects *inside* the IO block below,
        // not read from its return value: if the caller's job is cancelled while suspended waiting
        // to resume from Dispatchers.IO, withContext discards the block's return value and throws
        // CancellationException instead of returning it — but the assignment already ran, so `t`
        // still reaches `finally` and the native track is released there, never leaked.
        var t: AudioTrack? = null
        var written = -1
        var published = false
        try {
            val pcm = Pcm.concat(notes.flatMap { listOf(Pcm.tone(it.hz, noteMs, gain), Pcm.silence(gapMs)) })
            withContext(Dispatchers.IO) {
                // build() throws (it does not return an uninitialised track) when the platform
                // cannot allocate one — an incoming call, another app holding an exclusive route.
                val built = runCatching {
                    AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(pcm.size * 2)
                        .setTransferMode(AudioTrack.MODE_STATIC)
                        .build()
                }.getOrNull()
                t = built
                // A MODE_STATIC track reports STATE_NO_STATIC_DATA until its buffer has been
                // filled: STATE_INITIALIZED is what it *becomes* once the whole melody is written,
                // never what it starts as. Only STATE_UNINITIALIZED means the platform refused.
                if (built != null && stateOf(built) != AudioTrack.STATE_UNINITIALIZED) {
                    written = runCatching { built.write(pcm, 0, pcm.size) }.getOrElse { -1 }
                }
            }

            val built = t
            if (built == null || stateOf(built) != AudioTrack.STATE_INITIALIZED || written != pcm.size) {
                return Result.failure(IllegalStateException(PLAYBACK_FAILED))
            }
            if (current !== self) return Result.success(Unit) // stopped while we were building/writing

            published = true
            track.set(built)
            if (runCatching { built.play() }.isFailure) {
                // A stop() landing in this instant releases the track under us and play() throws.
                // Being stopped is not a failure to report to him; only a real refusal is.
                return if (current !== self) Result.success(Unit) else Result.failure(IllegalStateException(PLAYBACK_FAILED))
            }
            for (i in notes.indices) {
                if (current !== self) return Result.success(Unit)
                onNote(i)
                delay((noteMs + gapMs).toLong())
            }
            return Result.success(Unit)
        } catch (ce: CancellationException) {
            // A cancelled caller is not a failed one: structured concurrency has to see this.
            throw ce
        } catch (e: Throwable) {
            return Result.failure(IllegalStateException(PLAYBACK_FAILED, e))
        } finally {
            withContext(NonCancellable) {
                val built = t
                // Exactly one release: if the track was never published, nobody else can see it; if
                // it was, only the caller that takes it out of [track] releases it.
                if (built != null && (!published || track.compareAndSet(built, null))) releaseQuietly(built)
            }
        }
    }

    /** Stops (or cancels the in-flight build of) whatever is playing. Idempotent: safe before the first [play] and safe to call any number of times. Never suspends and never throws. */
    fun stop() = claim(null)

    /**
     * Hands the output to [token] (or to nobody, for [stop]) and silences whatever was sounding.
     * The [AtomicReference.getAndSet] is what makes the release happen exactly once: the caller that
     * takes the track out is the caller that releases it, and the playing call's `finally` finds it
     * gone and does nothing.
     */
    private fun claim(token: Any?) {
        current = token
        releaseQuietly(track.getAndSet(null))
    }

    /** A released track can throw on `getState()`; an unreadable state is not an initialised one. */
    private fun stateOf(t: AudioTrack): Int = runCatching { t.state }.getOrDefault(AudioTrack.STATE_UNINITIALIZED)

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
