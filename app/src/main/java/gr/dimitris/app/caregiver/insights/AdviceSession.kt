package gr.dimitris.app.caregiver.insights

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The one question in flight, and the last answer, held outside any screen.
 *
 * Three things this exists for, all of them about a request that costs money and carries the
 * caregiver's key:
 *
 * * **One at a time.** A second «Ρώτα τον Claude» while one is running does nothing but say so. The
 *   guard is an [AtomicBoolean] cleared in the coroutine's own `finally`, not the identity of a
 *   `Job` — a cancelled job is not active, so a job-identity guard could never fire on the very
 *   path it was written for.
 * * **Never orphaned.** The call runs on the application scope, so leaving the screen does not
 *   abandon a billed request half way. It could not be cancelled anyway: the SDK's `messages()
 *   .create` is a blocking Java call, and cancelling the coroutine around it would leave the socket
 *   and the key on the wire until the timeout regardless — but at least this way the answer is not
 *   thrown away.
 * * **Kept.** The answer lands here, not in a ViewModel, so a caregiver who backs out while it is
 *   thinking and comes back finds it waiting.
 */
class AdviceSession(
    private val scope: CoroutineScope,
    /** What actually goes out. A function, not the advisor, so a test can hand in a fake. */
    private val send: suspend (String) -> Result<Advice>,
    private val record: (String, Throwable) -> Unit,
) {
    data class State(
        val asking: Boolean = false,
        val advice: Advice? = null,
        /** Greek, always. Never carries anything from a request. */
        val error: String? = null,
        /** Shown once when a second question is refused because one is already running. */
        val notice: String? = null,
        /**
         * Exactly the text that went out with the question being answered, kept beside the answer.
         *
         * The answer outlives the screen on purpose, and the screen rebuilds its summary from newer
         * data every time it is opened — so «Τι στάλθηκε» used to show a summary that was *not*
         * what produced the advice underneath it. On the one screen in the app that sends anything
         * anywhere, that label has to be true.
         */
        val sent: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private val inFlight = AtomicBoolean(false)

    fun ask(summary: String) {
        if (summary.isBlank()) return
        if (!inFlight.compareAndSet(false, true)) {
            _state.update { it.copy(notice = BUSY) }
            return
        }
        // A fresh State: the previous answer, its error and its notice all belong to the question
        // that produced them, and this is a new one. [State.sent] is what the screen shows as
        // «Τι στάλθηκε», so the label can never drift from the answer under it.
        _state.update { State(asking = true, sent = summary) }
        scope.launch {
            try {
                send(summary).fold(
                    onSuccess = { advice -> _state.update { it.copy(advice = advice, error = null) } },
                    onFailure = { e ->
                        // The advisor hands back an AdviceException carrying a Greek sentence and no
                        // cause, so nothing from the request — least of all the key — reaches
                        // error_logs. "No key" is not a fault to log: the disabled button already
                        // prevents it, and a caregiver reading the error list should not find it.
                        if (e.message != ClaudeAdvisor.NO_KEY) record("claude advice", e)
                        _state.update { it.copy(error = e.message ?: ClaudeAdvisor.FAILED) }
                    },
                )
            } finally {
                inFlight.set(false)
                _state.update { it.copy(asking = false) }
            }
        }
    }

    /** The caregiver has read the "wait" line; it should not follow them around. */
    fun noticeSeen() {
        _state.update { if (it.notice == null) it else it.copy(notice = null) }
    }

    companion object {
        const val BUSY = "Περίμενε την απάντηση."
    }
}
