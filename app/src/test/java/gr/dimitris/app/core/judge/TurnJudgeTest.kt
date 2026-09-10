package gr.dimitris.app.core.judge

import gr.dimitris.app.core.secrets.Secrets
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * The promise of this class is that nothing he does can make it fail him. So every test here is the
 * same test asked a different way: with the toggle off, with no key, with a socket that died, with
 * safety declining, with a reply that was not JSON — does he still get a verdict, and did the phone
 * stay off the network when it had no business being on it?
 */
class TurnJudgeTest {

    private val ask = Ask(Kind.DIALOGUE, prompt = "Τι θα πάρεις;", heard = "καφέ", difficulty = 3)
    private val expand = Ask(Kind.EXPAND, heard = "φάρμακα πρέπει πάρω")

    /** Counts its calls, so "it did not touch the network" is an assertion and not a hope. */
    private class FakeClient(private val answer: () -> String?) : JudgeClient {
        var calls = 0
            private set
        var lastSystem: String? = null
            private set
        var lastUser: String? = null
            private set

        override suspend fun reply(key: String, system: String, user: String): String? {
            calls++
            lastSystem = system
            lastUser = user
            return answer()
        }
    }

    /** What the error log was asked to keep. */
    private class Rows {
        val where = mutableListOf<String>()
        val messages = mutableListOf<String>()
        fun record(w: String, e: Throwable) {
            where += w
            messages += e.message.orEmpty()
        }
    }

    private fun judge(
        client: FakeClient,
        rows: Rows = Rows(),
        key: String? = "sk-test",
        on: Boolean = true,
    ) = TurnJudge(Secrets { key }, enabled = { on }, client = client, record = rows::record)

    // --- the good case --------------------------------------------------------------------------

    @Test fun `a verdict from Claude is used as it stands`() = runBlocking {
        val client = FakeClient { """{"accept":true,"expanded":"Θέλω έναν καφέ.","feedback":"Ωραία!","score":0.9}""" }
        val v = judge(client).judge(ask)

        assertEquals(Source.JUDGE, v.source)
        assertTrue(v.accept)
        assertEquals("Θέλω έναν καφέ.", v.expanded)
        assertEquals("Ωραία!", v.feedback)
        assertEquals(1, client.calls)
        assertEquals(JudgeContract.SYSTEM_PROMPT, client.lastSystem)
        assertEquals(JudgeContract.userMessage(ask), client.lastUser)
    }

    // --- and every way it can go wrong ----------------------------------------------------------

    /**
     * The toggle is the caregiver's consent, and without it nothing may leave the phone. `calls == 0`
     * is the privacy test of spec §13: not "it fell back", but "it never asked".
     */
    @Test fun `with the toggle off nothing is sent and the local judge answers`() = runBlocking {
        val client = FakeClient { error("the network must not be touched") }
        val v = judge(client, on = false).judge(ask)

        assertEquals(0, client.calls)
        assertEquals(Source.LOCAL, v.source)
        assertTrue(v.accept)
    }

    /** Same again for the other half of the condition: the toggle on, but no key to use. */
    @Test fun `with no key nothing is sent and the local judge answers`() = runBlocking {
        val missing = FakeClient { error("the network must not be touched") }
        assertEquals(Source.LOCAL, judge(missing, key = null).judge(ask).source)
        assertEquals(0, missing.calls)

        // A key of spaces is no key. It would otherwise go out as one and come back a 401.
        val blank = FakeClient { error("the network must not be touched") }
        assertEquals(Source.LOCAL, judge(blank, key = "   ").judge(ask).source)
        assertEquals(0, blank.calls)
    }

    /** Neither of those is a fault. A caregiver reading the error list must not find them there. */
    @Test fun `off and no key are not written to the error log`() = runBlocking {
        val rows = Rows()
        judge(FakeClient { null }, rows, on = false).judge(ask)
        judge(FakeClient { null }, rows, key = null).judge(ask)
        assertEquals(emptyList<String>(), rows.where)
    }

    @Test fun `a timeout falls back to the local judge and is written down once`() = runBlocking {
        val rows = Rows()
        val client = FakeClient { throw SocketTimeoutException("read timed out to api.anthropic.com") }
        val judge = judge(client, rows)

        val v = judge.judge(ask)
        assertEquals(Source.LOCAL, v.source)
        assertTrue(v.accept)
        assertEquals(listOf(TurnJudge.WHERE_FAILED), rows.where)
        // Nothing from the request survives: not the host, not the exception's own words.
        assertEquals(listOf(TurnJudge.FAILED), rows.messages)
    }

    @Test fun `a refusal falls back to the local judge`() = runBlocking {
        val rows = Rows()
        val v = judge(FakeClient { null }, rows).judge(ask)
        assertEquals(Source.LOCAL, v.source)
        assertEquals(listOf(TurnJudge.WHERE_REFUSED), rows.where)
        assertEquals(listOf(TurnJudge.REFUSED), rows.messages)
    }

    @Test fun `a malformed reply falls back to the local judge`() = runBlocking {
        val rows = Rows()
        val v = judge(FakeClient { "Ναι, μου φαίνεται σωστό." }, rows).judge(ask)
        assertEquals(Source.LOCAL, v.source)
        assertTrue(v.accept)
        assertEquals(listOf(TurnJudge.WHERE_REPLY), rows.where)
        assertEquals(listOf(TurnJudge.BAD_REPLY), rows.messages)
    }

    /** An EXPAND that came back with no sentence falls back to his own words, not to an error. */
    @Test fun `an expansion that came back empty falls back to his own words`() = runBlocking {
        val v = judge(FakeClient { """{"accept":true,"expanded":null,"score":1}""" }).judge(expand)
        assertEquals(Source.LOCAL, v.source)
        assertEquals("φάρμακα πρέπει πάρω", v.expanded)
    }

    /**
     * The reason the judge is held for the life of the app. A phone in a lift fails on every word of
     * a fifteen-minute session; three hundred identical rows would bury the log a caregiver is meant
     * to be able to read.
     */
    @Test fun `a connection that is down all session is one row, not one per word`() = runBlocking {
        val rows = Rows()
        val client = FakeClient { throw IOException("unreachable") }
        val judge = judge(client, rows)

        repeat(20) { judge.judge(ask) }

        assertEquals(20, client.calls)
        assertEquals(listOf(TurnJudge.WHERE_FAILED), rows.where)
    }

    /** Two different things going wrong are two different facts, and both are worth one row. */
    @Test fun `each failure class gets its own row`() = runBlocking {
        val rows = Rows()
        var mode = 0
        val client = object : JudgeClient {
            override suspend fun reply(key: String, system: String, user: String): String? = when (mode) {
                0 -> throw IOException("down")
                1 -> null
                else -> "δεν είναι JSON"
            }
        }
        val judge = TurnJudge(Secrets { "sk-test" }, enabled = { true }, client = client, record = rows::record)

        judge.judge(ask); mode = 1
        judge.judge(ask); mode = 2
        judge.judge(ask); judge.judge(ask)

        assertEquals(
            listOf(TurnJudge.WHERE_FAILED, TurnJudge.WHERE_REFUSED, TurnJudge.WHERE_REPLY),
            rows.where,
        )
    }

    /** A new session says it again: down this morning and down tonight is two facts, not one. */
    @Test fun `a new run may write the same failure down again`() = runBlocking {
        val rows = Rows()
        val judge = judge(FakeClient { throw IOException("down") }, rows)

        judge.judge(ask)
        judge.judge(ask)
        assertEquals(1, rows.where.size)

        judge.newRun()
        judge.judge(ask)
        assertEquals(2, rows.where.size)
    }

    /** A settings read that throws reads as off, because the local judge is always a correct answer. */
    @Test fun `a settings read that throws falls back without asking`() = runBlocking {
        val client = FakeClient { error("the network must not be touched") }
        val judge = TurnJudge(
            Secrets { "sk-test" },
            enabled = { throw IllegalStateException("datastore gone") },
            client = client,
        )
        val v = judge.judge(ask)
        assertEquals(0, client.calls)
        assertEquals(Source.LOCAL, v.source)
    }

    /** A keystore that refuses reads as "no key", which is the state the caregiver should then see. */
    @Test fun `a key store that throws falls back without asking`() = runBlocking {
        val client = FakeClient { error("the network must not be touched") }
        val judge = TurnJudge(
            Secrets { throw SecurityException("keystore") },
            enabled = { true },
            client = client,
        )
        assertEquals(Source.LOCAL, judge.judge(ask).source)
        assertEquals(0, client.calls)
    }

    /** Whatever happens, he is never told no by a network. A refused word is a refused word only. */
    @Test fun `a miss on the local path is still only a miss`() = runBlocking {
        val v = judge(FakeClient { throw IOException("down") })
            .judge(Ask(Kind.WORD, target = "καφές", heard = "αυτοκίνητο"))
        assertFalse(v.accept)
        assertNull(v.feedback)
        assertEquals(Source.LOCAL, v.source)
    }
}
