package gr.dimitris.app.core.speech

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import android.speech.tts.TextToSpeech as AndroidTts

class AndroidTextToSpeech(context: Context) : TextToSpeech {
    private val greek = Locale("el", "GR")
    private val ready = CompletableDeferred<Boolean>()
    private val waiting = ConcurrentHashMap<String, CancellableContinuation<Result<Unit>>>()
    private val engine: AndroidTts = AndroidTts(context.applicationContext) { status ->
        ready.complete(status == AndroidTts.SUCCESS)
    }

    init {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onDone(utteranceId: String) {
                waiting.remove(utteranceId)?.resume(Result.success(Unit))
            }
            override fun onStop(utteranceId: String, interrupted: Boolean) {
                waiting.remove(utteranceId)?.resume(Result.success(Unit))
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = onError(utteranceId, -1)
            override fun onError(utteranceId: String, errorCode: Int) {
                waiting.remove(utteranceId)?.resume(Result.failure(TtsException("Σφάλμα φωνής ($errorCode)")))
            }
        })
    }

    private suspend fun isReady(): Boolean = withTimeoutOrNull(INIT_TIMEOUT_MS) { ready.await() } ?: false

    override suspend fun isGreekAvailable(): Boolean =
        isReady() && engine.isLanguageAvailable(greek) >= AndroidTts.LANG_AVAILABLE

    override suspend fun speak(text: String, rate: Float): Result<Unit> {
        if (!isReady()) return Result.failure(TtsException("Η φωνή δεν ξεκίνησε"))
        if (engine.setLanguage(greek) < AndroidTts.LANG_AVAILABLE) {
            return Result.failure(TtsException("Δεν υπάρχει ελληνική φωνή"))
        }
        engine.setSpeechRate(rate)
        val id = UUID.randomUUID().toString()
        return suspendCancellableCoroutine { cont ->
            waiting[id] = cont
            val queued = engine.speak(text, AndroidTts.QUEUE_FLUSH, null, id)
            if (queued != AndroidTts.SUCCESS && waiting.remove(id) != null) {
                cont.resume(Result.failure(TtsException("Δεν μπόρεσα να μιλήσω")))
            }
            cont.invokeOnCancellation { waiting.remove(id); engine.stop() }
        }
    }

    override fun stop() { engine.stop() }

    fun shutdown() {
        engine.stop()
        waiting.keys.toList().forEach { id ->
            waiting.remove(id)?.resume(Result.failure(TtsException("Η φωνή έκλεισε")))
        }
        engine.shutdown()
    }

    private companion object {
        const val INIT_TIMEOUT_MS = 10_000L
    }
}

/** Opens the system screen where the Greek voice can be installed. Falls back to the TTS settings, then general settings. */
fun openTtsInstaller(context: Context) {
    val attempts = listOf(
        Intent(AndroidTts.Engine.ACTION_INSTALL_TTS_DATA),
        Intent("com.android.settings.TTS_SETTINGS"),
        Intent(Settings.ACTION_SETTINGS),
    )
    for (intent in attempts) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (_: ActivityNotFoundException) { }
    }
}
