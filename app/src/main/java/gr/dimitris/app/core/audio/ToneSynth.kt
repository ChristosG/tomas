package gr.dimitris.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import gr.dimitris.app.modules.singsay.Melody
import gr.dimitris.app.modules.singsay.Pitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Plays a sequence of two-pitch notes and calls [onNote] as each one starts, so the screen can light the syllable. */
class ToneSynth {
    @Volatile private var track: AudioTrack? = null

    suspend fun play(notes: List<Pitch>, noteMs: Int = Melody.NOTE_MS, gapMs: Int = Melody.GAP_MS, gain: Float = 1f, onNote: (Int) -> Unit = {}) {
        if (notes.isEmpty()) return
        stop()
        val pcm = Pcm.concat(notes.flatMap { listOf(Pcm.tone(it.hz, noteMs, gain), Pcm.silence(gapMs)) })
        val t = withContext(Dispatchers.IO) {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Pcm.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { it.write(pcm, 0, pcm.size) }
        }
        track = t
        try {
            t.play()
            for (i in notes.indices) {
                if (track !== t) return
                onNote(i)
                delay((noteMs + gapMs).toLong())
            }
        } finally {
            if (track === t) { runCatching { t.stop() }; t.release(); track = null }
        }
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
