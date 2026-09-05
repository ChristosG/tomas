package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Test

class GreekTest {
    @Test fun `single consonant`() = assertEquals("κ", Greek.firstSound("καφές"))
    @Test fun `accented vowel loses its accent`() = assertEquals("α", Greek.firstSound("άνθρωπος"))
    @Test fun `vowel digraph stays together`() = assertEquals("ου", Greek.firstSound("ουρανός"))
    @Test fun `consonant digraph stays together`() = assertEquals("μπ", Greek.firstSound("μπάλα"))
    @Test fun `capital and whitespace are normalised`() = assertEquals("ν", Greek.firstSound("  Νερό "))
    @Test fun `empty gives empty`() = assertEquals("", Greek.firstSound("   "))
    @Test fun `stripAccents keeps letters`() = assertEquals("καλημερα", Greek.stripAccents("καλημέρα"))
}
