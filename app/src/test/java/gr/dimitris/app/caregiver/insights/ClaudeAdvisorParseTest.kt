package gr.dimitris.app.caregiver.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splitting the answer in two is the only part of the advisor that has to be right without a
 * network, and it is the part that decides what the phone reads out loud to Dimitris. Every case
 * where the split cannot be trusted has to end with his half empty.
 */
class ClaudeAdvisorParseTest {

    private fun answer(caregivers: String, dimitris: String) =
        "${ClaudeAdvisor.CAREGIVERS}\n$caregivers\n\n${ClaudeAdvisor.DIMITRIS}\n$dimitris\n"

    @Test fun `both sections come back trimmed and in the right halves`() {
        val advice = ClaudeAdvisor.parse(answer("- Δούλεψε τα ψώνια.\n- Τραγουδήστε μαζί.", "Πάει καλά. Συνέχισε έτσι."))

        assertEquals("- Δούλεψε τα ψώνια.\n- Τραγουδήστε μαζί.", advice.caregivers)
        assertEquals("Πάει καλά. Συνέχισε έτσι.", advice.dimitris)
    }

    @Test fun `preamble before the first heading is dropped`() {
        val advice = ClaudeAdvisor.parse("Βεβαίως, ορίστε.\n\n" + answer("- Ένα.", "Μπράβο."))

        assertEquals("- Ένα.", advice.caregivers)
        assertEquals("Μπράβο.", advice.dimitris)
    }

    @Test fun `no markers at all means the caregivers get everything and he gets nothing`() {
        val advice = ClaudeAdvisor.parse("  Μια απάντηση χωρίς τίτλους.  ")

        assertEquals("Μια απάντηση χωρίς τίτλους.", advice.caregivers)
        assertEquals("", advice.dimitris)
    }

    @Test fun `one missing marker means he gets nothing`() {
        val onlyCaregivers = ClaudeAdvisor.parse("${ClaudeAdvisor.CAREGIVERS}\n- Ένα.")
        assertTrue(onlyCaregivers.caregivers.contains("- Ένα."))
        assertEquals("", onlyCaregivers.dimitris)

        val onlyDimitris = ClaudeAdvisor.parse("${ClaudeAdvisor.DIMITRIS}\nΜπράβο.")
        assertTrue(onlyDimitris.caregivers.contains("Μπράβο."))
        assertEquals("", onlyDimitris.dimitris)
    }

    /** Out of order is a mangled answer, not a swap: nothing gets read to him. */
    @Test fun `the sections in the wrong order are not split`() {
        val text = "${ClaudeAdvisor.DIMITRIS}\nΜπράβο.\n\n${ClaudeAdvisor.CAREGIVERS}\n- Ένα."
        val advice = ClaudeAdvisor.parse(text)

        assertEquals(text.trim(), advice.caregivers)
        assertEquals("", advice.dimitris)
    }

    @Test fun `an empty section for him stays empty rather than becoming whitespace`() {
        val advice = ClaudeAdvisor.parse(answer("- Ένα.", "   "))

        assertEquals("- Ένα.", advice.caregivers)
        assertEquals("", advice.dimitris)
    }

    /** The prompt has to keep asking for the exact headings the parser looks for. */
    @Test fun `the system prompt names both headings`() {
        assertTrue(ClaudeAdvisor.SYSTEM_PROMPT.contains(ClaudeAdvisor.CAREGIVERS))
        assertTrue(ClaudeAdvisor.SYSTEM_PROMPT.contains(ClaudeAdvisor.DIMITRIS))
    }
}
