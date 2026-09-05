package gr.dimitris.app.ui.theme

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.staticCompositionLocalOf

/** The three signals of the app. Success is always icon + sound + haptic, never colour alone. */
class Feedback(context: Context) {
    /** The tone generator refuses to open when the audio hardware is busy; the app must still run, silently. */
    private val tones: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull()
    private val vibrator: Vibrator? = context.getSystemService(Vibrator::class.java)

    fun success() {
        tones?.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        vibrate(longArrayOf(0, 40, 60, 40))
    }

    fun nudge() {
        tones?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
        vibrate(longArrayOf(0, 30))
    }

    fun tap() {
        vibrator?.vibrate(VibrationEffect.createOneShot(15, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrate(pattern: LongArray) {
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }
}

val LocalFeedback = staticCompositionLocalOf<Feedback> { error("Feedback not provided") }
