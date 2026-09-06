package gr.dimitris.app.caregiver.insights

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.ThinkingConfigAdaptive
import gr.dimitris.app.core.secrets.Secrets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * What came back: one section for the people who look after him, one for him.
 *
 * [truncated] means the model ran out of room mid-answer. Not an error — what did arrive is worth
 * reading — but the screen says so under the text, because a caregiver should not have to guess
 * whether a short answer was short on purpose.
 */
data class Advice(val caregivers: String, val dimitris: String, val truncated: Boolean = false)

/**
 * A failure the app is willing to write down. Deliberately carries **no cause**: an exception from
 * the HTTP client would drag its own message and stack trace into `error_logs`, where a caregiver
 * can read them and a backup could carry them, and the only secret on this phone travels in that
 * request's headers. Everything the advisor knows that is safe to keep is already in [message].
 */
class AdviceException(message: String) : Exception(message)

/**
 * The optional advisor. Nothing here runs unless a caregiver has saved their own Anthropic key in
 * the settings and then tapped the button, and the only thing that ever leaves the phone is the
 * [AdviceSummary] text the same screen shows them first.
 *
 * The advice is not a diagnosis and the prompt says so; what comes back is a week's worth of
 * suggestions for the people around him, plus two warm sentences the phone reads aloud to him.
 */
class ClaudeAdvisor(private val secrets: Secrets, private val model: suspend () -> String) {

    /** Touches the encrypted store, so call it off the main thread. */
    val hasKey: Boolean get() = !secrets.claudeKey().isNullOrBlank()

    suspend fun ask(summary: String): Result<Advice> = withContext(Dispatchers.IO) {
        val key = secrets.claudeKey()?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(AdviceException(NO_KEY))
        val chosen = try {
            model().takeIf { it.isNotBlank() } ?: FALLBACK_MODEL
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Throwable) {
            FALLBACK_MODEL
        }
        var client: com.anthropic.client.AnthropicClient? = null
        try {
            client = AnthropicOkHttpClient.builder()
                .apiKey(key)
                // Two minutes, not the SDK's ten. A caregiver who has tapped «πίσω» has abandoned
                // the answer; the socket, the IO thread and the key in that request's headers must
                // not outlive their patience by eight minutes.
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                .build()
            val params = MessageCreateParams.builder()
                .model(chosen)
                .maxTokens(MAX_TOKENS)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .addUserMessage(summary)
                .build()
            val response = client.messages().create(params)
            if (refused(response)) return@withContext Result.failure(AdviceException(REFUSED))
            val text = textOf(response)
            if (text.isEmpty()) return@withContext Result.failure(AdviceException(EMPTY))
            Result.success(parse(text).copy(truncated = truncated(response)))
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: AnthropicServiceException) {
            // The status code is the one detail worth keeping: 401 is a mistyped key, and a caregiver
            // who is told that fixes it in a minute. Nothing else from the exception is kept.
            Result.failure(AdviceException(serviceMessage(e.statusCode())))
        } catch (_: Throwable) {
            Result.failure(AdviceException(FAILED))
        } finally {
            runCatching { client?.close() }
        }
    }

    companion object {
        const val CAREGIVERS = "## Για τους φροντιστές"
        const val DIMITRIS = "## Για τον Δημήτρη"

        /** Room for the two short sections plus the thinking that leads to them. */
        const val MAX_TOKENS = 8_000L

        /** Long enough for a considered answer, short enough that a cancelled one really stops. */
        const val TIMEOUT_SECONDS = 120L

        /**
         * How much of the answer is ever read out loud to him. The prompt asks for two sentences;
         * this is what happens when it does not get them. A paragraph of numbers read at a man with
         * expressive aphasia is not advice, and past about 4 000 characters `TextToSpeech.speak`
         * simply refuses and he gets an error instead of a voice.
         */
        const val MAX_DIMITRIS = 400

        /** Below this a sentence-boundary cut would leave a stub, so the plain cut is kinder. */
        private const val MIN_SENTENCE = MAX_DIMITRIS / 2

        /** Where a Greek sentence can end. `;` is the Greek question mark. */
        private val SENTENCE_ENDS = charArrayOf('.', '!', ';', '…')

        /** Used only if the settings flow somehow hands back nothing; the real default is in Settings. */
        const val FALLBACK_MODEL = "claude-opus-5"

        const val NO_KEY = "Δεν υπάρχει κλειδί. Βάλε ένα στις ρυθμίσεις."
        const val REFUSED = "Ο Claude δεν απάντησε σε αυτό το αίτημα."
        const val EMPTY = "Ο Claude δεν απάντησε. Δοκίμασε ξανά."
        const val FAILED = "Ο Claude δεν απάντησε. Δοκίμασε ξανά."
        const val BAD_KEY = "Το κλειδί δεν έγινε δεκτό. Έλεγξε το κλειδί στις ρυθμίσεις."
        const val TRUNCATED = "Η απάντηση κόπηκε. Ρώτα ξανά."

        /**
         * Who Dimitris is, said once, so the advice is about him and not about a stroke in general.
         * Fixed in the app rather than editable: it is the one part of the request that a caregiver
         * must not be able to turn into something the phone then reads out loud to him.
         */
        val SYSTEM_PROMPT = """
            Είσαι σύμβουλος για την καθημερινή εξάσκηση του Δημήτρη, ενός ενήλικα άνδρα στην Ελλάδα.
            Πριν από περίπου δυόμισι χρόνια είχε εγκεφαλικό στο αριστερό ημισφαίριο. Έχει δεξιά
            ημιπάρεση, αφασία Broca (καταλαβαίνει πολύ καλά, δυσκολεύεται να βγάλει τις λέξεις) και
            ακαλκουλία. Η μνήμη, το χιούμορ και το τραγούδι του είναι ακέραια — τραγουδάει λέξεις που
            δεν μπορεί να πει.

            Θα λάβεις μια περίληψη της εξάσκησής του από την εφαρμογή του: λεπτά, ασκήσεις ανά
            άσκηση, πόση βοήθεια χρειάστηκε, δύσκολες λέξεις, επίπεδα.

            Απάντησε στα ελληνικά, με απλά λόγια, σαν ενήλικας προς ενήλικες. Καμία ιατρική διάγνωση,
            καμία πρόγνωση, τίποτα που να τον υποτιμά. Αν τα στοιχεία είναι λίγα, πες το απλά.

            Γράψε ακριβώς αυτές τις δύο ενότητες, με αυτούς ακριβώς τους τίτλους και με αυτή τη σειρά:

            $CAREGIVERS
            3 έως 6 σύντομες, συγκεκριμένες προτάσεις για το τι να κάνουν οι φροντιστές την επόμενη
            εβδομάδα.

            $DIMITRIS
            Το πολύ δύο σύντομες, ζεστές προτάσεις προς τον ίδιο τον Δημήτρη, σε δεύτερο πρόσωπο, σε
            πολύ απλά ελληνικά, χωρίς αριθμούς και χωρίς ποσοστά. Το τηλέφωνο θα τις διαβάσει
            δυνατά, οπότε γράψε τες όπως θα τις έλεγες.
        """.trimIndent()

        private fun serviceMessage(status: Int): String =
            if (status == 401 || status == 403) BAD_KEY else "$FAILED (σφάλμα $status)"

        /**
         * The words of the answer. Text blocks only, in order: a thinking block is the model's
         * reasoning, not its answer, and reading one out to Dimitris would be the worst possible
         * kind of wrong. `ContentBlock.text()` is empty for every non-text block, so the filter is
         * the SDK's own and not a list of type names this file would have to keep up to date.
         */
        internal fun textOf(m: Message): String =
            m.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("\n").trim()

        /** True when safety declined the request. HTTP 200, so nothing throws; only this says so. */
        internal fun refused(m: Message): Boolean = stopReason(m).equals("refusal", ignoreCase = true)

        /** True when the model ran out of room. What arrived is still worth showing — with a warning. */
        internal fun truncated(m: Message): Boolean = stopReason(m).equals("max_tokens", ignoreCase = true)

        private fun stopReason(m: Message): String? = m.stopReason().orElse(null)?.asString()

        /**
         * Splits the answer on the two headings. A reply that lost them is not thrown away — the
         * caregivers get the whole thing and nothing is read out loud to Dimitris, which is the
         * safe way round: an unsplit answer is a full answer in the wrong shape, and reading an
         * answer meant for caregivers to him would be the actual harm.
         */
        fun parse(text: String): Advice {
            val c = text.indexOf(CAREGIVERS)
            val d = text.indexOf(DIMITRIS)
            if (c == -1 || d == -1 || d < c) return Advice(text.trim(), "")
            return Advice(
                caregivers = text.substring(c + CAREGIVERS.length, d).trim(),
                dimitris = forDimitris(text.substring(d + DIMITRIS.length)),
            )
        }

        /**
         * His half, capped once, here — so what is on the screen and what is spoken are the same
         * words. Cut at the end of a sentence when there is one late enough to be worth keeping;
         * otherwise cut plainly rather than hand him a two-word stub.
         */
        internal fun forDimitris(raw: String): String {
            val text = raw.trim()
            if (text.length <= MAX_DIMITRIS) return text
            val head = text.take(MAX_DIMITRIS)
            val end = head.lastIndexOfAny(SENTENCE_ENDS)
            return (if (end >= MIN_SENTENCE) head.substring(0, end + 1) else head).trim()
        }
    }
}
