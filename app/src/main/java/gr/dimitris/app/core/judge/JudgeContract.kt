package gr.dimitris.app.core.judge

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import gr.dimitris.app.core.data.Adapt
import gr.dimitris.app.core.greek.Greek

/**
 * What the app is asking about. Four questions, because they are four different judgements and a
 * single "is this right?" would get all four of them wrong.
 */
enum class Kind {
    /** One word against one target. His articulation is effortful; a close form is the word. */
    WORD,

    /** A whole sentence against a target sentence: the meaning and the grammar both count. */
    SENTENCE,

    /**
     * An open answer to an open question. There is no single right answer — this is the one spec §13
     * added, and the whole point of it is that many replies are valid.
     */
    DIALOGUE,

    /**
     * Not a judgement at all: the content words he produced, and the full grammatical sentence
     * wanted back. This is the sentence expansion §13 calls the core therapy.
     */
    EXPAND,
}

/** Where a verdict came from. The caller writes it into `detail.judge` so a row can be read later. */
enum class Source { JUDGE, LOCAL }

/**
 * One turn, as little of it as possible. Text only — the transcript, never the audio — and nothing
 * about him beyond what he just said: spec §13's privacy rule is kept by what this class is able to
 * carry, not by remembering to leave things out at the call site.
 *
 * [prompt] is the question he was answering (a dialogue line), [target] the words the exercise was
 * after, [heard] what the recogniser or the keyboard produced. [difficulty] is his own 1–5 dot row;
 * it is sent so the model knows how much grammar to insist on, and for nothing else.
 *
 * [intent] is what a good answer has to *convey*, in Greek, as the caregiver or the seed wrote it:
 * «λέει τι θέλει και πόσο». It is the difference between an open question and a guessing game — the
 * [target] is one right answer out of many, and this says what they all have in common. It is written
 * by a person about the exercise, never about him.
 *
 * A [Kind.DIALOGUE] carries one wherever the line was written with one. So does the one [Kind.SENTENCE]
 * board that asks for a sentence rather than for *the* sentence — «Γράψε»'s typed level, where the
 * picture admits any correct sentence about the word and the target is only the example the app
 * happened to build ([gr.dimitris.app.modules.trace.TraceViewModel.WRITE_A_SENTENCE]). A WORD or an
 * EXPAND never has one: there is nothing an intent could say that the target does not.
 */
data class Ask(
    val kind: Kind,
    val prompt: String? = null,
    val target: String? = null,
    val heard: String,
    val difficulty: Int = 1,
    val intent: String? = null,
)

/**
 * What the app does with the turn.
 *
 * [accept] confirms it for him. [expanded] is the full grammatical Greek sentence to show and speak
 * when his was telegraphic — present on an accepted turn as often as on a refused one, because
 * «φάρμακα πρέπει πάρω» is a correct answer to a question *and* a sentence worth hearing whole.
 * [feedback] is one warm Greek line, or nothing. [score] is 0–1 and exists for the caregiver's
 * progress screen; no screen gates anything on it.
 */
data class Verdict(
    val accept: Boolean,
    val expanded: String? = null,
    val feedback: String? = null,
    val score: Float = 0f,
    val source: Source,
) {
    /**
     * The shape **every** caller writes under `detail.judge` on the attempt row — Tasks 3, 6 and 7,
     * and anything after them. Here rather than in three view models so that the caregiver's progress
     * reader has one shape to support instead of three that drifted: the key names, whether `expanded`
     * is included at all, and whether `ms` covers the local path are all decided once, in this
     * function.
     *
     * ```
     * val detail = Adapt.detail { kept("judge", verdict.detail(ms = now() - began)) }
     * ```
     *
     * [ms] is how long the verdict took to arrive, measured by the caller around its own
     * [TurnJudge.judge] call — so a `LOCAL` row with `ms` near zero is the fallback answering
     * instantly and a `LOCAL` row with `ms` near 8 000 is a timeout. That difference is the whole
     * reason the field is worth keeping.
     *
     * `expanded` is absent rather than null when there was no expansion, which is
     * [gr.dimitris.app.core.data.Adapt]'s rule for every other detail key, and it is capped at
     * [Adapt.MAX_TEXT] because an attempt row leaves the phone twice — it syncs to the father's
     * server and it goes to Claude inside the journey report.
     */
    fun detail(ms: Long): Map<String, Any?> = buildMap {
        put("source", source.name)
        put("accept", accept)
        put("ms", ms.coerceAtLeast(0L))
        expanded?.trim()?.takeIf { it.isNotEmpty() }?.let { put("expanded", it.take(Adapt.MAX_TEXT)) }
    }
}

/**
 * The wire between the app and one Haiku call: what goes up, and how what comes back is read.
 *
 * Held apart from [TurnJudge] because these two halves are the part that can be wrong without
 * anything throwing — a prompt that stops producing JSON, a reply shape nobody anticipated — and
 * they should be provable in a plain unit test rather than only against the API.
 *
 * Reading the reply is deliberately forgiving in every direction but one. Prose around the object, a
 * ``` fence, a missing `score`, `"true"` instead of `true`: all read. The single exception is
 * [Verdict.accept] — a reply that does not say whether it accepted the turn is not a verdict, and
 * inventing one either way would either wall him (the thing the whole app is built not to do) or
 * confirm a word he never said. That reply returns null and [LocalJudge] decides instead, which is
 * exactly what the fallback is for.
 */
object JudgeContract {

    /**
     * Who he is and what is being asked, said once per request.
     *
     * Three sentences of profile and not a word more. Spec §13: "Nothing about his health beyond
     * what §1 states enters prompts" — and §1 is a longer list than this on purpose. The stroke, the
     * aphasia and the telegraphic speech are what a judgement of *this sentence* needs; his
     * acalculia, his hemiparesis and his apraxia are not, so they are not here. A judge that was
     * never told a fact cannot leak it.
     *
     * Fixed in the app, never editable, for the same reason
     * [gr.dimitris.app.caregiver.insights.ClaudeAdvisor]'s prompt is: this text decides what the
     * phone then says out loud to him.
     */
    val SYSTEM_PROMPT = """
        Κρίνεις τι είπε ο Δημήτρης σε μία άσκηση λόγου και απαντάς μόνο με JSON.

        Ο Δημήτρης είναι ενήλικας άνδρας στην Ελλάδα που είχε εγκεφαλικό στο αριστερό ημισφαίριο.
        Έχει αφασία Broca: καταλαβαίνει πολύ καλά, δυσκολεύεται να βγάλει τις λέξεις και η άρθρωση
        του κοστίζει. Ο λόγος του είναι τηλεγραφικός — λέει «φάρμακα πρέπει πάρω» και εννοεί «πρέπει
        να πάρω τα φάρμακα».

        Μιλάς σε έναν ενήλικα. Τίποτα παιδικό, τίποτα που να τον υποτιμά.

        Θα λάβεις ένα αντικείμενο JSON με έξι πεδία: kind, prompt, target, heard, difficulty, intent.
        Το heard είναι αυτό που είπε, όπως το έγραψε η αναγνώριση φωνής ή το πληκτρολόγιο. Το
        difficulty είναι 1 έως 5 και λέει πόση γραμματική να απαιτήσεις: στο 1 ελάχιστη, στο 5
        ολόκληρη πρόταση. Το intent είναι ο στόχος της γραμμής — τι πρέπει να πετύχει η απάντησή του,
        γραμμένο από τη φροντίστρια — και μπορεί να είναι null.

        Κρίνε ανάλογα με το kind:

        DIALOGUE: ανοιχτή ερώτηση, ανοιχτή απάντηση. Δέξου ΟΠΟΙΑΔΗΠΟΤΕ σχετική και λογική απάντηση
        στο prompt — δεν υπάρχει μία σωστή, πολλές είναι σωστές, και το target (αν υπάρχει) είναι
        απλώς ένα παράδειγμα. Αν υπάρχει intent, δέξου κάθε απάντηση που πετυχαίνει αυτόν τον στόχο,
        όπως κι αν είναι διατυπωμένη. Αν η απάντησή του είναι σχετική αλλά τηλεγραφική, βάλε accept
        true ΚΑΙ στο expanded ολόκληρη τη σωστή πρόταση, για να την επαναλάβει. Βάλε accept false
        μόνο όταν η απάντηση δεν έχει καμία σχέση με την ερώτηση· και τότε βάλε στο expanded μια
        σωστή, ολόκληρη απάντηση στην ερώτηση, για να την επαναλάβει — ποτέ accept false χωρίς
        expanded.

        WORD: δέξου το target, ή μια κοντινή προφορά ή κλίση του. Μικρές διαφορές ήχων, ένα χαμένο
        τελικό «ς», έναν τόνο αλλού: δεν είναι λάθος. Το expanded είναι null.

        SENTENCE: βάλε accept true όταν το νόημα συμφωνεί με το target ΚΑΙ η γραμματική στέκει. Αν
        υπάρχει intent, τότε το target είναι απλώς ένα παράδειγμα: δέξου ΚΑΘΕ σωστή ελληνική πρόταση
        που πετυχαίνει αυτόν τον στόχο, κι αν δεν είναι η ίδια πρόταση με το target. Αλλιώς accept
        false, και στο expanded ολόκληρη τη σωστή πρόταση — ποτέ accept false χωρίς expanded.

        EXPAND: δεν κρίνεις τίποτα. Το heard είναι λέξεις περιεχομένου· γύρνα στο expanded ολόκληρη
        τη σωστή ελληνική πρόταση που φτιάχνουν, με accept true. Πρώτο πρόσωπο ενεστώτα, εκτός αν οι
        λέξεις λένε άλλο χρόνο ή άλλο πρόσωπο. Μην προσθέσεις τίποτα που δεν είναι στις λέξεις —
        ούτε δικαιολογίες, ούτε λεπτομέρειες, ούτε χαιρετισμούς.

        Το feedback είναι μία ζεστή πρόταση προς τον ίδιο, το πολύ 12 ελληνικές λέξεις, ή null. Ποτέ
        «λάθος», ποτέ «όχι». Αν δεν έχεις κάτι ζεστό να πεις, βάλε null.

        Το score είναι αριθμός από 0 έως 1: πόσο καλά τα πήγε.

        Απάντησε με ΕΝΑ αντικείμενο JSON και τίποτα άλλο — χωρίς ``` γύρω του, χωρίς κείμενο πριν ή
        μετά, με αυτά ακριβώς τα τέσσερα κλειδιά:
        {"accept":true,"expanded":null,"feedback":null,"score":0.9}
    """.trimIndent()

    /**
     * The ask as one JSON object. All six keys always, nulls written out rather than left off: a
     * stable shape is one less thing for the model to interpret, and «target: null» is itself the
     * information that there is no single right answer.
     */
    fun userMessage(ask: Ask): String {
        val o = JsonObject()
        o.addProperty("kind", ask.kind.name)
        o.addProperty("prompt", ask.prompt?.trim()?.takeIf { it.isNotEmpty() })
        o.addProperty("target", ask.target?.trim()?.takeIf { it.isNotEmpty() })
        o.addProperty("intent", ask.intent?.trim()?.takeIf { it.isNotEmpty() })
        o.addProperty("heard", ask.heard.trim())
        // Clamped, because the prompt promises the model a 1–5 scale and a caller that passed 0 or 7
        // would be quietly asking it to interpret a number the prompt never described.
        o.addProperty("difficulty", ask.difficulty.coerceIn(MIN_DIFFICULTY, MAX_DIFFICULTY))
        return o.toString()
    }

    /**
     * The reply, or null when it is not a verdict at all and [LocalJudge] should answer instead.
     *
     * [ask] is needed for one rule only: an `expanded` that is simply his own words echoed back is not
     * an expansion, and handing it on would put «the full form, say it again» on the screen over the
     * sentence he has just said.
     *
     * **[Kind.EXPAND] is exempt from that rule**, and the exemption is the whole difference between
     * the two kinds of turn. A DIALOGUE or a SENTENCE was *judged*, and an echo there is the model
     * finding nothing to add to something he already got out. An EXPAND asked one question — "what
     * whole sentence do these words make?" — and «θέλω» + «καφέ» really does make «Θέλω καφέ.»: the
     * answer being nearly his own words back means the words were already a sentence, which is a
     * success and belongs on the screen as it came. Dropping it there cost the caregiver a row in
     * «Σφάλματα» saying Claude had failed, and him the tidied form.
     *
     * Whether a verdict is *usable* is [TurnJudge]'s question, not this one — see its EXPAND check.
     */
    fun parse(reply: String, ask: Ask): Verdict? {
        val raw = jsonObject(reply) ?: return null
        val element = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return null
        if (!element.isJsonObject) return null
        val o = element.asJsonObject
        val accept = bool(o, "accept") ?: return null
        return Verdict(
            accept = accept,
            expanded = str(o, "expanded")?.takeIf { ask.kind == Kind.EXPAND || !echoes(it, ask.heard) },
            feedback = warm(str(o, "feedback")),
            // A reply that judged the turn but forgot to score it is still a verdict; the score is
            // for a progress screen, not for him, so it is derived rather than thrown away.
            score = (num(o, "score") ?: if (accept) 1f else 0f).coerceIn(0f, 1f),
            source = Source.JUDGE,
        )
    }

    /**
     * The model's line, or nothing, and this is the last gate before a sentence a language model wrote
     * is read out loud to him.
     *
     * The prompt asks for at most [MAX_FEEDBACK_WORDS] warm Greek words and forbids [FORBIDDEN] —
     * «λάθος» and «όχι», the two words the whole app is built never to say to him. A prompt is an
     * instruction, not a guarantee: a refused SENTENCE is exactly the turn a model is most likely to
     * open with «Όχι ακριβώς…», and TTS would then say it. So the rule is enforced here as well, and
     * enforced by dropping the line rather than by editing it — [feedback] is optional, [LocalJudge]
     * proves a null one is a complete verdict, and half a sentence of encouragement is worse than none.
     *
     * Matched word by word on lower-cased, unaccented text, so «ΛΑΘΟΣ», «Λάθος» and «όχι,» are all the
     * same word, and «κόχη» is not one of them.
     */
    internal fun warm(feedback: String?): String? {
        val text = feedback?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (text.split(WHITESPACE).count { it.isNotEmpty() } > MAX_FEEDBACK_WORDS) return null
        return if (plain(text).split(NOT_LETTERS).any { it in FORBIDDEN }) null else text
    }

    /** The same words he just said, back again. Compared as leniently as the gentle check compares. */
    private fun echoes(expanded: String, heard: String): Boolean {
        val said = key(heard)
        return said.isNotEmpty() && key(expanded) == said
    }

    private fun key(s: String): String = plain(s).replace(NOT_LETTERS, " ").trim()

    /**
     * Lower-cased, unaccented, and with both sigmas written the same way — so «ΛΑΘΟΣ», «Λάθος» and a
     * model that typed «λάθοσ» are one word, not three. The JDK's Greek lower-casing already picks the
     * final form by position; folding it away means nothing here depends on having picked the same one.
     */
    private fun plain(s: String): String =
        Greek.stripAccents(Greek.normalize(s)).replace(FINAL_SIGMA, MEDIAL_SIGMA)

    /**
     * The one JSON object in the reply: from the first `{` forward to the brace that closes it,
     * counting depth and skipping over anything inside a string.
     *
     * The prompt asks for one bare object; models wrap it in ``` or introduce it with a sentence
     * anyway, and throwing that away would mean falling back to local matching over punctuation. But
     * first-brace-to-*last*-brace was too greedy in the other direction: one closing brace anywhere in
     * trailing prose, or a second object after the first, and the substring is not JSON — Gson refuses
     * trailing content too — so a perfectly good verdict became a LOCAL fallback and a row in the log.
     */
    internal fun jsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until raw.length) {
            val c = raw[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                inString -> Unit
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return raw.substring(start, i + 1)
                }
            }
        }
        // Never closed: a reply cut off by max_tokens mid-object. Not a verdict.
        return null
    }

    /** `true`, and also `"true"` and `1`, because all three turn up. */
    private fun bool(o: JsonObject, key: String): Boolean? {
        val p = o.get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive ?: return null
        if (p.isBoolean) return p.asBoolean
        return when (runCatching { p.asString }.getOrNull()?.trim()?.lowercase()) {
            "true", "1", "yes", "ναι" -> true
            "false", "0", "no", "όχι" -> false
            else -> null
        }
    }

    /** A non-empty string, where JSON `null` and the literal word "null" both mean nothing. */
    private fun str(o: JsonObject, key: String): String? =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asString }.getOrNull() }
            ?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }

    private fun num(o: JsonObject, key: String): Float? =
        o.get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asFloat }.getOrNull() }
            ?.takeIf { it.isFinite() }

    /** The scale the prompt describes to the model. His own dot row, bounded by the caregiver. */
    const val MIN_DIFFICULTY = 1
    const val MAX_DIFFICULTY = 5

    /**
     * How long a line may be before it stops being encouragement. The prompt asks for at most twelve
     * Greek words; past that it is a paragraph, and a paragraph read at a man with expressive aphasia
     * is not feedback.
     */
    const val MAX_FEEDBACK_WORDS = 12

    /**
     * The two words he must never hear, unaccented and lower-cased as [warm] compares them. «λάθος»
     * and «όχι» are the whole of spec §12's errorless rule said out loud, and
     * [gr.dimitris.app.core.speech.GentleCheck.TRY_AGAIN] exists precisely so that neither is needed.
     */
    val FORBIDDEN = setOf("λαθοσ", "οχι")

    /** Final and medial sigma. Written as escapes so that nothing here rests on an invisible choice. */
    private const val FINAL_SIGMA = '\u03C2'
    private const val MEDIAL_SIGMA = '\u03C3'

    private val WHITESPACE = Regex("\\s+")
    private val NOT_LETTERS = Regex("[^\\p{L}\\p{N}]+")
}
