package gr.dimitris.app.core.judge

import com.google.gson.JsonObject
import com.google.gson.JsonParser

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
 */
data class Ask(
    val kind: Kind,
    val prompt: String? = null,
    val target: String? = null,
    val heard: String,
    val difficulty: Int = 1,
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
)

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

        Θα λάβεις ένα αντικείμενο JSON με πέντε πεδία: kind, prompt, target, heard, difficulty.
        Το heard είναι αυτό που είπε, όπως το έγραψε η αναγνώριση φωνής ή το πληκτρολόγιο. Το
        difficulty είναι 1 έως 5 και λέει πόση γραμματική να απαιτήσεις: στο 1 ελάχιστη, στο 5
        ολόκληρη πρόταση.

        Κρίνε ανάλογα με το kind:

        DIALOGUE: ανοιχτή ερώτηση, ανοιχτή απάντηση. Δέξου ΟΠΟΙΑΔΗΠΟΤΕ σχετική και λογική απάντηση
        στο prompt — δεν υπάρχει μία σωστή, πολλές είναι σωστές, και το target (αν υπάρχει) είναι
        απλώς ένα παράδειγμα. Αν η απάντησή του είναι σχετική αλλά τηλεγραφική, βάλε accept true ΚΑΙ
        στο expanded ολόκληρη τη σωστή πρόταση, για να την επαναλάβει. Βάλε accept false μόνο όταν η
        απάντηση δεν έχει καμία σχέση με την ερώτηση.

        WORD: δέξου το target, ή μια κοντινή προφορά ή κλίση του. Μικρές διαφορές ήχων, ένα χαμένο
        τελικό «ς», έναν τόνο αλλού: δεν είναι λάθος. Το expanded είναι null.

        SENTENCE: βάλε accept true όταν το νόημα συμφωνεί με το target ΚΑΙ η γραμματική στέκει.
        Αλλιώς accept false, και στο expanded ολόκληρη τη σωστή πρόταση — ποτέ accept false χωρίς
        expanded.

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
     * The ask as one JSON object. All five keys always, nulls written out rather than left off: a
     * stable shape is one less thing for the model to interpret, and «target: null» is itself the
     * information that there is no single right answer.
     */
    fun userMessage(ask: Ask): String {
        val o = JsonObject()
        o.addProperty("kind", ask.kind.name)
        o.addProperty("prompt", ask.prompt?.trim()?.takeIf { it.isNotEmpty() })
        o.addProperty("target", ask.target?.trim()?.takeIf { it.isNotEmpty() })
        o.addProperty("heard", ask.heard.trim())
        o.addProperty("difficulty", ask.difficulty)
        return o.toString()
    }

    /**
     * The reply, or null when it is not a verdict at all and [LocalJudge] should answer instead.
     *
     * [ask] is needed for one rule only: an EXPAND whose reply carries no sentence answered the one
     * question it was asked with nothing, so it is no more usable than prose would have been.
     */
    fun parse(reply: String, ask: Ask): Verdict? {
        val raw = jsonObject(reply) ?: return null
        val element = runCatching { JsonParser.parseString(raw) }.getOrNull() ?: return null
        if (!element.isJsonObject) return null
        val o = element.asJsonObject
        val accept = bool(o, "accept") ?: return null
        val expanded = str(o, "expanded")
        if (ask.kind == Kind.EXPAND && expanded == null) return null
        return Verdict(
            accept = accept,
            expanded = expanded,
            feedback = str(o, "feedback"),
            // A reply that judged the turn but forgot to score it is still a verdict; the score is
            // for a progress screen, not for him, so it is derived rather than thrown away.
            score = (num(o, "score") ?: if (accept) 1f else 0f).coerceIn(0f, 1f),
            source = Source.JUDGE,
        )
    }

    /**
     * Braces to braces. The prompt asks for one bare object; models wrap it in ``` or introduce it
     * with a sentence anyway, and throwing that away would mean falling back to local matching over
     * punctuation.
     */
    internal fun jsonObject(raw: String): String? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return raw.substring(start, end + 1)
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
}
