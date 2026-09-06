package gr.dimitris.app.caregiver.insights

import gr.dimitris.app.core.secrets.Secrets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * The one test that spends money, and only on a machine that already has a key in its environment.
 * Everywhere else — the emulator, a fresh clone, CI without the variable — it is skipped, which is
 * why the assume is the first line of the test and not a condition around the class.
 *
 * It exists because everything else about the advisor can be right while the request itself is
 * wrong: a model id that no longer exists, a thinking config the API rejects, a prompt that stops
 * producing the two headings. Those only ever show up against the real API.
 */
class ClaudeAdvisorLiveTest {

    @Test fun `a real question comes back with something for the caregivers`() {
        val key = System.getenv("ANTHROPIC_API_KEY")
        assumeTrue("ANTHROPIC_API_KEY not set — skipping the live call", key != null)

        val advisor = ClaudeAdvisor(Secrets { key }) { "claude-opus-5" }
        val result = runBlocking { advisor.ask(SUMMARY) }

        val advice = result.getOrNull()
        assertTrue("${result.exceptionOrNull()?.message}", advice != null)
        assertTrue("caregivers section was empty", advice!!.caregivers.isNotBlank())
    }

    private companion object {
        /** Deliberately tiny, and made up: nothing of Dimitris' own goes over the wire in a test. */
        val SUMMARY = """
            Πρόοδος — Δημήτρης
            Περίοδος: 1/9/2026 – 5/9/2026 (5 μέρες)

            Σύνολο: 40 λεπτά, 60 ασκήσεις
            Σερί: 3 μέρες
            Μαθημένες λέξεις: 4

            Ανά άσκηση
            - Λέξεις: 40 ασκήσεις, 70% σωστά (σωστά 28, με βοήθεια 8, προσπέρασε 4)
            - Αριθμοί: 20 ασκήσεις, 50% σωστά (σωστά 10, με βοήθεια 4, προσπέρασε 6)

            Δύσκολες λέξεις (τις προσπερνά)
            - ψωμί: 4 φορές

            Επίπεδα
            - Αριθμοί (1–7): 2

            Τι βλέπω
            - Σερί 3 ημερών. Συνέχισε έτσι!
        """.trimIndent()
    }
}
