package gr.dimitris.app.core.speech

/**
 * Which recogniser this phone would hear Greek with right now, as a pure decision table.
 *
 * This exists because of exactly one field report. On Chris' phone the platform recogniser worked
 * for a day and then stopped: first `ERROR_LANGUAGE_NOT_SUPPORTED` (12) — the phone had fallen back
 * to a recognition service with no Greek in it — and then `ERROR_NETWORK` (2), because the engine it
 * did find was a cloud one and the phone was offline. The app said «δεν λειτούργησε» to both, which
 * told a man with Broca's aphasia nothing and his caregiver less.
 *
 * Spec §13 answers it: transcription stays free and **on the phone**. So the question "can this
 * phone hear Greek without a connection, and if not, what is missing?" is worth a table of its own —
 * one that can be argued with in a plain unit test, because the emulator has no speech engine at all
 * and none of this can be exercised on it. [AndroidSpeechToText] asks the platform for the five
 * facts; everything that follows from them is decided here.
 */
object OnDeviceSupport {
    /** What will answer «Μίλα». */
    enum class Engine {
        /**
         * Greek is on the phone. This is the one that makes the single speech control possible: the
         * app holds the microphone itself, so the same audio can be the transcript *and* the take
         * he plays back — see [PcmTake].
         */
        ON_DEVICE,

        /**
         * The phone has an on-device engine that knows about Greek but has not downloaded it. A
         * caregiver can fix that from the settings in one tap, so the row says so and offers it.
         * Until then the network engine answers, exactly as it did before this phase.
         */
        NEEDS_DOWNLOAD,

        /** A cloud recogniser, and the phone has a connection. What phases 11 and before used. */
        NETWORK,

        /**
         * Nothing can listen: no engine at all, or a cloud-only engine with no connection. The
         * second is Chris' code 2, and it is the case that must be *said* rather than shrugged at.
         */
        NONE,
    }

    /** Whether this engine records his voice itself, which is what folds two buttons into one. */
    val Engine.keepsTake: Boolean get() = this == Engine.ON_DEVICE

    /** Whether «Μίλα» can open a window at all. */
    val Engine.listens: Boolean get() = this != Engine.NONE

    /**
     * The on-device recogniser APIs — `isOnDeviceRecognitionAvailable`,
     * `createOnDeviceSpeechRecognizer`, `checkRecognitionSupport`, `triggerModelDownload` — all
     * arrived in Android 13. Below it there is no on-device path to take, whatever the phone has
     * installed.
     */
    const val ON_DEVICE_SDK = 33

    /**
     * The language subtag the engine's lists are matched on.
     *
     * Prefix rather than equality because the platform is free to answer `el`, `el-GR`, `ell` or
     * `ell-GR` and all four are the language he speaks; the region never matters, because there is
     * only one Greek in this app.
     */
    const val GREEK = "el"

    /** Whether one of the engine's language lists contains Greek. */
    fun speaksGreek(languages: List<String>): Boolean = languages.any(::isGreek)

    /**
     * The five facts, and the only place they are weighed.
     *
     * Read top to bottom:
     *
     * * no on-device path on this Android, or no on-device engine installed → the cloud, if there is
     *   a connection, and otherwise nothing. **This is Chris' code 2**: an offline phone with a
     *   cloud-only engine is [Engine.NONE], and the line he gets says «σύνδεση» rather than blaming
     *   his voice;
     * * Greek already downloaded → [Engine.ON_DEVICE], which is free, offline and able to hand the
     *   app his own audio;
     * * Greek known but not downloaded → [Engine.NEEDS_DOWNLOAD]. **This is Chris' code 12**, and
     *   the answer to it is a button in the settings rather than a dead «Μίλα»;
     * * an engine that does not know Greek at all → the cloud, or nothing. Said plainly in the row
     *   as «δεν υποστηρίζεται», because no tap of his will ever fix it.
     *
     * [online] is deliberately the last word and never the first: a phone that can hear Greek on its
     * own does not care whether it has a connection, which is the whole point of the phase.
     */
    fun decide(
        sdk: Int,
        onDeviceAvailable: Boolean,
        installedLanguages: List<String>,
        supportedLanguages: List<String>,
        online: Boolean,
    ): Engine = when {
        sdk < ON_DEVICE_SDK || !onDeviceAvailable -> cloud(online)
        speaksGreek(installedLanguages) -> Engine.ON_DEVICE
        speaksGreek(supportedLanguages) -> Engine.NEEDS_DOWNLOAD
        else -> cloud(online)
    }

    private fun cloud(online: Boolean) = if (online) Engine.NETWORK else Engine.NONE

    /**
     * The language subtag, lowercased, with either separator: `el_GR` is as good as `el-GR`, and an
     * engine that answers `EL-gr` is answering Greek.
     */
    private fun isGreek(tag: String): Boolean =
        tag.trim().lowercase().substringBefore('-').substringBefore('_').startsWith(GREEK)

    /** The four things the settings row can say about Greek without a connection. */
    enum class Greek { INSTALLED, MISSING, DOWNLOADING, UNSUPPORTED }

    /**
     * What the row says, given the engine and whether a download is running.
     *
     * [Engine.ON_DEVICE] outranks [downloading] so that the instant a download lands the row reads
     * «εγκατεστημένα» — the caregiver should not have to wait for a poll to finish before the phone
     * admits it can do the thing it can now do.
     */
    fun greekFor(engine: Engine, downloading: Boolean): Greek = when {
        engine == Engine.ON_DEVICE -> Greek.INSTALLED
        downloading -> Greek.DOWNLOADING
        engine == Engine.NEEDS_DOWNLOAD -> Greek.MISSING
        else -> Greek.UNSUPPORTED
    }

    /** The one line the caregiver reads, in her language, with no jargon and no error code in it. */
    fun lineFor(greek: Greek): String = GREEK_LINE + when (greek) {
        Greek.INSTALLED -> "εγκατεστημένα"
        Greek.MISSING -> "δεν υπάρχουν"
        Greek.DOWNLOADING -> "λήψη…"
        Greek.UNSUPPORTED -> "δεν υποστηρίζεται"
    }

    /**
     * "Greek without a connection", because that is the thing she is being told about — not "the
     * on-device model", which is a sentence about software rather than about her phone.
     */
    const val GREEK_LINE = "Ελληνικά χωρίς σύνδεση: "

    /** The one button that fixes [Engine.NEEDS_DOWNLOAD], and the only one in the row. */
    const val DOWNLOAD = "Λήψη ελληνικών"
}
