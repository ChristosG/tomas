package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Test

class AccusativeTest {

    /** The masculine ending Dimitris has to hear: «θέλω καφέ», never «θέλω καφές». */
    @Test fun `a masculine noun drops its final sigma`() {
        assertEquals("καφέ", Greek.accusative("καφές"))
        assertEquals("χυμό", Greek.accusative("χυμός"))
        assertEquals("άντρα", Greek.accusative("άντρας"))
        assertEquals("ήλιο", Greek.accusative("ήλιος"))
        assertEquals("δρόμο", Greek.accusative("δρόμος"))
    }

    /** Neuters and feminines are the same word in the accusative, and are left exactly as they are. */
    @Test fun `neuter and feminine nouns are unchanged`() {
        assertEquals("νερό", Greek.accusative("νερό"))
        assertEquals("μπύρα", Greek.accusative("μπύρα"))
        assertEquals("ψωμί", Greek.accusative("ψωμί"))
        assertEquals("τσάι", Greek.accusative("τσάι"))
        assertEquals("ζάχαρη", Greek.accusative("ζάχαρη"))
    }

    /**
     * Plural nouns are the same word in the accusative too, and «πατάτες» ends in the sigma a
     * masculine singular would lose. Where the accent sits is what tells the two apart.
     */
    @Test fun `plurals are unchanged`() {
        assertEquals("πατάτες", Greek.accusative("πατάτες"))
        assertEquals("γυναίκες", Greek.accusative("γυναίκες"))
        assertEquals("πόλεις", Greek.accusative("πόλεις"))
        assertEquals("φρούτα", Greek.accusative("φρούτα"))
        assertEquals("κλειδιά", Greek.accusative("κλειδιά"))
    }

    /**
     * A neuter that ends in sigma keeps it: «τρώω κρέας», not «τρώω κρέα». The ending alone cannot
     * tell the two apart, so the neuters that end in one are named.
     */
    @Test fun `a neuter that ends in sigma keeps it`() {
        assertEquals("κρέας", Greek.accusative("κρέας"))
        assertEquals("φως", Greek.accusative("φως"))
        assertEquals("λάθος", Greek.accusative("λάθος"))
    }

    /** Caregivers type in a hurry, and a word arrives capitalised or with the accent left off. */
    @Test fun `capitals and missing accents are still recognised`() {
        assertEquals("Καφέ", Greek.accusative("Καφές"))
        assertEquals("κρεας", Greek.accusative("κρεας"))
        assertEquals("καφέ", Greek.accusative("  καφές  "))
    }

    /** Only the noun bends; whatever stands in front of it is left alone. */
    @Test fun `a two-word name bends on its last word`() {
        assertEquals("σούπερ μάρκετ", Greek.accusative("σούπερ μάρκετ"))
        assertEquals("φυσικό χυμό", Greek.accusative("φυσικό χυμός"))
    }

    @Test fun `nothing is nothing`() = assertEquals("", Greek.accusative("   "))
}
