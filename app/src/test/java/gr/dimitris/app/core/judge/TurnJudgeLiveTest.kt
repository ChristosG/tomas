package gr.dimitris.app.core.judge

import gr.dimitris.app.core.secrets.Secrets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The second test in this app that spends money, and like the first it runs only on a machine that
 * already has a key in its environment. Everywhere else — the emulator, a fresh clone, CI without the
 * variable — it is skipped, which is why the assume is the first line of the test and not a condition
 * around the class.
 *
 * It exists because everything else can be right while the request itself is wrong: a model id that
 * is no longer served, a params shape the API rejects, a prompt that has stopped producing JSON. None
 * of those show up against a fake client, and all of them would turn every turn in the app into a
 * silent local match — which is the failure mode nobody would notice, because by design it looks
 * exactly like the app working.
 *
 * The turn asked is the one §13 is about: a telegraphic answer to an open question. It must come back
 * accepted **and** with the full sentence, because that pair is the therapy.
 */
class TurnJudgeLiveTest {

    @Test fun `a real telegraphic answer comes back accepted, with the whole sentence`() {
        val key = System.getenv("ANTHROPIC_API_KEY")
        // Blank as well as absent: `ANTHROPIC_API_KEY=""` would otherwise pass the guard, be read as
        // "no key" by the judge, and fail here as «the judge fell back to local matching» — a failure
        // about the environment dressed up as a failure about the prompt.
        assumeTrue("ANTHROPIC_API_KEY not set — skipping the live call", !key.isNullOrBlank())

        val judge = TurnJudge(Secrets { key }, enabled = { true })
        val ask = Ask(
            kind = Kind.DIALOGUE,
            prompt = "Τι θα πάρεις από το φαρμακείο;",
            heard = "φάρμακα πρέπει πάρω",
            difficulty = 3,
        )

        val v = runBlocking { judge.judge(ask) }

        assertEquals("the judge fell back to local matching", Source.JUDGE, v.source)
        assertTrue("a relevant answer must be accepted", v.accept)
        assertNotNull("a telegraphic answer must come back with the full sentence", v.expanded)
        assertTrue("the expansion was blank", v.expanded!!.isNotBlank())
    }
}
