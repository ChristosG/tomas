package gr.dimitris.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import gr.dimitris.app.core.speech.TextToSpeech
import java.io.File

/**
 * The single owner of everything that makes or takes sound. Screens and view models call this and
 * nothing else, so the rule "only one thing at a time" lives in one place instead of being repeated
 * as cross-stop calls at every call site.
 *
 * Every operation silences the outputs first. A recording in progress is deliberately left running:
 * asking to record while already recording is a bug, and [Recorder] says so in Greek.
 *
 * Audio focus is advisory and best effort — a refused request never blocks Dimitris from being
 * heard, so failures are ignored and the operation goes ahead anyway.
 */
class Voice(
    context: Context,
    private val tts: TextToSpeech,
    private val player: Player,
    private val recorder: Recorder,
) {
    private val audio: AudioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    /** Assistive speech: it is Dimitris' voice, not media. */
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val outputFocus = focusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
    private val recordFocus = focusRequest(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)

    /**
     * How many speak/play calls are inside their try/finally. A new utterance interrupts the old
     * one, and the old one's `finally` may land afterwards; counting keeps that late abandon from
     * dropping the focus the new utterance just took.
     */
    private var outputHolders = 0

    val isRecording: Boolean get() = recorder.isRecording

    /** Stops anything being said or played. Safe when nothing is running; never touches a recording. */
    fun quiet() {
        tts.stop()
        player.stop()
    }

    suspend fun speak(text: String, rate: Float): Result<Unit> {
        quiet()
        holdOutputFocus()
        return try {
            tts.speak(text, rate)
        } finally {
            releaseOutputFocus()
        }
    }

    suspend fun play(file: File): Result<Unit> {
        quiet()
        holdOutputFocus()
        return try {
            player.play(file)
        } finally {
            releaseOutputFocus()
        }
    }

    /** Throws (in Greek) if a recording is already running — focus is taken only once one starts. */
    fun startRecording(): File {
        quiet()
        val file = recorder.start()
        audio.requestAudioFocus(recordFocus)
        return file
    }

    fun stopRecording(): Recorded = try {
        recorder.stop()
    } finally {
        audio.abandonAudioFocusRequest(recordFocus)
    }

    fun cancelRecording() {
        try {
            recorder.cancel()
        } finally {
            audio.abandonAudioFocusRequest(recordFocus)
        }
    }

    private fun holdOutputFocus() {
        synchronized(this) { if (outputHolders++ == 0) audio.requestAudioFocus(outputFocus) }
    }

    private fun releaseOutputFocus() {
        synchronized(this) { if (--outputHolders == 0) audio.abandonAudioFocusRequest(outputFocus) }
    }

    private fun focusRequest(gain: Int) = AudioFocusRequest.Builder(gain).setAudioAttributes(attributes).build()
}
