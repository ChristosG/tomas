package gr.dimitris.app.core.audio

import android.media.MediaPlayer
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume

/** Plays one file at a time and suspends until it ends. Starting a new file ends the previous caller's wait with success. */
class Player {
    /** Read outside the lock by stop() and by the cancellation callback, so it must not be cached. */
    @Volatile private var player: MediaPlayer? = null
    private var waiting: CancellableContinuation<Result<Unit>>? = null

    suspend fun play(file: File): Result<Unit> = suspendCancellableCoroutine { cont ->
        stop()
        val p = MediaPlayer()
        synchronized(this) { player = p; waiting = cont }
        p.setOnCompletionListener { finish(p, Result.success(Unit)) }
        p.setOnErrorListener { _, what, extra ->
            finish(p, Result.failure(IOException("Σφάλμα αναπαραγωγής $what/$extra")))
            true
        }
        try {
            p.setDataSource(file.absolutePath)
            p.prepare()
            p.start()
        } catch (e: Exception) {
            finish(p, Result.failure(e))
        }
        cont.invokeOnCancellation { if (player === p) finish(p, Result.success(Unit)) }
    }

    /** Stops whatever is playing. An interrupted caller gets success, like an interrupted utterance. */
    fun stop() {
        val p = player ?: return
        finish(p, Result.success(Unit))
    }

    /** Exactly one finish per player: the first caller to pass the identity check releases it and resumes the waiter. */
    private fun finish(p: MediaPlayer, result: Result<Unit>) {
        val cont: CancellableContinuation<Result<Unit>>?
        synchronized(this) {
            if (player !== p) return
            player = null
            cont = waiting
            waiting = null
        }
        runCatching { if (p.isPlaying) p.stop() }
        p.release()
        if (cont != null && cont.isActive) cont.resume(result)
    }
}
