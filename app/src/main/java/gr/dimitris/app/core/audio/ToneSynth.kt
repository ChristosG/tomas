package gr.dimitris.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import gr.dimitris.app.modules.singsay.Key
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

    /**
     * [breathBefore] are the indices of the notes that start a breath group
     * ([Melody.breaths]): the silence before each of them is [Melody.BREATH_GAPS] gaps long instead
     * of one, so a long sentence is sung in breaths rather than in one unbroken run. Empty is the
     * old behaviour exactly — an even [gapMs] after every note.
     */
    suspend fun play(
        notes: List<Pitch>,
        noteMs: Int = Melody.NOTE_MS,
        gapMs: Int = Melody.GAP_MS,
        gain: Float = 1f,
        key: Key = Key.NORMAL,
        breathBefore: Set<Int> = emptySet(),
        onNote: (Int) -> Unit = {},
    ): Result<Unit> {
        if (notes.isEmpty()) return Result.success(Unit)
        val self = Any()
        // Claimed before queueing, not after the gate is granted: a caller arriving while another
        // melody plays has to invalidate it *now*, or the newest melody would politely wait for the
        // one it was meant to replace.
        claim(self)
        return gate.withLock { playHoldingGate(self, notes, noteMs, gapMs, gain, key, breathBefore, onNote) }
    }

    private suspend fun playHoldingGate(
        self: Any,
        notes: List<Pitch>,
        noteMs: Int,
        gapMs: Int,
        gain: Float,
        key: Key,
        breathBefore: Set<Int>,
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
        var pcm: ShortArray? = null
        var streaming = false
        try {
            withContext(Dispatchers.IO) {
                // Synthesised here, not on the caller's dispatcher: an eight-syllable phrase is a
                // few hundred thousand sin() evaluations and half a megabyte of ShortArray, and the
                // caller is viewModelScope — i.e. the thread drawing the syllables.
                //
                // TAIL_MS of silence is appended to whatever was asked for: the wait below counts
                // from play(), so the device's own output latency (tens of ms on a speaker, more on
                // Bluetooth) is still unplayed when the track is released, and it would take the
                // last note's decay with it.
                val audio = Pcm.concat(
                    notes.flatMapIndexed { i, p ->
                        listOf(Pcm.tone(key.hz(p), noteMs, gain), Pcm.silence(Melody.gapAfter(i, gapMs, breathBefore)))
                    } + listOf(Pcm.silence(TAIL_MS)),
                )
                pcm = audio
                streaming = streams(audio.size)
                // build() throws (it does not return an uninitialised track) when the platform
                // cannot allocate one — an incoming call, another app holding an exclusive route.
                val built = runCatching { build(audio.size, streaming) }.getOrNull()
                t = built
                // A MODE_STATIC track reports STATE_NO_STATIC_DATA until its buffer has been
                // filled: STATE_INITIALIZED is what it *becomes* once the whole melody is written,
                // never what it starts as. Only STATE_UNINITIALIZED means the platform refused.
                // A MODE_STREAM track is INITIALIZED from the moment it is built.
                if (built != null && stateOf(built) != AudioTrack.STATE_UNINITIALIZED) {
                    written =
                        if (streaming) feed(built, audio, 0)
                        else runCatching { built.write(audio, 0, audio.size) }.getOrElse { -1 }
                }
            }

            val built = t
            val audio = pcm
            val ready = built != null && audio != null && stateOf(built) == AudioTrack.STATE_INITIALIZED &&
                if (streaming) written > 0 else written == audio.size
            if (!ready || built == null || audio == null) return Result.failure(IllegalStateException(PLAYBACK_FAILED))
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
                // A streamed melody is fed a note at a time, from this loop and from no other
                // thread: the ring buffer holds a few seconds, the writes never block, and the only
                // code that can touch the track is the code that is also watching [current].
                if (streaming) {
                    written = feed(built, audio, written)
                    if (written < 0) return stoppedOrFailed(self)
                }
                // The same gap the PCM above was written with, note for note — the breath he hears
                // and the breath the lit syllables wait out are one number, read once.
                delay((noteMs + Melody.gapAfter(i, gapMs, breathBefore)).toLong())
            }
            if (streaming) return drain(self, built, audio, written)
            // The silent tail written above, waited out here: without it the release below throws
            // away whatever the device had not yet pushed out, clipping the last note's decay.
            if (current === self) delay(TAIL_MS.toLong())
            return Result.success(Unit)
        } catch (ce: CancellationException) {
            // A cancelled caller is not a failed one: structured concurrency has to see this.
            throw ce
        } catch (e: Throwable) {
            return Result.failure(IllegalStateException(PLAYBACK_FAILED, e))
        } finally {
            // IO as well as NonCancellable: pause/flush/release are native calls, and this `finally`
            // otherwise runs them on whatever the caller's dispatcher was — the main thread.
            withContext(NonCancellable + Dispatchers.IO) {
                val built = t
                // Exactly one release: if the track was never published, nobody else can see it; if
                // it was, only the caller that takes it out of [track] releases it.
                if (built != null && (!published || track.compareAndSet(built, null))) releaseQuietly(built)
            }
        }
    }

    /**
     * The track this melody is played through: the whole clip in a static buffer, or a ring buffer
     * fed as it plays when the clip is too big for one ([streams]).
     *
     * A static track's buffer is shared memory the platform has to allocate in one piece, and phase
     * 13 quadrupled what this module asks for: «Θα ήθελα να κλείσω ένα ραντεβού για αύριο το πρωί»
     * is twenty notes and four breaths — about 18 seconds at [gr.dimitris.app.modules.singsay.Tempo.SLOW],
     * which is 1.6 MB where the longest phrase before it was 0.4 MB. A refusal there would be
     * «Δεν παίζει ο ήχος» on exactly the card this module now exists for, so above the threshold the
     * melody streams instead: a bounded ring buffer, written a note at a time from the playing loop.
     */
    private fun build(samples: Int, streaming: Boolean): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(if (streaming) streamBufferBytes() else samples * 2)
        .setTransferMode(if (streaming) AudioTrack.MODE_STREAM else AudioTrack.MODE_STATIC)
        .build()

    /**
     * Hands [track] as much of [audio] from [from] as its buffer has room for, and returns where the
     * next write starts — or -1 if the track refused, which on this path means it was released under
     * us by a [stop].
     *
     * Non-blocking on purpose. A blocking write would hold an IO thread inside the native track for
     * as long as it takes the audio to drain, and [quiet] — which Dimitris causes by leaving the
     * screen — would then be racing a thread it cannot see. Nothing here ever waits; the pacing is
     * the note loop's own [delay].
     */
    private suspend fun feed(track: AudioTrack, audio: ShortArray, from: Int): Int = withContext(Dispatchers.IO) {
        if (from >= audio.size) return@withContext from
        val n = runCatching {
            track.write(audio, from, audio.size - from, AudioTrack.WRITE_NON_BLOCKING)
        }.getOrElse { -1 }
        if (n < 0) -1 else from + n
    }

    /**
     * The end of a streamed melody: whatever is left of [audio] goes in, and then the track is given
     * the time to play out what it holds — up to [DRAIN_MS], which is longer than the ring buffer
     * can possibly be behind. Without this the `finally` below would release the track with the last
     * breath group still inside it.
     */
    private suspend fun drain(self: Any, track: AudioTrack, audio: ShortArray, from: Int): Result<Unit> {
        var at = from
        var left = DRAIN_MS
        while (current === self && left > 0) {
            if (at < audio.size) {
                at = feed(track, audio, at)
                if (at < 0) return stoppedOrFailed(self)
            }
            val head = runCatching { track.playbackHeadPosition }.getOrElse { audio.size }
            if (at >= audio.size && head >= audio.size) return Result.success(Unit)
            delay(FEED_MS.toLong())
            left -= FEED_MS
        }
        return Result.success(Unit)
    }

    /** A track that refused mid-melody: being stopped is not a failure to report to him, a refusal is. */
    private fun stoppedOrFailed(self: Any): Result<Unit> =
        if (current !== self) Result.success(Unit) else Result.failure(IllegalStateException(PLAYBACK_FAILED))

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

        /** Silence written after the last note and waited out, so output latency cannot eat its decay. */
        const val TAIL_MS = 150

        /**
         * The most PCM that goes into one static buffer, in bytes — half a megabyte, about six
         * seconds of [SAMPLE_RATE] mono. Everything the module asked for before phase 13 is under
         * it (the longest phrase was seven syllables, 0.4 MB) and every long sentence at a slow
         * tempo is over it. See [build] for why the two paths exist at all.
         */
        const val MAX_STATIC_BYTES = 512 * 1024

        /** Whether a melody of [samples] 16-bit samples is played streamed rather than from one static buffer. */
        internal fun streams(samples: Int): Boolean = samples * 2 > MAX_STATIC_BYTES

        /**
         * The ring buffer a streamed melody is fed through: a quarter of a megabyte, which is about
         * three seconds — four or five notes of lead, where the loop tops it up every note. Never
         * below what the platform says it needs for this format.
         */
        private fun streamBufferBytes(): Int {
            val min = runCatching {
                AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            }.getOrDefault(0)
            return maxOf(STREAM_BUFFER_BYTES, if (min > 0) min else 0)
        }

        private const val STREAM_BUFFER_BYTES = 256 * 1024

        /** How often a streamed melody is topped up and asked how far it has got, in milliseconds. */
        private const val FEED_MS = 50

        /**
         * The longest a streamed melody may take to play out what it is still holding, once every
         * note has been handed over. The track can never be more than its own ring buffer behind —
         * about three seconds — so six is slack, and it is a cap rather than a wait: the loop leaves
         * the moment the playback head has reached the end.
         */
        private const val DRAIN_MS = 6_000

        /** Said when a note couldn't be played — no usable audio output, or the platform refused the track. */
        const val PLAYBACK_FAILED = "Δεν παίζει ο ήχος"
    }
}
