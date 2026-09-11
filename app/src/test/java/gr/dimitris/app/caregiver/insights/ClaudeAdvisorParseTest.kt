package gr.dimitris.app.caregiver.insights

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Splitting the answer in two is the only part of the advisor that has to be right without a
 * network, and it is the part that decides what the phone reads out loud to Dimitris. Every case
 * where the split cannot be trusted has to end with his half empty.
 */
class ClaudeAdvisorParseTest {

    /** One line, so an assertion is about the words and not about where the wrapping fell. */
    private fun flowing(text: String) = text.replace(Regex("\\s+"), " ")

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
    @Test fun `the system prompt names all three headings`() {
        assertTrue(ClaudeAdvisor.SYSTEM_PROMPT.contains(ClaudeAdvisor.CAREGIVERS))
        assertTrue(ClaudeAdvisor.SYSTEM_PROMPT.contains(ClaudeAdvisor.DIMITRIS))
        assertTrue(ClaudeAdvisor.SYSTEM_PROMPT.contains(ClaudeAdvisor.FOCUS))
    }

    /** Phase 11's prompt: an SLT-informed coach, a comparison with last time, and a focus. */
    @Test fun `the system prompt asks for what phase 11 needs`() {
        val p = flowing(ClaudeAdvisor.SYSTEM_PROMPT)
        assertTrue("not a doctor", p.contains("όχι ως γιατρός"))
        assertTrue("compare with the previous advices", p.contains("προηγούμενες συμβουλές"))
        assertTrue("one JSON object on one line", p.contains("σε μία γραμμή"))
    }

    /**
     * The one thing that would make a model give the wrong advice from right numbers. «Άκου» is on
     * every screen by design (errorless learning) and pressing it forces the recorded help to 3, so
     * a mean of 2,4 can be a man choosing to listen rather than a man going backwards — and the
     * conclusion a prompt without this invites is "stop giving him the model", which is the one
     * recommendation the app must never carry.
     */
    @Test fun `the prompt says that listening is encouraged and raises the recorded help`() {
        // Whitespace-normalised: the prompt is a wrapped raw string and the model reads it as
        // flowing text, so a sentence that happens to straddle a line break is still that sentence.
        val p = flowing(ClaudeAdvisor.SYSTEM_PROMPT)
        assertTrue(p, p.contains("τον ενθαρρύνουμε να το πατάει"))
        assertTrue(p, p.contains("γράφει βοήθεια τουλάχιστον 3"))
        assertTrue(p, p.contains("δεν είναι από μόνη της οπισθοδρόμηση"))
        assertTrue("and it must say so outright", p.contains("Μην προτείνεις ποτέ να του στερήσουν το"))
    }

    /** The JSON wants ids and the report speaks Greek; the pairing has to be in the prompt. */
    @Test fun `the prompt pairs every Greek module name with its id`() {
        val p = flowing(ClaudeAdvisor.SYSTEM_PROMPT)
        listOf(
            "Λέξεις = WORDCOACH", "Αριθμοί = NUMBERS", "Τραγούδα και πες το = SINGSAY",
            "Διάλογοι = SCRIPTS", "Προτάσεις = SENTENCES", "Γράψε = TRACE", "Δεξί χέρι = ARCADE",
            "Μίλα = TALKBOARD",
        ).forEach { assertTrue(it, p.contains(it)) }
        assertTrue("and which level keys exist", p.contains("μόνο numbers, sentences, trace, sql και steps"))
    }

    /**
     * **What a «Λέξεις» dot really selects.** The line used to say «1 μόνο λέξεις· 2 έως 5 λέξεις και
     * φράσεις», which was true before phase 13 and is now the opposite of the thing the phase was for:
     * it told the advisor that dots 2 to 5 all select the same two hundred words, so the advisor had no
     * content reason ever to move that dot — and «the app is too easy» was half that dot's fault.
     *
     * Five tiers, and the prompt names each of them ([Difficulty.wordCoachTier]).
     */
    @Test fun `the prompt says what each vocabulary tier is`() {
        val p = flowing(ClaudeAdvisor.SYSTEM_PROMPT)
        assertTrue("the tier ceiling", p.contains("η κουκκίδα n παίρνει τις λέξεις μέχρι και τη δυσκολία n"))
        assertTrue("the long everyday words", p.contains("φαρμακείο, τράπεζα"))
        assertTrue("the verbs and the opinion adjectives", p.contains("τα ρήματα και τα επίθετα της γνώμης"))
        assertTrue("the abstract nouns", p.contains("οι αφηρημένες λέξεις"))
        assertFalse("the pre-phase-13 line is still there", p.contains("1 μόνο λέξεις"))
        // And the two new tiles' own keys, which are dots rather than levels.
        assertTrue("the dot is the number for both", p.contains("Στο sql και στο steps ο αριθμός είναι η ίδια η κουκκίδα"))
    }

    /**
     * Phase 12's profile, which is spec §13: he tried the app and said it was too easy, and the
     * advice has to be given to the man who said that rather than to the one §1 alone describes.
     */
    @Test fun `the prompt describes him as he is since he told us`() {
        val p = flowing(ClaudeAdvisor.SYSTEM_PROMPT)
        assertTrue("says most everyday words", p.contains("Λέει τις περισσότερες καθημερινές λέξεις") ||
            p.contains("λέει τις περισσότερες καθημερινές λέξεις"))
        assertTrue("reads Greek slowly", p.contains("διαβάζει ελληνικά αργά"))
        assertTrue("telegraphic", p.contains("τηλεγραφικός"))
        assertTrue("full sentences and multi-step tasks are the goal",
            p.contains("ολόκληρες προτάσεις και οι εργασίες με πολλά βήματα"))
    }

    /**
     * Spec §13's privacy rule, written into the one text that decides what a language model believes
     * about him: §1 and §13 and nothing else, and no inferring the rest from the numbers or from a
     * caregiver's note.
     */
    @Test fun `the prompt forbids saying anything else about his health`() {
        val p = flowing(ClaudeAdvisor.SYSTEM_PROMPT)
        assertTrue(p, p.contains("Αυτά είναι όλα όσα ξέρεις για την υγεία του"))
        assertTrue(p, p.contains("Μη συμπεράνεις"))
        assertTrue("no diagnosis", p.contains("Καμία ιατρική διάγνωση"))
    }

    /** The dots are the phase's whole point, so the advisor has to know what one means. */
    @Test fun `the prompt explains the five dots and asks which one to set next`() {
        val p = flowing(ClaudeAdvisor.SYSTEM_PROMPT)
        assertTrue("the row of five", p.contains("πέντε κουκκίδες, 1 έως 5"))
        assertTrue("he sets them", p.contains("Τις πατάει ο ίδιος ο Δημήτρης"))
        assertTrue("the caregiver bounds them", p.contains("κάτω και πάνω όριο"))
        assertTrue("a dot picks a band", p.contains("Η κουκκίδα διαλέγει ζώνη"))
        // What a dot means in each module, at least by naming every module in the list.
        listOf("Λέξεις:", "Αριθμοί:", "Προτάσεις:", "Γράψε:", "Διάλογοι:", "Δεξί χέρι:")
            .forEach { assertTrue(it, p.contains(it)) }
        assertTrue("and it is asked for", p.contains("πες σε ποια κουκκίδα να πάει κάθε άσκηση"))
        assertTrue("aiming at four in five", p.contains("τέσσερα στα πέντε"))
    }

    /**
     * The prompt is now asked to name a dot per module, so a clause that says the app does more than
     * it does becomes advice. Typed sentence boards are `SentenceTemplates.TYPED_LEVELS = 7..8`,
     * which is dot 4 and dot 5 of `Difficulty.SENTENCES_BANDS`, and only when the judge is on
     * (`variantFor(…, judged)`); dot 3 is the gap board. And a module a caregiver has switched off
     * is not on his screen at all, so a dot for it is advice he cannot take.
     */
    @Test fun `the prompt says where the keyboard really starts and that a module can be off`() {
        val p = flowing(ClaudeAdvisor.SYSTEM_PROMPT)
        assertTrue("typed boards start at dot 4", p.contains("από το 4 και πάνω γράφει και ολόκληρες"))
        assertTrue("and only with the judge on", p.contains("μόνο όταν είναι ανοιχτός ο «Έλεγχος με Claude»"))
        assertTrue("dot 3 fills the gap", p.contains("στο 3 συμπληρώνει τη λέξη που λείπει"))
        assertFalse("never «from 3 and up»", p.contains("από το 3 και πάνω"))
        // Numbers' top band is levels 12–15: change, the clock and the day after, four-digit number
        // words, two-step problems. No dates anywhere on the ladder.
        assertFalse("no dates on the numbers ladder", p.contains("ημερομηνί"))
        assertTrue("the week day is what level 13 has", p.contains("τη μέρα της εβδομάδας"))
        assertTrue("a module can be switched off", p.contains("μπορεί να κλείσει τελείως μια άσκηση"))
        assertTrue("and then he cannot do it", p.contains("δεν φαίνεται καθόλου στην οθόνη"))
    }

    /** The scales the numbers are on, so «μέση βοήθεια 2,4» is read as what it is. */
    @Test fun `the prompt explains the help scale and the boxes`() {
        val p = flowing(ClaudeAdvisor.SYSTEM_PROMPT)
        assertTrue(p, p.contains("0 = το είπε μόνος του με την εικόνα"))
        assertTrue(p, p.contains("3 άκουσε τη λέξη"))
        assertTrue(p, p.contains("1–5"))
    }

    /**
     * `ask` resolves the model itself and falls back silently when the setting is blank, so a row
     * that stored the setting could name a model that was never asked — and `advice.model` is the
     * one field of that row nobody can check afterwards.
     */
    @Test fun `an answer carries no model until the advisor names one`() {
        assertEquals("", ClaudeAdvisor.parse(answer("- Ένα.", "Μπράβο.")).model)
        assertEquals("claude-opus-5", ClaudeAdvisor.parse(answer("- Ένα.", "Μπράβο."))
            .copy(model = "claude-opus-5").model)
    }

    // ---- the third section ---------------------------------------------------------------------

    private fun answer3(caregivers: String, dimitris: String, focus: String) =
        "${ClaudeAdvisor.CAREGIVERS}\n$caregivers\n\n${ClaudeAdvisor.DIMITRIS}\n$dimitris\n\n" +
            "${ClaudeAdvisor.FOCUS}\n$focus\n"

    private val json = """{"items":["καφές"],"sounds":["π"],"modules":["WORDCOACH"],"levels":{"numbers":3},"why":"τα ψώνια"}"""

    @Test fun `all three sections land in their own halves`() {
        val advice = ClaudeAdvisor.parse(answer3("- Δούλεψε τα ψώνια.", "Πάει καλά.", json))

        assertEquals("- Δούλεψε τα ψώνια.", advice.caregivers)
        assertEquals("Πάει καλά.", advice.dimitris)
        assertEquals(json, advice.focusJson)
    }

    /**
     * The whole reason the focus is cut off first. A JSON object read out loud at 0.8× to a man
     * with expressive aphasia is exactly the kind of harm the split exists to prevent, and before
     * the third heading existed the object would have been the tail of his own section.
     */
    @Test fun `the focus is never part of what is read to him`() {
        val advice = ClaudeAdvisor.parse(answer3("- Ένα.", "Πάει καλά. Συνέχισε.", json))

        assertEquals("Πάει καλά. Συνέχισε.", advice.dimitris)
        assertFalse(advice.dimitris, advice.dimitris.contains("{"))
        assertFalse(advice.caregivers, advice.caregivers.contains("{"))
    }

    @Test fun `a missing focus section is an empty focus, not a broken answer`() {
        val advice = ClaudeAdvisor.parse(answer("- Ένα.", "Μπράβο."))

        assertEquals("- Ένα.", advice.caregivers)
        assertEquals("Μπράβο.", advice.dimitris)
        assertEquals("", advice.focusJson)
    }

    @Test fun `a focus heading with no object under it is empty rather than nonsense`() {
        val advice = ClaudeAdvisor.parse(answer3("- Ένα.", "Μπράβο.", "Δεν έχω κάτι συγκεκριμένα."))
        assertEquals("", advice.focusJson)
        assertEquals("Μπράβο.", advice.dimitris)
    }

    /** Models fence their JSON and models wrap it. Neither is a reason to lose it. */
    @Test fun `a fenced or wrapped object is still found, on one line`() {
        val fenced = ClaudeAdvisor.parse(answer3("- Ένα.", "Μπράβο.", "```json\n{\n  \"items\": [\"καφές\"]\n}\n```"))
        assertEquals("""{ "items": ["καφές"] }""", fenced.focusJson)
    }

    /**
     * The scan is depth-matched, exactly as `JudgeContract.jsonObject` is and for the same reason:
     * first-brace-to-**last**-brace was too greedy in the other direction. One `}` in a sentence
     * after the object, or a second object under the heading, and the substring stopped being JSON —
     * Gson refuses trailing content — so a perfectly good focus became no focus at all, and his next
     * session was planned as though the advisor had said nothing about it.
     */
    @Test fun `a brace in the prose after the object does not swallow it`() {
        val advice = ClaudeAdvisor.parse(answer3("- Ένα.", "Μπράβο.", "$json\nΤο } εδώ είναι τυπογραφικό."))
        assertEquals(json, advice.focusJson)
    }

    @Test fun `a second object after the first is not read as part of it`() {
        val advice = ClaudeAdvisor.parse(answer3("- Ένα.", "Μπράβο.", """$json {"items":["ψωμί"]}"""))
        assertEquals(json, advice.focusJson)
    }

    /** A brace inside a string is text, not structure. */
    @Test fun `a brace inside a value does not close the object`() {
        val braced = """{"why":"το } δεν μετράει","items":["καφές"]}"""
        assertEquals(braced, ClaudeAdvisor.parse(answer3("- Ένα.", "Μπράβο.", braced)).focusJson)
    }

    /** Cut off by max_tokens mid-object: not a focus, and never half of one. */
    @Test fun `an object that was never closed is no focus at all`() {
        val advice = ClaudeAdvisor.parse(answer3("- Ένα.", "Μπράβο.", """{"items":["καφές","""))
        assertEquals("", advice.focusJson)
    }

    /** An answer whose two headings are mangled still gives a focus, and still says nothing to him. */
    @Test fun `a focus survives an answer that lost its other headings`() {
        val advice = ClaudeAdvisor.parse("Μια απάντηση χωρίς τίτλους.\n\n${ClaudeAdvisor.FOCUS}\n$json")

        assertEquals("Μια απάντηση χωρίς τίτλους.", advice.caregivers)
        assertEquals("", advice.dimitris)
        assertEquals(json, advice.focusJson)
    }

    /** Out of order is a mangled answer: nothing is read to him, whatever the focus says. */
    @Test fun `a focus written before his section does not split the answer`() {
        val text = "${ClaudeAdvisor.CAREGIVERS}\n- Ένα.\n\n${ClaudeAdvisor.FOCUS}\n$json\n\n" +
            "${ClaudeAdvisor.DIMITRIS}\nΜπράβο."
        val advice = ClaudeAdvisor.parse(text)

        assertEquals("", advice.dimitris)
        assertEquals(json, advice.focusJson)
    }

    /** The room and the wait both went up with the report; a stalled request still gives up. */
    @Test fun `the budget matches the size of what is now sent`() {
        assertEquals(16_000L, ClaudeAdvisor.MAX_TOKENS)
        assertEquals(240L, ClaudeAdvisor.TIMEOUT_SECONDS)
        assertEquals(1, ClaudeAdvisor.MAX_RETRIES)
    }
}
