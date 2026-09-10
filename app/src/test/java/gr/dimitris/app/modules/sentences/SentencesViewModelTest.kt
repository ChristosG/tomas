package gr.dimitris.app.modules.sentences

import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.judge.Ask
import gr.dimitris.app.core.judge.Kind
import gr.dimitris.app.core.judge.Source
import gr.dimitris.app.core.judge.Verdict
import gr.dimitris.app.core.seed.SeedManifest
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.random.Random

/**
 * The typed sentence of spec §13, argued with in a second rather than on a phone.
 *
 * Dimitris found the two-tile sentences trivial, and what he is actually missing is not the order of
 * two pictures — it is producing a whole sentence with the small words in it. Levels 7 and 8 put a
 * picture and a keyboard in front of him and ask for exactly that. The rules live in [TypedCheck],
 * because [SentencesViewModel] cannot be built without an Android context; `SentencesFlowTest`
 * proves the wiring on a device, and what is pinned here is what the wiring is *for*:
 *
 * * a sentence the judge accepts is his own — CORRECT the first time, and the mark is what it costs
 *   to be shown the answer;
 * * one it does not is never a wall: the whole form comes back to copy, and he may write it again
 *   as often as he likes;
 * * a judge that refuses without writing the sentence out still leaves him something to copy;
 * * and with the judge off there are no typed boards at all, because nothing on the phone can read
 *   a sentence.
 */
class SentencesViewModelTest {

    /** The board: a picture of bread, and the sentence it is about. */
    private val prompt = "ψωμί"
    private val target = "θέλω να φάω ψωμί γιατί πεινάω"

    private val asked = mutableListOf<Ask>()
    private val verdicts = ArrayDeque<Verdict>()

    private var judged = true
    private var dots = 4
    private var judgeThrows = false
    private val logged = mutableListOf<String>()

    private fun check() = TypedCheck(
        judged = { judged },
        difficulty = { dots },
        askJudge = { ask ->
            asked += ask
            if (judgeThrows) throw IOException("offline")
            verdicts.removeFirst()
        },
        record = { where, _ -> logged += where },
        now = { 0L },
    )

    private fun accepted(feedback: String? = null) =
        Verdict(accept = true, expanded = null, feedback = feedback, score = 1f, source = Source.JUDGE)

    private fun refused(expanded: String? = null, feedback: String? = null) =
        Verdict(accept = false, expanded = expanded, feedback = feedback, score = 0f, source = Source.JUDGE)

    @Test fun `a sentence the judge accepts is his own, and nothing is put on the screen over it`() = runTest {
        verdicts += accepted(feedback = "Μπράβο, ολόκληρη πρόταση.")
        val written = check().weigh(target, prompt, target)

        assertTrue("an accepted sentence counts", written.accepted)
        assertTrue("the first go is his own work", written.firstTry)
        assertNull("nothing to correct on a sentence he got right", written.whole)
        assertEquals("Μπράβο, ολόκληρη πρόταση.", written.feedback)
        // What went up, and nothing about him beyond the sentence he just wrote.
        val ask = asked.single()
        assertEquals(Kind.SENTENCE, ask.kind)
        assertEquals("the picture is the only hint the judge is given", prompt, ask.prompt)
        assertEquals(target, ask.target)
        assertEquals(target, ask.heard)
        assertEquals("his own dot row decides how much grammar to insist on", 4, ask.difficulty)
        // And no intent. This module's boards have exactly one right answer — the sentence the cards
        // were laid out from — so the target *is* what a good answer has to convey; an intent would
        // tell the model to accept any correct sentence about the picture, which is «Γράψε»'s typed
        // level and not this one. See [gr.dimitris.app.core.judge.Ask.intent].
        assertNull("a board with one right answer sent an intent", ask.intent)
        // The row has to be able to say the judge answered, and how long it took.
        assertEquals(Source.JUDGE.name, written.judge["source"])
        assertEquals(true, written.judge["accept"])
    }

    @Test fun `a sentence it does not accept comes back whole, to copy or to write again`() = runTest {
        val check = check()
        verdicts += refused(expanded = "Θέλω να φάω ψωμί γιατί πεινάω.", feedback = "Κοντά είσαι.")
        val first = check.weigh("θέλω ψωμί", prompt, target)

        assertFalse(first.accepted)
        assertEquals("the whole form, to copy", "Θέλω να φάω ψωμί γιατί πεινάω.", first.whole)
        assertEquals("Κοντά είσαι.", first.feedback)
        assertTrue("the first go was still his own", first.firstTry)

        // He copies it. The sentence counts — and it is assisted work, because it was on the screen.
        verdicts += accepted()
        val second = check.weigh("Θέλω να φάω ψωμί γιατί πεινάω.", prompt, target)
        assertTrue(second.accepted)
        assertFalse("a sentence copied off the screen is not a first try", second.firstTry)
        assertNull(second.whole)
    }

    /**
     * The prompt tells the model «ποτέ accept false χωρίς expanded», and the board still has to work
     * on the day it forgets: the sentence the board was built from is a correct one, and a refusal
     * with nothing to copy would be the wall this whole module is written not to be.
     */
    @Test fun `a refusal with no sentence in it still leaves him one`() = runTest {
        verdicts += refused(expanded = null)
        val written = check().weigh("ψωμί πεινάω", prompt, target)

        assertFalse(written.accepted)
        assertEquals(target, written.whole)
    }

    /** The judge's own contract is that it never throws. If one ever does, the board still answers him. */
    @Test fun `a judge that throws is one refusal and one row in the error list, not a dead board`() = runTest {
        judgeThrows = true
        val written = check().weigh("θέλω ψωμί", prompt, target)

        assertFalse("the local judge has no opinion about a sentence", written.accepted)
        assertEquals("and he is still shown what to write", target, written.whole)
        assertEquals(listOf(TypedCheck.WHERE), logged)
    }

    /**
     * With «Έλεγχος με Claude» off there is no typed board in the first place — the sitting is eight
     * ordinary ones. This is the toggle going off underneath him, between the board being planned
     * and «Έτοιμο»: the phase-11 comparison is not a judgement of his grammar, but it is honest
     * about the one thing it can see.
     */
    @Test fun `with the judge off the board falls back to the comparison and asks nobody`() = runTest {
        judged = false
        val check = check()
        assertTrue("he wrote the sentence", check.weigh(target, prompt, target).accepted)
        assertFalse("he wrote something else", check.weigh("νερό", prompt, target).accepted)
        assertTrue("nothing was asked of anybody", asked.isEmpty())
    }

    /**
     * A board he has not answered yet must not spend the mark it has never been given a chance to
     * earn: an empty field costs nobody the eight seconds, and it does not hand him a sentence to
     * copy or turn his next go into assisted work.
     */
    @Test fun `an empty field is not a turn and costs nobody eight seconds or the mark`() = runTest {
        val check = check()
        val written = check.weigh("   ", prompt, target)
        assertFalse(written.accepted)
        assertTrue("an empty string went to the judge", asked.isEmpty())
        assertNull("an empty field was answered with a sentence to copy", written.whole)

        verdicts += accepted()
        assertTrue("the next go is still his own", check.weigh(target, prompt, target).firstTry)
    }

    /**
     * The planning half of the same rule, which is what stops a typed board ever being drawn on a
     * phone that cannot read one. See [SentenceTemplates.variantFor].
     */
    @Test fun `no typed board exists on a phone whose judge will not answer`() {
        val templates = SentenceTemplates(Random(3))
        val pool = SeedManifest.parse(asset("seed/seed.json").readText()).items
            .filter { it.kind == ItemKind.WORD.name }
            .map { Item(text = it.text, kind = ItemKind.WORD, category = Category.valueOf(it.category)) }
        for (level in SentenceTemplates.TYPED_LEVELS) {
            val off = templates.session(level, pool, judged = false)
            assertEquals("a sitting is still a sitting", SentenceTemplates.SENTENCES_PER_SESSION, off.size)
            assertTrue("a typed board with nothing behind it", off.none { it.variant == Variant.TYPED })
            assertTrue("boards to build", off.all { it.variant == Variant.BUILD })
        }
    }

    /** Assets are not on the unit-test classpath, so the file is found by walking up from Gradle's directory. */
    private fun asset(name: String): java.io.File {
        var dir: java.io.File? = java.io.File(".").absoluteFile
        while (dir != null) {
            for (candidate in listOf(java.io.File(dir, "src/main/assets/$name"), java.io.File(dir, "app/src/main/assets/$name"))) {
                if (candidate.exists()) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("δεν βρέθηκε το asset $name")
    }
}
