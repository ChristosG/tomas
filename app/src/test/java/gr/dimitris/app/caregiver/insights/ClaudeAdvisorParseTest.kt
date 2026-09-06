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

        // Everything to the caregivers, with the model's own `##` taken off for reading.
        assertTrue(advice.caregivers, advice.caregivers.contains("Μπράβο."))
        assertTrue(advice.caregivers, advice.caregivers.contains("- Ένα."))
        assertEquals("", advice.dimitris)
    }

    /**
     * The prompt names both headings, so a model writing «διάβασέ του την ενότητα ## Για τον
     * Δημήτρη» *inside* the caregivers' advice is a plausible sentence — and with a plain `indexOf`
     * the split landed on that mention, handing the rest of the caregivers' text to the screen and
     * to the speaker as though it had been written for him.
     */
    @Test fun `a heading named inside a sentence is not a heading`() {
        val text = ClaudeAdvisor.CAREGIVERS + "\n" +
            "- Δούλεψε τα ψώνια.\n" +
            "- Στο τέλος διάβασέ του την ενότητα " + ClaudeAdvisor.DIMITRIS + ".\n\n" +
            ClaudeAdvisor.DIMITRIS + "\n" +
            "Πάει καλά."

        val advice = ClaudeAdvisor.parse(text)

        assertEquals("Πάει καλά.", advice.dimitris)
        assertTrue(advice.caregivers, advice.caregivers.contains("Δούλεψε τα ψώνια."))
        assertTrue(advice.caregivers, advice.caregivers.contains("διάβασέ του την ενότητα"))
    }

    /** Two real heading lines: the last one is the one the answer is actually written under. */
    @Test fun `the last heading line wins`() {
        val text = answer("- Πρόχειρο.", "Πρόχειρο.") + "\n" + answer("- Το κανονικό.", "Το κανονικό.")

        val advice = ClaudeAdvisor.parse(text)

        assertEquals("- Το κανονικό.", advice.caregivers)
        assertEquals("Το κανονικό.", advice.dimitris)
    }

    /** A heading with a colon, or trailing spaces, is still a heading. */
    @Test fun `a heading with a colon still splits`() {
        val advice = ClaudeAdvisor.parse(
            ClaudeAdvisor.CAREGIVERS + ":\n- Ένα.\n\n" + ClaudeAdvisor.DIMITRIS + ":  \nΜπράβο."
        )

        assertEquals("- Ένα.", advice.caregivers)
        assertEquals("Μπράβο.", advice.dimitris)
    }

    /**
     * The prompt asks for plain text; models emit markdown anyway. The screen renders it literally
     * and TextToSpeech reads an asterisk out loud at a man who cannot ask what it means.
     */
    @Test fun `markdown is taken off both halves`() {
        val advice = ClaudeAdvisor.parse(
            answer("### Την Δευτέρα\n- **Ψώνια** μαζί.", "**Μπράβο!** Συνέχισε.")
        )

        assertEquals("Την Δευτέρα\n- Ψώνια μαζί.", advice.caregivers)
        assertEquals("Μπράβο! Συνέχισε.", advice.dimitris)
    }

    @Test fun `an empty section for him stays empty rather than becoming whitespace`() {
        val advice = ClaudeAdvisor.parse(answer("- Ένα.", "   "))

        assertEquals("- Ένα.", advice.caregivers)
        assertEquals("", advice.dimitris)
    }

    /**
     * The prompt asks for two sentences. This is what happens when it does not get them: a wall of
     * text read at 0.8× to a man with expressive aphasia is not advice, and past about 4 000
     * characters Android's TextToSpeech refuses outright and he gets an error instead of a voice.
     */
    @Test fun `his half is capped, and cut at the end of a sentence`() {
        val sentence = "Πάει καλά και συνεχίζεις. "
        val wall = sentence.repeat(60)                      // ~1 500 characters
        val advice = ClaudeAdvisor.parse(answer("- Ένα.", wall))

        assertTrue("${advice.dimitris.length}", advice.dimitris.length <= ClaudeAdvisor.MAX_DIMITRIS)
        assertTrue(advice.dimitris, advice.dimitris.endsWith("."))
        assertTrue(advice.dimitris, advice.dimitris.startsWith("Πάει καλά"))
        // The caregivers' half is not capped: they read at their own pace, on a screen.
        assertEquals("- Ένα.", advice.caregivers)
    }

    @Test fun `a two-sentence answer is left exactly as it was written`() {
        val two = "Πάει πολύ καλά. Συνέχισε έτσι."
        assertEquals(two, ClaudeAdvisor.parse(answer("- Ένα.", two)).dimitris)
    }

    /** No sentence end late enough to keep: a plain cut beats handing him a three-word stub. */
    @Test fun `one endless sentence is cut plainly rather than to a stub`() {
        val endless = "Ναι. " + "και ".repeat(400)
        val capped = ClaudeAdvisor.parse(answer("- Ένα.", endless)).dimitris

        assertTrue("${capped.length}", capped.length <= ClaudeAdvisor.MAX_DIMITRIS)
        assertTrue("${capped.length}", capped.length > ClaudeAdvisor.MAX_DIMITRIS / 2)
    }

    /** The prompt has to keep asking for the exact headings the parser looks for. */
    @Test fun `the system prompt names both headings`() {
        assertTrue(ClaudeAdvisor.SYSTEM_PROMPT.contains(ClaudeAdvisor.CAREGIVERS))
        assertTrue(ClaudeAdvisor.SYSTEM_PROMPT.contains(ClaudeAdvisor.DIMITRIS))
    }
}
