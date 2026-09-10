package gr.dimitris.app.core.audio

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

/**
 * The short list of files retention has finished with and sync has not yet let go of.
 *
 * It exists because deleting the file in the same breath as the row broke the half that matters: a
 * row travels as `media://<sha>` only while its bytes are there to be hashed, so a deletion whose
 * file had already gone went out naming this phone's own `recordings/<uuid>.wav` — which the other
 * phone had never heard of, and it kept its copy for ever.
 */
class PendingRemovalsTest {
    private val dir = createTempDirectory("pending").toFile()
    private val list = PendingRemovals(File(dir, "pending.tsv"))

    @After fun clean() { dir.deleteRecursively() }

    private fun file(name: String): File = File(dir, name).apply { writeBytes(ByteArray(8)) }
    private fun resolve(path: String) = File(path)

    @Test fun `a file waits until its deletion has been pushed`() {
        val take = file("1.wav")
        list.add(take.absolutePath, at = 100)

        // A push that only got as far as rows stamped 50 leaves it where it is: the other phone has
        // not been told yet, and the bytes are the only thing that will let it find its own copy.
        assertEquals(0, list.release(upTo = 50, resolve = ::resolve))
        assertTrue(take.isFile)

        assertEquals(1, list.release(upTo = 100, resolve = ::resolve))
        assertTrue("the file went with the push", !take.exists())
        assertEquals("and the list forgot it", emptyList<PendingRemovals.Waiting>(), list.all())
    }

    @Test fun `only what the push reached`() {
        val early = file("1.wav")
        val late = file("2.wav")
        list.add(early.absolutePath, at = 10)
        list.add(late.absolutePath, at = 90)

        assertEquals(1, list.release(upTo = 50, resolve = ::resolve))

        assertTrue(!early.exists())
        assertTrue("the later one is still waiting for its own push", late.isFile)
        assertEquals(listOf(late.absolutePath), list.all().map { it.path })
    }

    /** It survives being killed mid-sitting, which is the only reason it is a file at all. */
    @Test fun `the list is read back from disk`() {
        list.add(file("1.wav").absolutePath, at = 10)
        list.add(file("2.wav").absolutePath, at = 20)

        val reopened = PendingRemovals(File(dir, "pending.tsv"))

        assertEquals(2, reopened.all().size)
        assertEquals(listOf(10L, 20L), reopened.all().map { it.since })
    }

    /** The same take pruned twice is one entry, not two. */
    @Test fun `the same path is recorded once, with its latest moment`() {
        val take = file("1.wav")
        list.add(take.absolutePath, at = 10)
        list.add(take.absolutePath, at = 40)

        assertEquals(1, list.all().size)
        assertEquals(40L, list.all().single().since)
    }

    /** A file that has already gone — a restored backup, a caregiver clearing storage — is done. */
    @Test fun `a file that is already gone counts as removed`() {
        list.add(File(dir, "never-existed.wav").absolutePath, at = 10)

        assertEquals(1, list.release(upTo = 10, resolve = ::resolve))
        assertEquals(emptyList<PendingRemovals.Waiting>(), list.all())
    }

    /** Nothing here is his work: a list that cannot be read is an empty list, and that is all. */
    @Test fun `a list that makes no sense is an empty list`() {
        File(dir, "pending.tsv").writeText("not a record\n\t\n123\n")
        assertEquals(emptyList<PendingRemovals.Waiting>(), list.all())
        assertEquals(0, list.release(upTo = Long.MAX_VALUE, resolve = ::resolve))
    }

    @Test fun `nothing waiting is nothing done`() {
        assertEquals(0, list.release(upTo = Long.MAX_VALUE, resolve = ::resolve))
        assertEquals(emptyList<PendingRemovals.Waiting>(), list.all())
    }

    @Test fun `clear forgets everything`() {
        val take = file("1.wav")
        list.add(take.absolutePath, at = 10)
        list.clear()
        assertEquals(emptyList<PendingRemovals.Waiting>(), list.all())
        assertTrue("forgetting is not deleting", take.isFile)
    }

    /** A week, for a phone that has no server to push to and never will. */
    @Test fun `an unsynced phone waits a week`() =
        assertEquals(7L * 24 * 60 * 60 * 1000, PendingRemovals.UNSYNCED_MS)
}
