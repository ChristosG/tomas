package gr.dimitris.app.core.audio

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

data class Recorded(val file: File, val durationMs: Long)

/** One recording at a time, AAC in an .m4a container. Caller must hold RECORD_AUDIO. */
class Recorder(private val context: Context, private val files: MediaFiles) {
    private var recorder: MediaRecorder? = null
    private var current: File? = null
    private var startedAt = 0L

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
        return file
    }

    fun stop(): Recorded {
        val r = recorder ?: error("Δεν ηχογραφεί")
        val file = current!!
        val duration = SystemClock.elapsedRealtime() - startedAt
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
        return Recorded(file, duration)
    }

    fun cancel() {
        val r = recorder ?: return
        runCatching { r.stop() }
        r.release()
        current?.delete()
        recorder = null
        current = null
    }
}
