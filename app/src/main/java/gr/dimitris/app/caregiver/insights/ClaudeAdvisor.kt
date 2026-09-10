package gr.dimitris.app.caregiver.insights

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.ThinkingConfigAdaptive
import gr.dimitris.app.core.judge.JudgeContract
import gr.dimitris.app.core.secrets.Secrets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Duration

/**
 * What came back: one section for the people who look after him, one for him, and one for the app.
 *
 * [focusJson] is the `## Εστίαση` object exactly as the model wrote it — raw, not parsed. It is
 * stored raw too ([gr.dimitris.app.core.data.Advice.focusJson]) and read through
 * [Focus] against the vocabulary of the day it is read, so a word the model named that a caregiver
 * only adds next week starts being honoured then instead of having been thrown away tonight.
 *
 * [truncated] means the model ran out of room mid-answer. Not an error — what did arrive is worth
 * reading — but the screen says so under the text, because a caregiver should not have to guess
 * whether a short answer was short on purpose.
 */
data class Advice(
    val caregivers: String,
    val dimitris: String,
    val focusJson: String = "",
    val truncated: Boolean = false,
    /**
     * The model that actually answered — resolved here, not read again by the caller. The settings
     * value can be blank or stale and [ClaudeAdvisor.ask] falls back to [ClaudeAdvisor.FALLBACK_MODEL]
     * without telling anyone, so a stored row that named the setting could name a model that was
     * never asked. `advice.model` is the one field of that row nobody can check afterwards.
     */
    val model: String = "",
)

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
                // Four minutes, not the SDK's ten. A caregiver who has tapped «πίσω» has abandoned
                // the answer; the socket, the IO thread and the key in that request's headers must
                // not outlive their patience by six more.
                .timeout(Duration.ofSeconds(TIMEOUT_SECONDS))
                // One retry, not the SDK's two. The timeout is per attempt, so three attempts of a
                // stalled connection is six minutes of a caregiver watching a spinner — and three
                // billed requests where they asked for one.
                .maxRetries(MAX_RETRIES)
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
            // Truncation is checked before emptiness: with adaptive thinking the budget can be gone
            // before a single text block is emitted, and telling the caregiver «δεν απάντησε» when
            // the honest answer is «κόπηκε» sends them retrying the same wall.
            val cut = truncated(response)
            if (text.isEmpty()) return@withContext Result.failure(AdviceException(if (cut) TRUNCATED else EMPTY))
            Result.success(parse(text).copy(truncated = cut, model = chosen))
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

        /** The third section: one JSON object the app itself acts on. See [Focus]. */
        const val FOCUS = "## Εστίαση"

        /**
         * Room for the three sections plus the thinking that leads to them. Doubled in phase 11:
         * what goes up is now his whole journey rather than a four-week summary, and an answer that
         * has read a year of history has more to say about it.
         */
        const val MAX_TOKENS = 16_000L

        /**
         * Long enough for a considered answer, short enough that a stalled one gives up. Doubled
         * with [MAX_TOKENS], for the same reason: a report of tens of thousands of characters is a
         * longer read and a longer answer, and a caregiver being told «δοκίμασε ξανά» because the
         * model was still thinking is the worst of both.
         */
        const val TIMEOUT_SECONDS = 240L

        /** Per attempt, and [TIMEOUT_SECONDS] is per attempt too, so this is the wait a caregiver gets. */
        const val MAX_RETRIES = 1

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
         * Who Dimitris is and what the answer has to look like, said once, so the advice is about
         * him and not about a stroke in general. Fixed in the app rather than editable: it is the
         * one part of the request that a caregiver must not be able to turn into something the
         * phone then reads out loud to him.
         *
         * Version 2, phase 11. What changed is what the model is now *given* — his whole journey
         * rather than four weeks of totals, the caregivers' own notes, and every advice it gave
         * before — so what it is asked for changed with it: compare yourself with what you said
         * last time, be specific enough to be acted on, and end with a focus the app can carry out
         * on its own ([Focus]).
         *
         * Version 3, phase 12. Dimitris tried the app himself and said it was too easy, and spec §13
         * rewrote who the advice is for: a man who says most everyday words, reads Greek slowly,
         * reasons well, and whose speech is telegraphic — so full sentences and multi-step tasks are
         * the goal, not single words. With it came the row of five dots on every module, which he
         * sets himself, so the prompt now explains what a dot means in each exercise and asks the
         * answer to name the dot each one should go to next.
         *
         * **The health of the man is §1 and §13 of the spec and nothing else** — no diagnosis, no
         * prognosis, no detail a caregiver mentioned in a note and no inference from the numbers. It
         * is written here rather than assumed because this text is the one thing in the app that
         * decides what a language model believes about him.
         */
        val SYSTEM_PROMPT = """
            Είσαι σύμβουλος για την καθημερινή εξάσκηση του Δημήτρη, ενός ενήλικα άνδρα στην Ελλάδα.
            Πριν από περίπου δυόμισι χρόνια είχε εγκεφαλικό στο αριστερό ημισφαίριο. Έχει δεξιά
            ημιπάρεση, αφασία Broca (καταλαβαίνει πολύ καλά, δυσκολεύεται να βγάλει τις λέξεις) και
            ακαλκουλία. Η μνήμη, το χιούμορ και το τραγούδι του είναι ακέραια — τραγουδάει λέξεις που
            δεν μπορεί να πει.

            Τον Σεπτέμβριο του 2026 δοκίμασε ο ίδιος την εφαρμογή και είπε ότι είναι πολύ εύκολη.
            Είναι πιο δυνατός απ' ό,τι υπέθετε η εφαρμογή: λέει τις περισσότερες καθημερινές λέξεις
            (όχι πάντα καθαρά), διαβάζει ελληνικά αργά αλλά καταλαβαίνει και αφηρημένες λέξεις,
            σκέφτεται καλά, χρησιμοποιεί βοηθό συνομιλίας με φωτογραφίες και λίγες γραμμένες λέξεις,
            και ήταν προγραμματιστής. Ο λόγος του είναι τηλεγραφικός — λέει «φάρμακα πρέπει πάρω».
            Αυτό που του λείπει είναι οι ολόκληρες προτάσεις και οι εργασίες με πολλά βήματα, και
            εκεί είναι τώρα το κέντρο της εφαρμογής: όπου μιλάει ή πατάει λέξεις, η εφαρμογή του
            δίνει ολόκληρη τη σωστή πρόταση, τη λέει, και του τη ζητάει πίσω.

            Αυτά είναι όλα όσα ξέρεις για την υγεία του. Μη συμπεράνεις και μη γράψεις τίποτα άλλο
            γι' αυτήν, ούτε από τους αριθμούς ούτε από τις σημειώσεις των φροντιστών.

            Συμβουλεύεις ως προπονητής με γνώση λογοθεραπείας, όχι ως γιατρός. Καμία ιατρική
            διάγνωση, καμία πρόγνωση, καμία φαρμακευτική ή ιατρική οδηγία. Αν κάτι χρειάζεται
            λογοθεραπευτή ή γιατρό, πες τους απλώς να το συζητήσουν μαζί του.

            Θα λάβεις όλη την πορεία του από την εφαρμογή: το προφίλ του, τις σημειώσεις των
            φροντιστών, κάθε άσκηση με τα σύνολά της, κάθε λέξη που έχει εξασκήσει ποτέ, τις
            τελευταίες τέσσερις εβδομάδες ανά ημέρα, τις προηγούμενες συμβουλές σου, τα επίπεδα και
            όσα βλέπει μόνη της η εφαρμογή. Η ενότητα «Προφίλ» εξηγεί τις κλίμακες· διάβασέ την πριν
            βγάλεις συμπέρασμα από αριθμό.

            Οι ασκήσεις, με τα ελληνικά ονόματα που βλέπει και τους κωδικούς που χρησιμοποιεί η
            εφαρμογή:
            - Λέξεις = WORDCOACH (βρίσκει τη λέξη για μια εικόνα)
            - Αριθμοί = NUMBERS (ποσά, πράξεις, ευρώ και ρέστα, ώρα, μέρες, προβλήματα)
            - Τραγούδα και πες το = SINGSAY (τραγουδάει τη φράση και μετά τη λέει)
            - Διάλογοι = SCRIPTS (ανοιχτοί διάλογοι· απαντάει με δικά του λόγια)
            - Προτάσεις = SENTENCES (φτιάχνει, συμπληρώνει ή γράφει ολόκληρη πρόταση)
            - Γράψε = TRACE (γράφει γράμματα και λέξεις με το δάχτυλο)
            - Δεξί χέρι = ARCADE (ασκήσεις για το δεξί του χέρι, όχι λόγος)
            - Βήματα = STEPS (μια καθημερινή δουλειά σε 3 έως 6 βήματα: πρώτα τα βάζει στη σειρά
              και μετά τα λέει με «πρώτα… μετά… τέλος». Είναι η άσκηση για τον σχεδιασμό, που ο
              ίδιος λέει πως είναι το πιο δύσκολό του)
            - SQL = SQL (απλές ερωτήσεις SELECT σε πίνακες — δύο με τις δικές του λέξεις και τις
              ασκήσεις του, δύο από το βιβλίο· ήταν προγραμματιστής και το ζήτησε ο ίδιος)
            - Μίλα = TALKBOARD, ο πίνακας επικοινωνίας — δεν είναι άσκηση
            Ο φροντιστής μπορεί να κλείσει τελείως μια άσκηση: τότε δεν φαίνεται καθόλου στην οθόνη
            του Δημήτρη και δεν μπορεί να την κάνει. Αν μια άσκηση δεν έχει κανένα στοιχείο, μπορεί
            να είναι κλειστή — πες το ως ερώτηση προς τους φροντιστές αντί να επιμείνεις σε κουκκίδα.

            Η δυσκολία: κάθε άσκηση έχει στην πρώτη της οθόνη μια σειρά από πέντε κουκκίδες, 1 έως 5.
            Τις πατάει ο ίδιος ο Δημήτρης· ο φροντιστής βάζει μόνο κάτω και πάνω όριο. Το 1 είναι το
            πιο εύκολο, το 5 το πιο δύσκολο, και σε κάθε άσκηση σημαίνει κάτι δικό της:
            - Λέξεις: 1 μόνο λέξεις· 2 έως 5 λέξεις και φράσεις
            - Αριθμοί: ζώνη επιπέδων 1–2, 3–4, 5–7, 8–11, 12–15 (στο 5 φτάνει σε ρέστα, ώρα, τη
              μέρα της εβδομάδας, τετραψήφιους αριθμούς με λέξεις και προβλήματα δύο βημάτων)
            - Προτάσεις: ζώνη επιπέδων 1–2, 3–4, 5–6, 7, 8 (στο 3 μπαίνουν τα άρθρα, στο 4 μια
              δευτερεύουσα πρόταση, στο 5 η ερώτηση· στο 3 συμπληρώνει τη λέξη που λείπει, και από
              το 4 και πάνω γράφει και ολόκληρες προτάσεις στο πληκτρολόγιο — μόνο όταν είναι
              ανοιχτός ο «Έλεγχος με Claude»)
            - Γράψε: 1 κεφαλαία, 2 μικρά, 3 το όνομά του, 4 λέξεις, 5 λέξεις από μνήμης
            - Διάλογοι: πόσο δύσκολο διάλογο δέχεται — η κουκκίδα n παίρνει τους διαλόγους μέχρι και
              το επίπεδο n
            - Τραγούδα και πες το: μέχρι πόσες συλλαβές έχει η φράση — 2, 4, 6, 8, όσες να 'ναι
            - Δεξί χέρι: πόσο μικρός γίνεται ο στόχος
            - Βήματα: η κουκκίδα n παίρνει τις δουλειές μέχρι και τη δυσκολία n — 1 τρία βήματα,
              2 τέσσερα, 3 πέντε, 4 έξι, 5 έξι και ένα βήμα που ανήκει σε άλλη δουλειά
            - SQL: η κουκκίδα n παίρνει τα επίπεδα μέχρι και το n — 1 βάζει τις λέξεις της ερώτησης
              στη σειρά, 2 διαλέγει την ερώτηση που δίνει ένα αποτέλεσμα, 3 συμπληρώνει τη λέξη που
              λείπει, 4 γράφει ο ίδιος την ερώτηση, 5 δύο πίνακες μαζί (JOIN)
            Η κουκκίδα διαλέγει ζώνη· μέσα στη ζώνη το επίπεδο ανεβοκατεβαίνει μόνο του όπως πάντα,
            και το «levels» της Εστίασης μετακινεί μαζί και την κουκκίδα. Το «Μίλα» δεν έχει
            κουκκίδες.

            Δύο πράγματα που παρεξηγούνται εύκολα:

            Η βοήθεια είναι 0–4 (0 = το είπε μόνος του με την εικόνα, 1 πρώτος ήχος, 2 πρώτη
            συλλαβή, 3 άκουσε τη λέξη, 4 άκουσε και είδε τη λέξη). Το κουμπί «Άκου» είναι πάντα
            διαθέσιμο, τον ενθαρρύνουμε να το πατάει (μάθηση χωρίς λάθη) και κάθε πάτημα γράφει
            βοήθεια τουλάχιστον 3. Άρα υψηλή μέση βοήθεια δεν είναι από μόνη της οπισθοδρόμηση —
            μπορεί να σημαίνει ότι διάλεξε να ακούσει. Μην προτείνεις ποτέ να του στερήσουν το
            «Άκου».

            Το «κουτί» είναι επανάληψη με κενά, 1–5 (1 καινούργια λέξη, 5 μαθημένη). Παύλα σημαίνει
            ότι δεν έχει κουτί, όχι ότι ξέχασε τη λέξη.

            Σύγκρινε με τις προηγούμενες συμβουλές σου και πες καθαρά τι άλλαξε από τότε: τι πήγε
            καλύτερα, τι δεν κουνήθηκε, τι δεν δοκιμάστηκε καθόλου. Αν είναι η πρώτη φορά, πες το.

            Να είσαι συγκεκριμένος. Ονόμασε λέξεις, ονόμασε πρώτους ήχους, ονόμασε ασκήσεις, πες τι
            να ηχογραφήσουν ή τι να φωτογραφίσουν οι φροντιστές, και πες αν ένα επίπεδο πρέπει να
            ανέβει ή να κατέβει. Χρησιμοποίησε μόνο λέξεις που υπάρχουν στην αναφορά. Αν τα στοιχεία
            είναι λίγα, πες το απλά αντί να μαντέψεις.

            Και πες σε ποια κουκκίδα να πάει κάθε άσκηση που το χρειάζεται, με τον αριθμό της
            («Αριθμοί: πήγαινε στο 4») και με τον λόγο σε μία γραμμή. Ο στόχος είναι να πετυχαίνει
            περίπου τέσσερα στα πέντε: πολύ ψηλά σωστά σημαίνει ότι η κουκκίδα πρέπει να ανέβει, και
            το είπε και ο ίδιος ότι η εφαρμογή ήταν εύκολη. Μην ανεβάζεις πάνω από μία κουκκίδα τη
            φορά σε μία άσκηση, και άσε ήσυχες τις ασκήσεις που δεν έχουν αρκετά στοιχεία. Οι
            κουκκίδες είναι δικές του — γράφεις πρόταση προς αυτόν και τους φροντιστές, όχι εντολή
            που εκτελεί η εφαρμογή.

            Απάντησε στα ελληνικά, με απλά λόγια, σαν ενήλικας προς ενήλικες. Τίποτα που να τον
            υποτιμά.

            Γράψε ακριβώς αυτές τις τρεις ενότητες, με αυτούς ακριβώς τους τίτλους και με αυτή τη
            σειρά:

            $CAREGIVERS
            5 έως 10 σύντομες, συγκεκριμένες προτάσεις για το τι να κάνουν οι φροντιστές την επόμενη
            εβδομάδα. Μέσα σε αυτές πες και τις κουκκίδες που προτείνεις, μία γραμμή η καθεμιά.

            $DIMITRIS
            Το πολύ δύο σύντομες, ζεστές προτάσεις προς τον ίδιο τον Δημήτρη, σε δεύτερο πρόσωπο, σε
            πολύ απλά ελληνικά, χωρίς αριθμούς και χωρίς ποσοστά. Το τηλέφωνο θα τις διαβάσει
            δυνατά, οπότε γράψε τες όπως θα τις έλεγες.

            $FOCUS
            Μόνο ένα αντικείμενο JSON, σε μία γραμμή, χωρίς σχόλια και χωρίς ``` γύρω του:
            {"items":["καφές","ψωμί"],"sounds":["π"],"modules":["WORDCOACH"],"levels":{"numbers":3,"sentences":2,"trace":2},"why":"γιατί αυτά"}
            Οι λέξεις στο items πρέπει να είναι λέξεις που υπάρχουν στην αναφορά, γραμμένες ακριβώς
            όπως εκεί. Τα sounds είναι πρώτοι ήχοι. Τα modules γράφονται με τον κωδικό τους και όχι
            με το ελληνικό όνομα — οι κωδικοί είναι στη λίστα των ασκήσεων πιο πάνω. Το levels είναι
            προαιρετικό και δέχεται μόνο numbers, sentences και trace· βάλε μόνο όσα θέλεις να
            αλλάξουν, και θυμήσου ότι ένα επίπεδο μετακινεί μαζί του και την κουκκίδα της άσκησης. Η
            εφαρμογή κρατάει θέσεις για αυτές τις λέξεις στην επόμενη άσκησή του και δίνει σειρά σε
            αυτά τα modules, οπότε κράτα τες λίγες: 3 έως 8 λέξεις και το πολύ δύο modules.

            Οι τρεις τίτλοι είναι οι μόνες γραμμές που ξεκινούν με ##. Μην γράψεις τους τίτλους μέσα
            στο κείμενο. Καθόλου άλλο markdown: χωρίς αστερίσκους για έντονα γράμματα, με παύλες για
            τις λίστες.
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

        /** A heading is a whole line of its own — never a heading named inside a sentence. */
        private val CAREGIVERS_LINE = Regex("^##\\s*Για τους φροντιστές\\s*:?\\s*$", RegexOption.MULTILINE)
        private val DIMITRIS_LINE = Regex("^##\\s*Για τον Δημήτρη\\s*:?\\s*$", RegexOption.MULTILINE)
        private val FOCUS_LINE = Regex("^##\\s*Εστίαση\\s*:?\\s*$", RegexOption.MULTILINE)

        /**
         * Splits the answer on the two headings. A reply that lost them is not thrown away — the
         * caregivers get the whole thing and nothing is read out loud to Dimitris, which is the
         * safe way round: an unsplit answer is a full answer in the wrong shape, and reading an
         * answer meant for caregivers to him would be the actual harm.
         *
         * The headings are matched as whole lines, and the **last** such line wins. `indexOf` was
         * not enough: the prompt names both headings, so a model writing «διάβασέ του την ενότητα
         * ## Για τον Δημήτρη» *inside* the caregivers' advice would have moved the split onto that
         * sentence — and the rest of the caregivers' text would then have been read out loud to a
         * man with expressive aphasia, which is precisely what this function exists to prevent.
         */
        fun parse(text: String): Advice {
            // The focus is cut off the end first, before either half is decided. It is machine
            // text: a JSON object read out loud to a man with expressive aphasia — which is exactly
            // what would happen if it stayed inside the section that gets spoken — is the same kind
            // of harm the split below exists to prevent. Cutting it here also means an answer whose
            // headings are mangled still yields a usable focus, and still says nothing to him.
            val f = FOCUS_LINE.findAll(text).lastOrNull()
            val body = if (f == null) text else text.substring(0, f.range.first)
            val focusJson = if (f == null) "" else jsonObject(text.substring(f.range.last + 1))

            val c = CAREGIVERS_LINE.findAll(body).lastOrNull()
            val d = DIMITRIS_LINE.findAll(body).lastOrNull()
            if (c == null || d == null || d.range.first < c.range.last) return Advice(plain(body), "", focusJson)
            return Advice(
                caregivers = plain(body.substring(c.range.last + 1, d.range.first)),
                dimitris = forDimitris(body.substring(d.range.last + 1)),
                focusJson = focusJson,
            )
        }

        /**
         * The one JSON object in what follows the «## Εστίαση» heading, on one line: from the first
         * `{` forward to the brace that closes it, counting depth and skipping over anything inside
         * a string.
         *
         * Braces rather than a parse: the parsing is [Focus]'s job and it is deliberately forgiving,
         * so all this has to do is find the object and refuse to invent one. A model that wrapped it
         * in ``` or wrote a sentence around it still gets read; a model that wrote nothing at all
         * gives "" and the app simply plans the next session as it always did.
         *
         * It used to run first-brace to **last**-brace, which is the same bug
         * [JudgeContract.jsonObject] was fixed for: one stray `}` in a sentence after the object, or
         * a second object, and the substring is not JSON any more — Gson refuses trailing content —
         * so a perfectly good focus became no focus at all and his next session was planned as
         * though the advisor had said nothing about it.
         *
         * The scan itself **is** [JudgeContract.jsonObject]. It used to be a second copy of it, kept
         * byte for byte in step by hand, which is the one way two scanners whose whole value is that
         * they agree stop agreeing. All that is left here is the shape this caller wants: "" for
         * nothing found rather than null, and the object flattened onto one line so a model that
         * pretty-printed it still gives [Focus] something to parse.
         */
        internal fun jsonObject(raw: String): String =
            JudgeContract.jsonObject(raw)?.replace(NEWLINES, " ")?.trim().orEmpty()

        private val NEWLINES = Regex("\\s*\\R\\s*")

        /**
         * The model's markdown, taken off before either half reaches a screen or the speaker. The
         * prompt asks for plain text; models emit `**bold**` and `###` sub-headings anyway, the
         * screen renders them literally, and TTS reads an asterisk out loud at a man who cannot ask
         * what it means. Bullets are kept: a dash is a list either way.
         */
        internal fun plain(text: String): String = text.trim().lines().joinToString("\n") { line ->
            line.replace(MARKDOWN_EMPHASIS, "").replaceFirst(MARKDOWN_HEADING, "").trimEnd()
        }.trim()

        private val MARKDOWN_EMPHASIS = Regex("\\*\\*|__")
        private val MARKDOWN_HEADING = Regex("^\\s*#{1,6}\\s*")

        /**
         * His half, capped once, here — so what is on the screen and what is spoken are the same
         * words. Cut at the end of a sentence when there is one late enough to be worth keeping;
         * otherwise cut plainly rather than hand him a two-word stub.
         */
        internal fun forDimitris(raw: String): String {
            val text = plain(raw)
            if (text.length <= MAX_DIMITRIS) return text
            val head = text.take(MAX_DIMITRIS)
            val end = head.lastIndexOfAny(SENTENCE_ENDS)
            return (if (end >= MIN_SENTENCE) head.substring(0, end + 1) else head).trim()
        }
    }
}
