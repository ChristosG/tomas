package gr.dimitris.app.core.speech

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

class AndroidTextToSpeechTest {
    @Test fun speaksGreekWhenVoiceInstalled() = runBlocking {
        val tts = AndroidTextToSpeech(ApplicationProvider.getApplicationContext())
        try {
            val greek = withTimeout(20_000) { tts.isGreekAvailable() }
            assumeTrue("Greek voice not installed on this device", greek)
            val result = withTimeout(20_000) { tts.speak("Καλημέρα Δημήτρη", 0.9f) }
            assertTrue(result.exceptionOrNull()?.toString() ?: "", result.isSuccess)
        } finally { tts.shutdown() }
    }
}
