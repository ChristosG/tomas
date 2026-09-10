package gr.dimitris.app.core.judge

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.models.messages.MessageCreateParams
import gr.dimitris.app.caregiver.insights.ClaudeAdvisor
import gr.dimitris.app.core.secrets.Secrets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * One request to Claude, reduced to the only two outcomes the judge cares about, so a test can
 * answer it without a network and without the SDK.
 */
fun interface JudgeClient {
    /**
     * The model's answer as text; null when safety declined it or nothing came back at all. Anything
     * else — a timeout, a 401, a socket that died — throws, and [TurnJudge] turns every throw into
     * the local fallback.
     */
    suspend fun reply(key: String, system: String, user: String): String?
}

/**
 * A failure the judge is willing to write down. No cause, for exactly the reason
 * [gr.dimitris.app.caregiver.insights.AdviceException] has none: an exception from the HTTP client
 * drags its message and its stack into `error_logs`, where a caregiver reads them and a backup could
 * carry them, and the only secret on this phone travels in that request's headers.
 */
class JudgeException(message: String) : Exception(message)

/**
 * Every judgement of what Dimitris said, through one place (spec §13).
 *
 * With a key saved, «Έλεγχος με Claude» on and a network, one fast model call per turn decides
 * whether what he said counts and hands back the full grammatical sentence when his was telegraphic.
 * Without any of those — no key, the toggle off, flight mode, a timeout, a reply that was not a
 * verdict — [LocalJudge] answers instead, and he sees no difference. That is the whole contract of
 * this class, and it is why [judge] cannot fail:
 *
 * * **It never throws.** A man with expressive aphasia cannot be shown «σφάλμα δικτύου» in the middle
 *   of a sentence he is trying to produce. Every path out of here is a [Verdict].
 * * **It never waits long.** [TIMEOUT_SECONDS] seconds, because this runs between him speaking and
 *   the phone answering, not on a caregiver's report screen.
 * * **It is off by default.** The toggle starts false and the key starts absent, so a fresh install
 *   judges every turn on the phone and touches nothing outside it.
 *
 * The toggle is read *before* the key, because reading the key opens an encrypted file and touches
 * the keystore; a caregiver who never turned this on should not pay for that on every word.
 */
class TurnJudge(
    private val secrets: Secrets,
    /** «Έλεγχος με Claude». Read per turn, so switching it off stops the very next one. */
    private val enabled: suspend () -> Boolean,
    /** What actually goes out. A seam, not the SDK, so a test can answer without a network. */
    private val client: JudgeClient = Anthropic,
    /**
     * Where a failure is written down for the caregiver's error list. Defaulted to silence so a test
     * that is not about the log does not have to say so, and so nothing here can ever be the reason a
     * turn fails.
     */
    private val record: (String, Throwable) -> Unit = { _, _ -> },
) {

    /**
     * Which failures have already been written down. One row per class per run, not one per turn: a
     * phone that lost its connection fails on every word of a fifteen-minute session, and three
     * hundred identical rows would bury the error log a caregiver is supposed to be able to read.
     */
    private val reported: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * A new session or a new module. Clears what has been reported, so a connection that was down
     * this morning and is down again tonight is two rows rather than one — and still not three
     * hundred. Nothing breaks if a caller never calls it; the log just stays quieter.
     */
    fun newRun() = reported.clear()

    /** Never throws. Every failure is a [LocalJudge] verdict and at most one row in the error log. */
    suspend fun judge(ask: Ask): Verdict = withContext(Dispatchers.IO) {
        if (!on()) return@withContext LocalJudge.judge(ask)
        val key = key() ?: return@withContext LocalJudge.judge(ask)

        val reply = try {
            client.reply(key, JudgeContract.SYSTEM_PROMPT, JudgeContract.userMessage(ask))
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            // The throwable itself is dropped on the floor: it is the one object in this app that
            // has seen the key. FAILED says everything that is safe to keep.
            return@withContext fallback(ask, WHERE_FAILED, FAILED)
        }
        if (reply == null) return@withContext fallback(ask, WHERE_REFUSED, REFUSED)
        JudgeContract.parse(reply, ask) ?: fallback(ask, WHERE_REPLY, BAD_REPLY)
    }

    private fun fallback(ask: Ask, where: String, message: String): Verdict {
        if (reported.add(where)) record(where, JudgeException(message))
        return LocalJudge.judge(ask)
    }

    /** A settings read that failed reads as off: the local judge is always a correct answer. */
    private suspend fun on(): Boolean = try {
        enabled()
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        false
    }

    /** Touches the keystore, which is why [judge] is on [Dispatchers.IO] before this is called. */
    private fun key(): String? = try {
        secrets.claudeKey()?.takeIf { it.isNotBlank() }
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        null
    }

    companion object {
        /**
         * The fast model, per spec §13: this runs between him speaking and the phone answering, so
         * latency is the feature being bought. The advisor keeps `claude-opus-5` — it is asked once a
         * week by a caregiver who can wait four minutes.
         *
         * Not a setting, unlike the advisor's model. A caregiver has no way to tell a model that is
         * good at this from one that is not, and a mistyped id here would silently turn every turn
         * in the app into a local match.
         */
        const val MODEL = "claude-haiku-4-5-20251001"

        /** One small JSON object. Adaptive thinking is deliberately **not** asked for: see [Anthropic]. */
        const val MAX_TOKENS = 300L

        /**
         * Per attempt, and with [MAX_RETRIES] that is two attempts. Chosen against what he is doing
         * while it runs: he has just finished saying a sentence and the phone owes him an answer.
         * Eight seconds is a noticeable wait; sixteen is the outer edge, and past that the local
         * judge's answer — which is the one he used to get — is better than a correct one nobody is
         * still waiting for.
         */
        const val TIMEOUT_SECONDS = 8L

        /** One retry. A turn is cheap to re-ask and expensive to keep him waiting for. */
        const val MAX_RETRIES = 1

        /** Where in the app a failure happened, for the caregiver's error list. */
        const val WHERE_FAILED = "claude judge"
        const val WHERE_REFUSED = "claude judge refused"
        const val WHERE_REPLY = "claude judge reply"

        /**
         * What is written down. Greek, because a caregiver reads these, and carrying nothing from the
         * request — not a status code, not a host, certainly not a header.
         */
        const val FAILED = "Ο έλεγχος με Claude δεν απάντησε. Η άσκηση συνέχισε χωρίς αυτόν."
        const val REFUSED = "Ο Claude δεν έκρινε αυτόν τον γύρο. Η άσκηση συνέχισε χωρίς αυτόν."
        const val BAD_REPLY = "Η απάντηση του Claude δεν διαβάστηκε. Η άσκηση συνέχισε χωρίς αυτόν."

        /**
         * The real call. A new client per request, as [ClaudeAdvisor] does: the key is read fresh
         * from the encrypted store each time, so a caregiver who deletes it has deleted it, and no
         * long-lived object is left holding it.
         *
         * No thinking config at all — not even adaptive, which the advisor uses. This is a 300-token
         * yes-or-no about one Greek sentence and a man is waiting for it; thinking would spend the
         * budget and the seconds on a question that does not need either. `refused` and `textOf` are
         * the advisor's own, so "a thinking block is not an answer" stays decided in one place.
         */
        internal val Anthropic = JudgeClient { key, system, user ->
            var client: AnthropicClient? = null
            try {
                client = AnthropicOkHttpClient.builder()
                    .apiKey(key)
                    .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                    .maxRetries(MAX_RETRIES)
                    .build()
                val params = MessageCreateParams.builder()
                    .model(MODEL)
                    .maxTokens(MAX_TOKENS)
                    .system(system)
                    .addUserMessage(user)
                    .build()
                val response = client.messages().create(params)
                if (ClaudeAdvisor.refused(response)) null
                else ClaudeAdvisor.textOf(response).takeIf { it.isNotEmpty() }
            } finally {
                runCatching { client?.close() }
            }
        }
    }
}
