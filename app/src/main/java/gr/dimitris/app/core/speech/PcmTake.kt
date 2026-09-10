package gr.dimitris.app.core.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import gr.dimitris.app.core.audio.Recorded
import gr.dimitris.app.core.audio.Wav
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
 * Four things are deliberate:
 *
 * * **the file wins, and it wins on a separate thread.** The microphone thread writes the WAV and
 *   nothing else. Blocks for the recogniser are handed to a bounded queue that a second thread
 *   drains into the pipe, and when that queue is full the *oldest* block is dropped. A write into a
 *   pipe blocks until the reader takes the bytes, and a recogniser is free to hold the read end and
 *   not drain it — the first draft of this class did the pipe write on the microphone thread, so
 *   after about two seconds (a 64 KiB pipe at 32 kB/s) the `AudioRecord` ring would overrun, the bar
 *   would freeze, and his take would stop mid-word. Losing audio the recogniser was too slow to take
 *   is acceptable; losing the recording of him speaking is not;
 * * **one take, many sessions.** Google's service gives up a second or two into a silence (Chris
 *   measured it) and [Recognition.restartsAfterSilence] answers that by opening another session.
 *   Each session needs its own pipe, because a pipe can only be read once; the microphone, the
 *   threads and the WAV carry straight on across all of them. His «Μίλα» is one take whatever the
 *   service does with it;
 * * **the pipe is closed, not abandoned.** An engine fed from a descriptor may wait for
 *   end-of-stream before it decides what it heard, so «Στοπ» calls [closePipe] and the ordinary end
 *   of a take drains what is queued and then closes. `EPIPE` on the way — the engine has what it
 *   wanted and let go of its end — is how this is *supposed* to end, and it ends the pipe writer
 *   quietly;
 * * **the header is written twice.** Once empty at the start and once over the top at the end with
 *   the real length, because the length is not known until he stops. A take interrupted by anything
 *   at all — the app killed, the phone out of space — is still a valid, if empty, file.
 *
 * The caller must hold `RECORD_AUDIO`, and must call [start], [stop] and [cancel] off the main
 * thread: opening a microphone and joining a reader are not things the screen may wait on.
 *
 * Nothing here can be exercised on the emulator beyond the shape of it: there is no speech engine to
 * read the pipe, which is exactly why the file being independent of the pipe is the property worth
 * testing (`PcmTakeTest` holds a read end open and never drains it).
 */
class PcmTake private constructor(
    /** His take, in [gr.dimitris.app.core.audio.MediaFiles.recordingsDir], named as any other is. */
    val file: File,
    private val recorder: AudioRecord,
    private val blockBytes: Int,
    private val onLevel: (Float) -> Unit,
) {
    /** False from [stop] or [cancel] on. Both threads read it and only those two write it. */
    @Volatile private var running = true

    /**
     * Everything about the file: the handle, how much audio is in it, and the loudest sample seen.
     * Held only around a write of a tenth of a second, on the microphone thread and in [stop].
     */
    private val fileLock = Any()
    private var out: RandomAccessFile? = null
    private var dataBytes = 0
    private var peak = 0

    /**
     * The write end of the pipe the recogniser is reading, and the stream over it. Its own lock,
     * touched only by the pipe thread and by whoever opens or closes a pipe — never by the
     * microphone thread, which is the whole point.
     */
    private val pipeLock = Any()
    private var pipe: ParcelFileDescriptor? = null
    private var sink: FileOutputStream? = null

    /**
     * Blocks waiting to reach the recogniser, oldest first. Bounded, and full means the oldest goes:
     * the recogniser then misses a tenth of a second somewhere behind the live edge, which costs it a
     * syllable at worst, and the microphone thread never waits.
     */
    private val queue = ArrayBlockingQueue<ByteArray>(QUEUE_BLOCKS)

    /**
     * "No more blocks are coming; drain what is queued and close."
     *
     * A flag and not a marker in the queue, because the queue drops its oldest entry when it is full
     * — so a marker put in behind three seconds of audio nobody is reading would be thrown away as
     * "the oldest block" and «Στοπ» would never reach the engine at all. That is the exact failure
     * closing the pipe exists to prevent, and a flag cannot be dropped.
     */
    @Volatile private var endRequested = false

    /**
     * True while the pipe thread might be about to write, or is inside a `write` that has not come
     * back. A recogniser holding the read end and not draining it blocks that write for ever, and a
     * thread inside a blocking write will never look at [endRequested] — so [closePipe] reads this
     * to know it has to close the descriptor itself.
     *
     * Written and read under [pipeLock], and — this is the part two rounds of fixes got wrong — it
     * goes up at the **start** of the interval it describes, before the thread goes looking for a
     * block, and comes down only after the write for that block has returned. Set in the step that
     * takes the stream instead, it left a hair between `queue.poll` handing over the last block and
     * that step: «Στοπ» landing in there found an empty queue and a flag still down, closed nothing,
     * and the write that followed wedged against a recogniser nobody was draining. Twenty seconds of
     * a dead «Στοπ», in a window too narrow to test and wide enough to happen.
     *
     * The cost of starting it early is that the flag is up nearly all the time — the thread lives in
     * its [POLL_MS] poll — so «Στοπ» almost always closes the descriptor itself rather than leaving
     * it to the thread's own exit. That is the same close either way and it is the prompter of the
     * two; the branch it bypasses only ever applied when the queue was empty, which is to say when
     * there was nothing to lose by closing.
     */
    private var writing = false

    /** Counted down by the pipe thread once it has closed the pipe. [stop] waits on it, briefly. */
    private val pipeClosed = CountDownLatch(1)

    private val startedAt = SystemClock.elapsedRealtime()
    private var microphone: Thread? = null
    private var toPipe: Thread? = null

    val isRecording: Boolean get() = running

    /**
     * A fresh pipe for one recognition session, as the read end to hand to the intent. The previous
     * session's pipe is closed first: one pipe per session, because a session is the only thing that
     * can read one.
     *
     * Null when the microphone has already stopped or the pipe could not be made. There is no honest
     * fallback for that window — the app is holding the microphone, so letting the engine open it
     * too would be two captures on one device and usually silences one of them — so the caller
     * cancels the take and runs a plain recognition session instead. See
     * [AndroidSpeechToText.listen].
     */
    fun newPipe(): ParcelFileDescriptor? = synchronized(pipeLock) {
        closePipeLocked()
        if (!running) return null
        val ends = runCatching { ParcelFileDescriptor.createPipe() }.getOrNull() ?: return null
        pipe = ends[WRITE_END]
        sink = FileOutputStream(ends[WRITE_END].fileDescriptor)
        ends[READ_END]
    }

    /**
     * «Στοπ»: the recogniser is told there is no more audio coming, now.
     *
     * An engine fed from a descriptor may be waiting for end-of-stream before it says what it heard,
     * and the session's own bound is twenty seconds — so without this his «Στοπ» could stand there
     * doing nothing while the words he got out were thrown away. The microphone and the file are
     * untouched: this closes the pipe and nothing else.
     *
     * Prompt in both directions, and nothing waits for it: with a recogniser that is keeping up the
     * queued tail of his word goes down the pipe first and the close follows within a [POLL_MS] turn;
     * with one that has stopped reading the descriptor is closed here and now.
     *
     * Only [AndroidSpeechToText.stop] and [stop] call this, and both run where [newPipe] cannot be
     * interleaving — «Στοπ» is posted to the main looper, which is also the only thread that opens a
     * session's pipe. That is what makes closing "whatever is current" safe here without the identity
     * check [toPipe] needs.
     */
    fun closePipe() = synchronized(pipeLock) {
        endRequested = true
        // Two cases, decided here under the one lock the pipe thread also takes, so there is no
        // interleaving between them.
        //
        // **Nothing in flight and nothing queued** — the pipe thread is between two turns of its
        // loop, or already gone. The flag is enough: it comes round within [POLL_MS], finds the
        // queue dry and closes gracefully. It really will be dry, because the microphone stops
        // feeding this queue the moment the flag goes up: what he says after «Στοπ» is audio he has
        // said he is finished with, and the file keeps it without the engine being handed it. This
        // branch is narrow on purpose — [writing] is up from before the thread goes looking for a
        // block, not from when it starts writing one, so a thread that is alive is nearly always
        // holding the flag and takes the other branch.
        //
        // **Anything in flight or waiting** — a recogniser that is behind, which on this path means
        // one that has stopped reading. Nothing queued will reach it, and a write already in flight
        // never returns, so the descriptor is closed here and now. Android's
        // `FileOutputStream.close` signals threads blocked on that descriptor, so the blocked write
        // throws and the pipe thread carries on to its own close. What is lost is a tail the engine
        // was never going to read; what is saved is «Στοπ» meaning something.
        if (writing || queue.isNotEmpty()) closePipeLocked()
    }

    /**
     * The end of the wait: the microphone closes, whatever is queued is given a moment to reach the
     * recogniser, the pipe closes and the header is written with the real length.
     *
     * Every wait here is bounded, and the header is patched whatever the two threads are doing,
     * because a take that cannot be closed is a take he loses. In particular nothing joins the pipe
     * thread: it is a daemon holding two descriptors, and a recogniser that stopped reading must
     * never be able to make «Στοπ» a button that does nothing.
     *
     * Ordering, which matters: `AudioRecord.stop()` comes first so a pending `read` returns at once
     * and the join below is instant; `release()` waits for that thread to be *observed* gone,
     * because releasing a recorder another thread is inside is a native-level hazard. The join is
     * still bounded — a take that cannot be closed is a take he loses — so if the thread is somehow
     * still alive when the bound runs out the recorder is released anyway and the fact is written
     * down once, which is the honest order of those two risks. On the recogniser's side the ordering
     * is [AndroidSpeechToText]'s: the session's `destroy()` closes the service's copy of the read
     * end, and *that* — not anything here — is what frees a pipe write blocked against a service
     * that walked away.
     */
    fun stop(): Recorded {
        running = false
        val duration = SystemClock.elapsedRealtime() - startedAt
        runCatching { recorder.stop() }
        val reader = microphone
        microphone = null
        runCatching { reader?.join(MICROPHONE_JOIN_MS) }
        if (reader != null && reader.isAlive) {
            // Once, and to the log rather than to anyone's screen: it is a diagnostic about a
            // scheduler, and there is nothing a caregiver could do about it.
            Log.w(TAG, "microphone thread outlived its ${MICROPHONE_JOIN_MS}ms join; releasing anyway")
        }
        runCatching { recorder.release() }
        // What is queued is the tail of what he said: the recogniser gets a moment to take it, and
        // then the pipe closes whether it did or not.
        closePipe()
        runCatching { pipeClosed.await(PIPE_GRACE_MS, TimeUnit.MILLISECONDS) }
        synchronized(pipeLock) { closePipeLocked() }
        toPipe = null
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
        return Recorded(file, duration, loudest, silenceFloor = Recorded.SILENCE_PEAK_PCM)
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
        toPipe = Thread({ toPipe() }, "pcm-take-pipe").apply { isDaemon = true; start() }
        microphone = Thread({ fromMicrophone() }, "pcm-take-mic").apply { isDaemon = true; start() }
    }

    /**
     * The microphone thread. It reads, writes the WAV, moves the bar and hands a copy to the queue —
     * and it does nothing that can block on anything but the microphone itself. `AudioRecord.read`
     * blocks for about a block at a time, so this is a thread and not a coroutine: it spends its life
     * inside a blocking call that no dispatcher would be the better for.
     */
    private fun fromMicrophone() {
        val buffer = ByteArray(blockBytes)
        while (running) {
            val read = runCatching { recorder.read(buffer, 0, buffer.size) }.getOrDefault(-1)
            // A negative answer is the recorder gone — released under us, or the microphone taken.
            if (read < 0) break
            if (read == 0) continue
            keep(buffer, read)
        }
        // Whatever ended the loop, the engine is owed an end-of-stream rather than a hang. The flag,
        // not a hard close: what is already queued is still his voice and should still reach it.
        endRequested = true
    }

    private fun keep(buffer: ByteArray, read: Int) {
        val loudest = Wav.peakOf(buffer, read)
        // His take, and nothing the recogniser does can reach this.
        synchronized(fileLock) {
            val handle = out
            if (handle != null && runCatching { handle.write(buffer, 0, read) }.isSuccess) dataBytes += read
            if (loudest > peak) peak = loudest
        }
        // A copy, because this buffer is about to be filled again. Never a blocking put: when the
        // queue is full the oldest block goes, so a recogniser that has stopped reading costs the
        // engine a syllable and costs his take nothing at all.
        //
        // Nothing at all once he has said «Στοπ». The microphone runs on until the wait ends, so the
        // file holds everything up to the moment he finished — but the engine has been told there is
        // no more coming, and a queue the microphone kept refilling would never run dry for the pipe
        // thread to close on.
        if (!endRequested) {
            val block = buffer.copyOf(read)
            while (!queue.offer(block)) {
                if (queue.poll() == null) break
            }
        }
        // The bar, drawn from the app's own samples: with the engine fed through a pipe it never
        // calls `onRmsChanged`, and a man who cannot ask "is it hearing me?" would have no answer.
        onLevel(Wav.levelOf(loudest))
    }

    /**
     * The pipe thread. Everything that can block on the recogniser happens here and nowhere else.
     *
     * `EPIPE` is the ordinary ending of one *session* — the engine took what it wanted and let go of
     * its end — so it closes that pipe quietly and carries on. It carries on because the wait is not
     * over: a session that heard nothing is started again, with a new pipe, and the same queue has to
     * keep feeding it. Blocks arriving while no pipe is open are dropped, which is the honest thing
     * to do with audio nobody is listening to.
     *
     * The thread ends when the end of the stream has been asked for and the queue has run dry, or
     * when the microphone has stopped and the queue has run dry. The first of those is reachable
     * because [keep] stops feeding this queue the moment [endRequested] goes up.
     */
    private fun toPipe() {
        try {
            while (true) {
                // Drain first, end second: the last blocks are the end of what he said, and an engine
                // given end-of-stream in front of them would decide on a truncated word.
                if (endRequested && queue.isEmpty()) break
                // The flag goes up before the look, and comes down in the `finally` below whatever
                // happened in between. That is what makes it safe to read: from here to the end of
                // this turn there is no instant at which this thread holds a block and «Στοπ» can
                // see an idle pipe. The gap between turns is safe for the other reason — no block is
                // in hand there, and the `endRequested` check above is the next thing this thread
                // does, so a «Στοπ» landing in it ends the thread rather than being missed by it.
                synchronized(pipeLock) { writing = true }
                try {
                    val block = try {
                        queue.poll(POLL_MS, TimeUnit.MILLISECONDS)
                    } catch (e: InterruptedException) {
                        break
                    }
                    if (block == null) {
                        if (endRequested || !running) break else continue
                    }
                    // No pipe open: the block is dropped, which is the honest thing to do with audio
                    // nobody is listening to.
                    val stream = synchronized(pipeLock) { sink } ?: continue
                    try {
                        stream.write(block)
                    } catch (e: IOException) {
                        // Only the pipe this write was for. By the time a write fails the session
                        // that owned it has usually been destroyed and the *next* one may already
                        // have opened its pipe — closing whatever is current would then feed the new
                        // session nothing and it would come back having heard him say nothing at all.
                        synchronized(pipeLock) { if (sink === stream) closePipeLocked() }
                    }
                } finally {
                    synchronized(pipeLock) { writing = false }
                }
            }
        } finally {
            synchronized(pipeLock) { writing = false; closePipeLocked() }
            pipeClosed.countDown()
        }
    }

    /** Caller holds [pipeLock]. Idempotent: every path through here may be taken twice. */
    private fun closePipeLocked() {
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
         * Off the main thread, please: constructing an `AudioRecord` and opening a file are not
         * things the screen may wait on. [onLevel] is called about ten times a second from the
         * microphone thread with 0..1.
         */
        @SuppressLint("MissingPermission")
        fun start(file: File, onLevel: (Float) -> Unit = {}): PcmTake {
            val minimum = AudioRecord.getMinBufferSize(Wav.SAMPLE_RATE, CHANNEL, ENCODING)
            // Two of the driver's minimum, so one slow tick of the reader cannot overrun the buffer
            // and lose a syllable; and a sane floor when the driver will not say.
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
         * How much audio may wait for a slow recogniser: 32 blocks of a tenth of a second, a little
         * over three seconds, and about 100 kB of memory. Deep enough that an engine pausing to think
         * loses nothing, shallow enough that what it eventually reads is still roughly what he is
         * saying now rather than a recording of a minute ago.
         */
        const val QUEUE_BLOCKS = 32

        /** How long the pipe thread waits for a block before looking again at whether the take is over. */
        private const val POLL_MS = 50L

        /**
         * How long [stop] waits for the microphone thread. It has just been told to stop, so a
         * pending read is already on its way back; this is only so that a thread descheduled at the
         * wrong moment cannot leave the header short.
         */
        const val MICROPHONE_JOIN_MS = 100L

        /**
         * How long [stop] gives the queued tail to reach the recogniser before the pipe is closed
         * regardless. Short on purpose: this is «Στοπ» under his thumb, and the whole of [stop] has
         * to come back inside a fifth of a second whatever the recogniser is doing.
         */
        const val PIPE_GRACE_MS = 50L

        /** Said when the microphone could not be opened at all. Not shown to him: the caller decides. */
        const val NO_MICROPHONE = "Δεν άνοιξε το μικρόφωνο"

        private const val TAG = "PcmTake"
    }
}
