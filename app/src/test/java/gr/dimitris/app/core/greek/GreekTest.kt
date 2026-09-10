package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GreekTest {
    @Test fun `single consonant`() = assertEquals("κ", Greek.firstSound("καφές"))
    @Test fun `accented vowel loses its accent`() = assertEquals("α", Greek.firstSound("άνθρωπος"))
    @Test fun `vowel digraph stays together`() = assertEquals("ου", Greek.firstSound("ουρανός"))
    @Test fun `consonant digraph stays together`() = assertEquals("μπ", Greek.firstSound("μπάλα"))
    @Test fun `capital and whitespace are normalised`() = assertEquals("ν", Greek.firstSound("  Νερό "))
    @Test fun `empty gives empty`() = assertEquals("", Greek.firstSound("   "))
    @Test fun `stripAccents keeps letters`() = assertEquals("καλημερα", Greek.stripAccents("καλημέρα"))

    // ------------------------------------------------------------------ articles

    private fun nom(noun: String) = Greek.article(noun, Case.NOMINATIVE)
    private fun acc(noun: String) = Greek.article(noun, Case.ACCUSATIVE, before = Greek.accusative(noun))

    @Test fun `the ending says it, where the ending is the whole answer`() {
        assertEquals("ο", nom("μπαμπάς"))
        assertEquals("ο", nom("καφές"))
        assertEquals("ο", nom("χυμός"))
        assertEquals("ο", nom("φυσιοθεραπευτής"))
        assertEquals("η", nom("αδελφή"))
        assertEquals("η", nom("τηλεόραση"))
        assertEquals("το", nom("νερό"))
        assertEquals("το", nom("ψωμί"))
        assertEquals("το", nom("ρολόι"))
    }

    /** The words whose ending lies. «γάλα» is not «η γάλα», and «ρούχα» is not one shirt. */
    @Test fun `the list says it, where the ending lies`() {
        assertEquals("το", nom("γάλα"))
        assertEquals("η", nom("πόρτα"))
        assertEquals("η", nom("σούπα"))
        assertEquals("τα", nom("ρούχα"))
        assertEquals("τα", nom("κλειδιά"))
        assertEquals("οι", nom("πατάτες"))
        assertEquals("το", nom("σούπερ μάρκετ"))
        // A neuter that keeps its final sigma keeps «το» with it.
        assertEquals("το", nom("κρέας"))
    }

    /** A word this file has never seen is a word it says nothing about. Silence, not a guess. */
    @Test fun `an unreadable word gets no article at all`() {
        assertNull(nom("τυρόπιτα"))
        assertNull(nom("ζαμπόν"))
        assertNull(nom("εγώ"))
        assertNull(nom(""))
        assertNull(Greek.contracted("κρουασάν"))
    }

    /**
     * The ending -ές lies in **both** directions: «καφές» is a masculine singular and «δουλειές» is a
     * feminine plural stressed exactly the same way. The masculines are a closed handful; the plurals
     * are open-ended. So the handful is named and everything else is silence — never «ο δουλειές».
     */
    @Test fun `a plural stressed on -ές is never read as a masculine singular`() {
        assertEquals("ο", nom("καφές"))
        // Named in the list, so they get their real form.
        assertEquals("οι", nom("δουλειές"))
        assertEquals("τις", acc("δουλειές"))
        assertEquals("οι", nom("αδερφές"))
        assertEquals("οι", nom("ελιές"))
        assertEquals("τις", acc("καρδιές"))
        // Not in the list, and the rule refuses to guess rather than making them masculine.
        assertNull(nom("μπουκιές"))
        assertNull(nom("κουβέντες"))
        assertNull(nom("σκάλες"))
    }

    /**
     * And the accusative makes the same call before it takes a final sigma off. «θέλω ελιέ» is not a
     * Greek word; «θέλω μεζές» for a masculine nobody listed is merely an ending left on.
     */
    @Test fun `an end-stressed plural keeps its sigma in the accusative`() {
        assertEquals("καφέ", Greek.accusative("καφές"))
        assertEquals("κεφτέ", Greek.accusative("κεφτές"))
        assertEquals("ελιές", Greek.accusative("ελιές"))
        assertEquals("δουλειές", Greek.accusative("δουλειές"))
        assertEquals("καρδιές", Greek.accusative("καρδιές"))
        assertEquals("μπουκιές", Greek.accusative("μπουκιές"))
        assertEquals("πατάτες", Greek.accusative("πατάτες"))
    }

    /**
     * The masculine keeps its final «ν» always — that is what tells «τον καφέ» from «το γάλα» out
     * loud — and the feminine keeps it before a vowel, κ, π, τ, ξ, ψ and μπ, ντ, γκ, τσ, τζ.
     */
    @Test fun `the accusative article keeps its ν exactly where Greek keeps it`() {
        assertEquals("τον", acc("καφές"))
        assertEquals("τον", acc("μπαμπάς"))
        assertEquals("το", acc("νερό"))
        assertEquals("τα", acc("κλειδιά"))
        assertEquals("τις", acc("πατάτες"))
        // Feminine, ν kept: a vowel, a κ, a τ, a μπ.
        assertEquals("την", acc("ομπρέλα"))
        assertEquals("την", acc("καρέκλα"))
        assertEquals("την", acc("τράπεζα"))
        assertEquals("την", acc("μπύρα"))
        // Feminine, ν gone: θ, σ, δ, μ, γ, β.
        assertEquals("τη", acc("θάλασσα"))
        assertEquals("τη", acc("σούπα"))
        assertEquals("τη", acc("δουλειά"))
        assertEquals("τη", acc("μαμά"))
        assertEquals("τη", acc("γυναίκα"))
        assertEquals("τη", acc("βροχή"))
    }

    /** «σε» is never a word of its own in Greek: it is written into the article. */
    @Test fun `σε is contracted into the article`() {
        val to = { noun: String -> Greek.contracted(noun, before = Greek.accusative(noun)) }
        assertEquals("στο", to("φαρμακείο"))
        assertEquals("στο", to("σούπερ μάρκετ"))
        assertEquals("στον", to("δρόμος"))
        assertEquals("στην", to("τράπεζα"))
        assertEquals("στην", to("κουζίνα"))
        assertEquals("στη", to("θάλασσα"))
        assertEquals("στη", to("δουλειά"))
        assertEquals("στα", to("φάρμακα"))
        assertEquals("στις", to("πατάτες"))
    }

    /**
     * A card standing on its own is not standing in front of anything, so it is written in full. The
     * sentence builder lays articles out as cards, and «τη» on a card would be half a word.
     */
    @Test fun `an article on a card of its own is written whole`() {
        assertEquals("την", Greek.article(NounForm(Gender.FEMININE, plural = false), Case.ACCUSATIVE))
        assertEquals("στην", Greek.contracted(NounForm(Gender.FEMININE, plural = false)))
        assertEquals("οι", Greek.article(NounForm(Gender.MASCULINE, plural = true), Case.NOMINATIVE))
        assertEquals("τους", Greek.article(NounForm(Gender.MASCULINE, plural = true), Case.ACCUSATIVE))
        assertEquals("τα", Greek.article(NounForm(Gender.NEUTER, plural = true), Case.NOMINATIVE))
    }

    @Test fun `a plural is told from a masculine singular by its accent`() {
        assertEquals(true, Greek.plural("πατάτες"))
        assertEquals(true, Greek.plural("γυναίκες"))
        assertEquals(true, Greek.plural("πόλεις"))
        assertEquals(false, Greek.plural("καφές"))
        assertEquals(false, Greek.plural("χυμός"))
        // And the ones only the list knows.
        assertEquals(true, Greek.plural("ρούχα"))
        assertEquals(false, Greek.plural("γάλα"))
    }
}
