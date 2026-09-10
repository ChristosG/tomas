package gr.dimitris.app.core.speech

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The rule [AndroidSpeechToText.listen] holds the microphone by, pinned on its own.
 *
 * `NonCancellable` keeps a block from being cancelled. It does **not** make the hand-back
 * uncancellable: a `withContext` that changes dispatcher resumes its caller through the cancellable
 * path, so a job cancelled meanwhile takes the exception and the block's return value is thrown
 * away. Written as `handle = withContext(NonCancellable + Dispatchers.IO) { open() }` the assignment
 * therefore never happens — and what was opened is unreachable.
 *
 * For `open()` = "an `AudioRecord` on `VOICE_RECOGNITION` plus two threads and a WAV growing at
 * 32 kB/s", unreachable means it never stops. That was the shape of the bug twice over: once in the
 * code, and once in the fix that was supposed to close it. It is here as a plain JVM test because
 * the assumption behind it is the kind that comes back, and because on a device it is invisible —
 * the app looks idle while the microphone stays hot.
 *
 * Two cases, and the difference between them is one line.
 */
class CancelledHandoffTest {

    /** Something that must be closed once it exists, standing in for a `PcmTake`. */
    private class Resource {
        var closed = false
            private set
        fun close() { closed = true }
    }

    @Test fun `a value returned across a dispatcher is lost when the caller was cancelled`() = runBlocking {
        var built: Resource? = null
        var held: Resource? = null
        val opening = CountDownLatch(1)
        val cancelling = CountDownLatch(1)

        // A different dispatcher from the caller's, which is what makes the resume cancellable. The
        // instrumented test that was supposed to cover this used the same one and took the
        // undispatched path — a plain resume, no cancellation check — so it could never have caught it.
        val job = CoroutineScope(Dispatchers.Default).launch {
            held = withContext(NonCancellable + Dispatchers.IO) {
                opening.countDown()
                cancelling.await(TIMEOUT_S, TimeUnit.SECONDS)
                Resource().also { built = it }
            }
        }
        opening.await(TIMEOUT_S, TimeUnit.SECONDS)
        // Cancel while the block is running, then let it finish.
        job.cancel()
        cancelling.countDown()
        job.join()

        assertNotNull("`NonCancellable` did keep the block running", built)
        assertNull("and the caller never got what it built", held)
    }

    /** The same run, with the assignment moved inside the block. This is what the app does. */
    @Test fun `a field written inside the block is written whatever happens to the caller`() = runBlocking {
        var built: Resource? = null
        var held: Resource? = null
        val opening = CountDownLatch(1)
        val cancelling = CountDownLatch(1)

        val job = CoroutineScope(Dispatchers.Default).launch {
            try {
                withContext(NonCancellable + Dispatchers.IO) {
                    opening.countDown()
                    cancelling.await(TIMEOUT_S, TimeUnit.SECONDS)
                    held = Resource().also { built = it }
                }
                // The cancellation lands here, exactly as `ensureActive()` lets it in `listen()`.
                delay(TIMEOUT_S * 1_000)
            } finally {
                held?.close()
            }
        }
        opening.await(TIMEOUT_S, TimeUnit.SECONDS)
        job.cancel()
        cancelling.countDown()
        job.join()

        assertEquals("the caller is holding what it built", built, held)
        assertEquals("so the `finally` could close it", true, held?.closed)
    }

    /** And with nothing cancelling it, both shapes are fine — the hazard is the cancellation. */
    @Test fun `an uncancelled caller gets its value either way`() = runBlocking {
        var held: Resource? = null
        val job = CoroutineScope(Dispatchers.Default).launch {
            held = withContext(NonCancellable + Dispatchers.IO) { Resource() }
        }
        job.join()
        assertNotNull(held)
        job.cancelAndJoin()
    }

    private companion object {
        const val TIMEOUT_S = 5L
    }
}
