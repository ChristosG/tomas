package gr.dimitris.app.core.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.audio.Wav
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * One microphone, one stream of samples, two destinations: the recogniser and a file of his own
 * voice. This is the whole of the one speech control.
 *
 * Before this phase «Μίλα» and «Ηχογράφηση» were two buttons that each opened the microphone for a
 * different purpose — the first to be judged, the second to be played back — and a man with a right
 * hemiparesis and no words had to understand the difference between them to use his own phone. Spec
 * §13 asks for one. The only way to have one honestly is to stop asking the recognition service to
 * open the microphone: the app opens it, and hands the engine the same bytes it is writing to disk
 * through `EXTRA_AUDIO_SOURCE`. So the transcript and the take are the same breath, by construction,
 * and «Άκου» after a window plays him exactly what the phone judged.
 *
 * Three things are deliberate:
 *
 * * **the file wins.** The pipe is written after the file and outside its lock, so a recogniser that
 *   reads two seconds and walks away — which is what `EPIPE` means, and it is allowed to — cannot
 *   stall or truncate his take. The pipe breaking is an ordinary outcome, not an error;
 * * **one take, many sessions.** Google's service gives up a second or two into a silence (Chris
 *   measured it) and [Recognition.restartsAfterSilence] answers that by opening another session.
 *   Each session needs its own pipe, because a pipe can only be read once; the microphone, the
 *   thread and the WAV carry straight on across all of them. His «Μίλα» is one take whatever the
 *   service does with it;
 * * **the header is written twice.** Once empty at the start and once over the top at the end with
 *   the real length, because the length is not known until he stops. A take interrupted by anything
 *   at all — the app killed, the phone out of space — is still a valid, if empty, file.
 *
 * The caller must hold `RECORD_AUDIO`. Nothing here can be exercised on the emulator beyond the
 * shape of it: there is no speech engine to read the pipe, which is exactly why the file being
 * independent of the pipe is the property worth testing (`PcmTakeTest`).
 */
class PcmTake private constructor(
    /** His take, in [gr.dimitris.app.core.audio.MediaFiles.recordingsDir], named as any other is. */
    val file: File,
    private val recorder: AudioRecord,
    private val blockBytes: Int,
    private val onLevel: (Float) -> Unit,
) {
    /** False from [stop] or [cancel] on. The pump thread reads it and nothing else writes it. */
    @Volatile private var running = true

    /**
     * Everything about the file: the handle, how much audio is in it, and the loudest sample seen.
     * Held only around a write of a tenth of a second, and never while the pipe is being written.
     */
    private val fileLock = Any()
    private var out: RandomAccessFile? = null
    private var dataBytes = 0
    private var peak = 0

    /**
     * The write end of the pipe the recogniser is reading, and the stream over it. Its own lock,
     * because a write to a pipe blocks until the engine reads — and a blocked pipe must never be
     * able to hold up his take or his «Στοπ».
     */
    private val pipeLock = Any()
    private var pipe: ParcelFileDescriptor? = null
    private var sink: FileOutputStream? = null

    private val startedAt = SystemClock.elapsedRealtime()
    private var pump: Thread? = null

    val isRecording: Boolean get() = running

    /**
     * A fresh pipe for one recognition session, as the read end to hand to the intent. The previous
     * session's pipe is closed first: one pipe per session, because a session is the only thing that
     * can read one.
     *
     * Null when the microphone has already stopped or the pipe could not be made. The caller then
     * opens a plain session and lets the engine use its own microphone — which costs the one-control
     * behaviour for that window and nothing else.
     */
    fun newPipe(): ParcelFileDescriptor? = synchronized(pipeLock) {
        closePipe()
        if (!running) return null
        val ends = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        pipe = ends[WRITE_END]
        sink = FileOutputStream(ends[WRITE_END].fileDescriptor)
        ends[READ_END]
    }

    /**
     * «Στοπ», or the end of the wait: the microphone closes, the pipe closes, and the header is
     * written with the real length.
     *
     * The pipe is closed *first* so that a pump thread blocked writing into a recogniser that has
     * stopped reading is freed before it is waited for; the wait itself is bounded, and the header is
     * patched whatever the thread does, because a take that cannot be closed is a take he loses.
     */
    fun stop(): Recorded {
        closePipeNow()
        running = false
        val duration = SystemClock.elapsedRealtime() - startedAt
        runCatching { pump?.join(JOIN_MS) }
        pump = null
        runCatching { recorder.stop() }
        runCatching { recorder.release() }
        val loudest: Int
        val length: Int
        synchronized(fileLock) {
            val handle = out
            out = null
            length = dataBytes
            loudest = peak
            runCatching {
                handle?.seek(0)
                handle?.write(Wav.header(length))
            }
            runCatching { handle?.close() }
        }
        return Recorded(file, duration, loudest)
    }

    /** The take belongs to a word he has left behind: closed and deleted, not kept. */
    fun cancel() {
        runCatching { stop() }
        runCatching { file.delete() }
    }

    private fun begin() {
        synchronized(fileLock) {
            out = RandomAccessFile(file, "rw").apply {
                setLength(0)
                write(Wav.header(0))
            }
        }
        recorder.startRecording()
        pump = Thread({ drain() }, "pcm-take").apply { isDaemon = true; start() }
    }

    /**
     * The one reading thread. `AudioRecord.read` blocks for about a block at a time, so this is a
     * thread and not a coroutine: it spends its life inside a blocking call that no dispatcher would
     * be the better for.
     */
    private fun drain() {
        val buffer = ByteArray(blockBytes)
        while (running) {
            val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(-1)
            // A negative answer is the recorder gone — released under us, or the microphone taken.
            if (read < 0) break
            if (read == 0) continue
            keep(buffer, read)
        }
        // Whatever ended the loop, the engine is owed an end-of-stream rather than a hang.
        closePipeNow()
    }

    private fun keep(buffer: ByteArray, read: Int) {
        val loudest = Wav.peakOf(buffer, read)
        // His take first, and under its own lock. Nothing the recogniser does can reach this.
        synchronized(fileLock) {
            val handle = out
            if (handle != null && runCatching { handle.write(buffer, 0, read) }.isSuccess) dataBytes += read
            if (loudest > peak) peak = loudest
        }
        // Then the recogniser's pipe, outside that lock. `EPIPE` — the engine has what it wanted and
        // closed its end — is how this is *supposed* to end, so it closes the pipe and says nothing.
        val stream = synchronized(pipeLock) { sink }
        if (stream != null) {
            try {
                stream.write(buffer, 0, read)
            } catch (e: IOException) {
                closePipeNow()
            }
        }
        // The bar, drawn from the app's own samples: with the engine fed through a pipe it never
        // calls `onRmsChanged`, and a man who cannot ask "is it hearing me?" would have no answer.
        onLevel(Wav.levelOf(loudest))
    }

    private fun closePipeNow() = synchronized(pipeLock) { closePipe() }

    /** Caller holds [pipeLock]. Idempotent: every path through here may be taken twice. */
    private fun closePipe() {
        runCatching { sink?.close() }
        runCatching { pipe?.close() }
        sink = null
        pipe = null
    }

    companion object {
        /**
         * Opens the microphone and starts filling [file]. Throws in Greek when the microphone cannot
         * be had at all — the permission was refused, or something else holds it — which the caller
         * answers by falling back to a plain recognition session.
         *
         * [onLevel] is called about ten times a second from the pump thread with 0..1.
         */
        @SuppressLint("MissingPermission")
        fun start(file: File, onLevel: (Float) -> Unit = {}): PcmTake {
            val minimum = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, CHANNEL, ENCODING)
            // Two of the driver's minimum, so one slow tick of the pump cannot overrun the buffer and
            // lose a syllable; and a sane floor when the driver will not say.
            val buffer = if (minimum > 0) minimum * 2 else Wav.SAMPLE_RATE * BYTES_PER_SAMPLE
            val recorder = AudioRecord(SOURCE, Wav.SAMPLE_RATE, CHANNEL, ENCODING, buffer)
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                runCatching { recorder.release() }
                throw IllegalStateException(NO_MICROPHONE)
            }
            val block = blockBytes(buffer)
            val take = PcmTake(file, recorder, block, onLevel)
            try {
                take.begin()
            } catch (e: Throwable) {
                runCatching { recorder.release() }
                throw e
            }
            return take
        }

        /**
         * A tenth of a second a read, which is how often the bar may move and how finely the peak is
         * measured — fine enough that one short syllable cannot fall between two readings. Never
         * bigger than the buffer the driver gave us.
         */
        private fun blockBytes(buffer: Int): Int =
            (Wav.SAMPLE_RATE * BYTES_PER_SAMPLE / 10).coerceAtMost(buffer).coerceAtLeast(BYTES_PER_SAMPLE)

        /**
         * `VOICE_RECOGNITION` and not `MIC`: it is the source the platform documents as leaving its
         * own noise suppression and gain shaping out of the way of a recogniser, and what he is doing
         * into this microphone is being recognised.
         */
        private const val SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val BYTES_PER_SAMPLE = 2

        private const val READ_END = 0
        private const val WRITE_END = 1

        /**
         * How long [stop] waits for the pump thread. Generous enough for a blocking read of one block
         * to return, short enough that «Στοπ» is never a button that does nothing.
         */
        const val JOIN_MS = 500L

        /** Said when the microphone could not be opened at all. Not shown to him: the caller decides. */
        const val NO_MICROPHONE = "Δεν άνοιξε το μικρόφωνο"
    }
}
