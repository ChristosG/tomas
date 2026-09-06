package gr.dimitris.app.caregiver.insights

import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlock
import com.anthropic.models.messages.ThinkingBlock
import com.anthropic.models.messages.Usage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Optional

/**
 * The half of the advisor that reads what the API sent back, driven with `Message` objects built by
 * the SDK's own builders rather than by a network call. It is the half that decides what a man with
 * aphasia hears out loud, and until now nothing exercised it at all: the live test is skipped on any
 * machine without a key, and the parser tests only ever saw a `String`.
 *
 * These also fail loudly if a future SDK bump changes `stopReason()` or `ContentBlock.text()` under
 * a Kotlin `?.`, which would otherwise compile and go wrong quietly.
 */
class ClaudeAdvisorMessageTest {

    private fun message(
        blocks: List<Any> = emptyList(),
        stop: StopReason? = StopReason.END_TURN,
    ): Message {
        val builder = Message.builder()
            .id("msg_test")
            .content(emptyList<ContentBlock>())
            .model("claude-opus-5")
            .stopReason(Optional.ofNullable(stop))
            .stopDetails(Optional.empty())
            .stopSequence(Optional.empty())
            .usage(
                Usage.builder()
                    .inputTokens(10)
                    .outputTokens(20)
                    .cacheCreation(Optional.empty())
                    .cacheCreationInputTokens(Optional.empty())
                    .cacheReadInputTokens(Optional.empty())
                    .inferenceGeo(Optional.empty())
                    .serverToolUse(Optional.empty())
                    .serviceTier(Optional.empty())
                    .build()
            )
        blocks.forEach { block ->
            when (block) {
                is TextBlock -> builder.addContent(block)
                is ThinkingBlock -> builder.addContent(block)
                else -> error("unsupported block in this test: $block")
            }
        }
        return builder.build()
    }

    private fun text(t: String) = TextBlock.builder().text(t).citations(emptyList()).build()

    private fun thinking(t: String) = ThinkingBlock.builder().thinking(t).signature("sig").build()

    @Test fun `only the text blocks become the answer, in order`() {
        val m = message(listOf(thinking("σκέφτομαι…"), text("πρώτο"), text("δεύτερο")))

        assertEquals("πρώτο\nδεύτερο", ClaudeAdvisor.textOf(m))
    }

    /**
     * The whole point of the filter: a thinking block is the model working, not the model answering.
     * Reading one out to Dimitris would be the worst kind of wrong this screen can do.
     */
    @Test fun `a thinking block is never part of the answer`() {
        val m = message(listOf(thinking("μήπως να του πω ότι τα πάει χάλια;"), text("Πάει καλά.")))

        assertEquals("Πάει καλά.", ClaudeAdvisor.textOf(m))
        assertFalse(ClaudeAdvisor.textOf(m).contains("χάλια"))
    }

    @Test fun `a response with no content at all is an empty answer, not a crash`() {
        assertEquals("", ClaudeAdvisor.textOf(message()))
        assertEquals("", ClaudeAdvisor.textOf(message(listOf(thinking("μόνο σκέψη")))))
    }

    @Test fun `a refusal is recognised`() {
        assertTrue(ClaudeAdvisor.refused(message(listOf(text("")), stop = StopReason.REFUSAL)))
        assertFalse(ClaudeAdvisor.refused(message(listOf(text("ok")))))
        assertFalse(ClaudeAdvisor.refused(message(listOf(text("ok")), stop = null)))
    }

    /** A cut answer is still an answer: the screen shows it and says it was cut. */
    @Test fun `running out of room is truncation, not refusal`() {
        val m = message(listOf(text("Μισή απάν")), stop = StopReason.MAX_TOKENS)

        assertTrue(ClaudeAdvisor.truncated(m))
        assertFalse(ClaudeAdvisor.refused(m))
        assertEquals("Μισή απάν", ClaudeAdvisor.textOf(m))
    }

    @Test fun `a finished answer is neither refused nor truncated`() {
        val m = message(listOf(text("Τέλος.")))

        assertFalse(ClaudeAdvisor.refused(m))
        assertFalse(ClaudeAdvisor.truncated(m))
    }
}
