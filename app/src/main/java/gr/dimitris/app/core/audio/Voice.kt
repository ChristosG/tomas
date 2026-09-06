package gr.dimitris.app.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import gr.dimitris.app.core.speech.TextToSpeech
import gr.dimitris.app.modules.singsay.Key
import gr.dimitris.app.modules.singsay.Melody
import gr.dimitris.app.modules.singsay.Pitch
import java.io.File

/**
 * The single owner of everything that makes or takes sound. Screens and view models call this and
 * nothing else, so the rule "only one thing at a time" lives in one place instead of being repeated
 * as cross-stop calls at every call site.
 *
 * Every operation silences the outputs first. A recording in progress is deliberately left running:
 * asking to record while already recording is a bug, and [Recorder] says so in Greek. Asking to
 * speak or play while it records is the same kind of bug — the microphone would hear the answer —
 * so those refuse in Greek instead of talking over the take.
 *
 * Audio focus is advisory and best effort — a refused request never blocks Dimitris from being
 * heard, so failures are ignored and the operation goes ahead anyway.
 */
class Voice(
    context: Context,
    private val tts: TextToSpeech,
    private val player: Player,
    private val recorder: Recorder,
    private val synth: ToneSynth,
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

    /**
     * Stops anything being said or played — the melody included. Safe when nothing is running;
     * never touches a recording.
     *
     * The melody is here and not left to its caller because every `onDispose { voice.quiet() }` in
     * the app is a promise that leaving a screen leaves it silent: a tone still sounding under the
     * talk board is the same bug as an utterance still speaking under it.
     */
    fun quiet() {
        tts.stop()
        player.stop()
        synth.stop()
    }

    suspend fun speak(text: String, rate: Float): Result<Unit> {
        if (isRecording) return Result.failure(IllegalStateException(RECORDING_NOW))
        quiet()
        return try {
            holdOutputFocus()
            tts.speak(text, rate)
        } finally {
            releaseOutputFocus()
        }
    }

    suspend fun play(file: File): Result<Unit> {
        if (isRecording) return Result.failure(IllegalStateException(RECORDING_NOW))
        quiet()
        return try {
            holdOutputFocus()
            player.play(file)
        } finally {
            releaseOutputFocus()
        }
    }

    /**
     * The two-note melody of "Τραγούδα και πες το", played through the same door as everything else
     * that makes sound: it takes the transient focus so another app's music ducks under it, it is
     * refused while the microphone is open, and [quiet] stops it. [key] is a caregiver setting, not
     * a property of [notes] — the same [Pitch] list sings in whichever register [key] names.
     */
    suspend fun playMelody(
        notes: List<Pitch>,
        noteMs: Int = Melody.NOTE_MS,
        gapMs: Int = Melody.GAP_MS,
        gain: Float = 1f,
        key: Key = Key.NORMAL,
        onNote: (Int) -> Unit = {},
    ): Result<Unit> {
        if (isRecording) return Result.failure(IllegalStateException(RECORDING_NOW))
        quiet()
        return try {
            holdOutputFocus()
            synth.play(notes, noteMs, gapMs, gain, key, onNote)
        } finally {
            releaseOutputFocus()
        }
    }

    /**
     * Throws (in Greek) if a recording is already running. The refusal is checked here, before any
     * focus is touched, because the abandon below would otherwise drop the focus the running take
     * holds — a second start must leave the first recording exactly as it was.
     *
     * Otherwise focus is taken before the recorder starts, so the first millisecond is not another
     * app's music, and handed straight back if the recorder never starts.
     */
    fun startRecording(): File {
        if (isRecording) throw IllegalStateException(ALREADY_RECORDING)
        quiet()
        audio.requestAudioFocus(recordFocus)
        return try {
            recorder.start()
        } catch (e: Throwable) {
            audio.abandonAudioFocusRequest(recordFocus)
            throw e
        }
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

    /** Counted only once the request itself has returned, so a throwing request leaves no phantom holder. */
    private fun holdOutputFocus() {
        synchronized(this) {
            if (outputHolders == 0) audio.requestAudioFocus(outputFocus)
            outputHolders++
        }
    }

    /** The floor at zero matters: the paired hold may have thrown before it ever counted. */
    private fun releaseOutputFocus() {
        synchronized(this) { if (outputHolders > 0 && --outputHolders == 0) audio.abandonAudioFocusRequest(outputFocus) }
    }

    private fun focusRequest(gain: Int) = AudioFocusRequest.Builder(gain).setAudioAttributes(attributes).build()

    companion object {
        /** Said when output is asked for mid-recording: the microphone is open and must stay clean. */
        const val RECORDING_NOW = "Ηχογραφεί τώρα"

        /** The same words [Recorder] uses, so a refusal reads the same wherever it is raised. */
        const val ALREADY_RECORDING = "Ήδη ηχογραφεί"
    }
}
