package gr.dimitris.app.core.audio

import android.media.MediaPlayer
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/** Plays one file at a time and suspends until it ends. */
class Player {
    private var player: MediaPlayer? = null

    suspend fun play(file: File): Result<Unit> = suspendCancellableCoroutine { cont ->
        stop()
        val p = MediaPlayer()
        player = p
        p.setOnCompletionListener { release(p); if (cont.isActive) cont.resume(Result.success(Unit)) }
        p.setOnErrorListener { _, what, extra ->
            release(p)
            if (cont.isActive) cont.resume(Result.failure(IOException("MediaPlayer error $what/$extra")))
            true
        }
        try {
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.start()
        } catch (e: Exception) {
            release(p)
            if (cont.isActive) cont.resume(Result.failure(e))
        }
        cont.invokeOnCancellation { stop() }
    }

    fun stop() {
        player?.let { runCatching { if (it.isPlaying) it.stop() }; it.release() }
        player = null
    }

    private fun release(p: MediaPlayer) {
        p.release()
        if (player === p) player = null
    }
}
