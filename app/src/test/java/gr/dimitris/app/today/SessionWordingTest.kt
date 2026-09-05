package gr.dimitris.app.today

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionWordingTest {
    @Test fun `one exercise is singular`() =
        assertEquals("Έκανες 1 άσκηση σήμερα: Λέξεις.", SessionWording.summary(1, listOf("Λέξεις")))

    @Test fun `more than one is plural and lists every module`() =
        assertEquals("Έκανες 4 ασκήσεις σήμερα: Λέξεις, Αριθμοί.", SessionWording.summary(4, listOf("Λέξεις", "Αριθμοί")))

    @Test fun `nothing done is an invitation, not a score`() =
        assertEquals(SessionWording.NOTHING_DONE, SessionWording.summary(0, listOf("Λέξεις")))

    /** Defensive: a count with no module behind it still reads as a sentence. */
    @Test fun `no titles leaves off the list`() =
        assertEquals("Έκανες 2 ασκήσεις σήμερα.", SessionWording.summary(2, emptyList()))
}
