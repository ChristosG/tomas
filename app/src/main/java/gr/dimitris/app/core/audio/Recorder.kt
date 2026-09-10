package gr.dimitris.app.core.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.Timer
import java.util.TimerTask

/**
 * One finished take. [peakAmplitude] is the loudest sample of it on [MediaRecorder.getMaxAmplitude]'s
 * 0..32767 scale, and is what tells a take he spoke into from a take he did not.
 *
 * Two recorders fill this in now, on the same scale and against the same line: [Recorder], which
 * polls the AAC encoder, and [gr.dimitris.app.core.speech.PcmTake], which knows the exact peak
 * because it has the samples in its hand ([Wav.peakOf]). The second is the one «Μίλα» uses on the
 * on-device path, where one microphone feeds the recogniser and his own file at once.
 *
 * It defaults to 0 so that a [Recorded] built anywhere else — a test, a future recorder — reads as
 * silence rather than quietly passing a check it was never measured for.
 */
data class Recorded(
    val file: File,
    val durationMs: Long,
    val peakAmplitude: Int = 0,
    /**
     * The line *this* take is judged against. It travels with the take because the two recorders
     * measure the same 0..32767 scale through different paths and do not deserve the same number:
     * see [SILENCE_PEAK] and [SILENCE_PEAK_PCM]. A `Recorded` built anywhere else gets the stricter
     * of the two rather than quietly passing a check it was never measured for.
     */
    val silenceFloor: Int = SILENCE_PEAK,
) {
    /**
     * Nothing was said into this one. Chris found that a take never checked anything, so a silent
     * one "passed" and was saved as his voice; the module deletes these and asks again instead.
     */
    val isSilent: Boolean get() = peakAmplitude < silenceFloor

    companion object {
        /**
         * Below this, nobody spoke into a [Recorder] take.
         *
         * Calibration: the emulator's microphone is dead silent and a take of over a second reads a
         * peak of about 8 — the codec's own noise floor, measured by `RecorderTest`, not zero. A
         * voice at arm's length on a real phone reads roughly 3 000–20 000 on this scale, and room
         * noise well under 1 000, so the line sits at 1 500: far enough above a
         * quiet room that a rustle is not a word, far enough below a quiet voice that his is never
         * thrown away. Every take's real peak rides along in the attempt's detail as `peak`, so the
         * number can be moved on evidence from his phone instead of on a guess.
         */
        const val SILENCE_PEAK = 1_500

        /**
         * The same line for a take made by [gr.dimitris.app.core.speech.PcmTake], and it is lower on
         * purpose.
         *
         * **Measured, on this emulator:** a second of its silent microphone reads a peak of **8**
         * through `PcmTake` (`PcmTakeTest`) — the same 8 `RecorderTest` reads through the AAC
         * encoder. So the noise floor itself gives no reason to move the line.
         *
         * **Unmeasured, and the reason it moves anyway:** [SILENCE_PEAK] was calibrated against
         * `MediaRecorder.getMaxAmplitude()`, which reads the signal on its way into the AAC encoder
         * with whatever gain the `MIC` source applies. `PcmTake` reads raw PCM16 off
         * `VOICE_RECOGNITION`, a source the platform documents as leaving its own gain shaping out
         * of the way of a recogniser. Whether the *same voice* lands lower on that path is a
         * property of his Samsung and cannot be measured on a silent emulator — but if it does, and
         * the line stayed at 1 500, quiet Greek words he really said would start being deleted.
         *
         * The reasoning for 600, said plainly rather than promised: the floor is 8, room noise
         * through a phone microphone sits in the low hundreds, quiet speech at normal distance lands
         * around 1 500–3 000, and an ordinary word well above that. 600 is two orders of magnitude
         * above the floor, clear of a quiet room, and comfortably below the quietest thing he is
         * likely to get out. Erring low is the right direction here, because the cost of keeping a
         * too-quiet take is a quiet playback, while the cost of deleting one is losing the one word
         * he managed.
         *
         * **Adaptation data.** Every take's real peak rides in the attempt's detail as `peak`
         * alongside `sttOn`, so a fortnight of his rows says whether 600 is right: the number to
         * watch is the *lowest* peak on takes he did speak into. See `docs/ADAPTATION.md`.
         */
        const val SILENCE_PEAK_PCM = 600

        /** Said when the take had nothing in it. An invitation, never a verdict on his voice. */
        const val SILENT_TAKE = "Δεν σε άκουσα. Πες το πιο δυνατά."

        /**
         * The same refusal in the caregiver's editors. Hers is about the take, not about her: she
         * is recording a model, often at arm's length across a kitchen table, and "δεν σε άκουσα"
         * would read as the app addressing her the way it addresses him.
         */
        const val SILENT_TAKE_CAREGIVER = "Δεν ακούστηκε τίποτα. Ξαναπές το πιο δυνατά."
    }
}

/** One recording at a time, AAC in an .m4a container. Caller must hold RECORD_AUDIO. */
class Recorder(private val context: Context, private val files: MediaFiles) {
    private var recorder: MediaRecorder? = null
    private var current: File? = null
    private var startedAt = 0L

    /**
     * The loudest thing heard so far, and the timer filling it. `maxAmplitude` reports the peak
     * *since it was last read*, so it has to be polled: read once at the end and the whole take
     * before that last window would be invisible.
     */
    private val peakLock = Any()
    private var sampler: Timer? = null
    private var peak = 0

    val isRecording: Boolean get() = recorder != null

    fun start(): File {
        check(recorder == null) { "Ήδη ηχογραφεί" }
        val file = files.newRecordingFile()
        val r = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else @Suppress("DEPRECATION") MediaRecorder()
        r.setAudioSource(MediaRecorder.AudioSource.MIC)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
        r.setAudioEncodingBitRate(96_000)
        r.setAudioSamplingRate(44_100)
        r.setOutputFile(file.absolutePath)
        r.prepare()
        r.start()
        recorder = r
        current = file
        startedAt = SystemClock.elapsedRealtime()
        startSampling(r)
        return file
    }

    fun stop(): Recorded {
        val r = recorder ?: error("Δεν ηχογραφεί")
        val file = current ?: error("Δεν ηχογραφεί")
        val duration = SystemClock.elapsedRealtime() - startedAt
        val loudest = stopSampling(r)
        try {
            r.stop()
        } catch (e: RuntimeException) {
            file.delete()   // stopped too early: nothing usable was written
            throw e
        } finally {
            r.release()
            recorder = null
            current = null
        }
        return Recorded(file, duration, loudest)
    }

    fun cancel() {
        val r = recorder ?: return
        stopSampling(r)
        runCatching { r.stop() }
        r.release()
        current?.delete()
        recorder = null
        current = null
    }

    /**
     * A daemon timer rather than a coroutine: [Recorder] has no scope of its own, and the reading is
     * one cheap call every tenth of a second. The lock is what keeps the tick off a recorder that
     * `stop` has already released — reading `maxAmplitude` after that throws.
     */
    private fun startSampling(r: MediaRecorder) {
        synchronized(peakLock) {
            peak = 0
            // Primes the counter: the first read returns the peak since the recorder started.
            runCatching { r.maxAmplitude }
            val timer = Timer("recorder-peak", true)
            sampler = timer
            timer.schedule(object : TimerTask() {
                override fun run() {
                    synchronized(peakLock) {
                        if (sampler !== timer) return
                        val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
                        if (amp > peak) peak = amp
                    }
                }
            }, PEAK_SAMPLE_MS, PEAK_SAMPLE_MS)
        }
    }

    /** Stops the timer and folds in one last reading, so the tail of the take counts too. */
    private fun stopSampling(r: MediaRecorder): Int = synchronized(peakLock) {
        sampler?.cancel()
        sampler = null
        val amp = runCatching { r.maxAmplitude }.getOrDefault(0)
        if (amp > peak) peak = amp
        peak
    }

    companion object {
        /** Often enough that a single word cannot fall between two readings. */
        const val PEAK_SAMPLE_MS = 100L
    }
}
