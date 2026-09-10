package gr.dimitris.app.core.speech

import android.Manifest
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import gr.dimitris.app.DimitrisApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The real recogniser on this device, on the two things about it that do not need a Greek engine.
 *
 * This emulator *has* a recognition service — it answers `ERROR_SERVER_DISCONNECTED` (11) — so the
 * network path is genuinely walked here: a window really opens, really fails, and really comes back.
 * What cannot be walked is the on-device path, because there is no Greek model to install.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSpeechToTextTest {
    @get:Rule val mic: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.RECORD_AUDIO)

    private val graph get() = ApplicationProvider.getApplicationContext<DimitrisApp>().graph

    private val made = mutableListOf<File>()

    private fun recogniser() = AndroidSpeechToText(graph.app) { graph.files.newWavFile().also { made += it } }

    @After fun clean() { made.forEach { it.delete() } }

    /**
     * «Στοπ» ends the window, wherever in it his thumb lands.
     *
     * The screens raise the indicator and put «Στοπ» under his thumb *synchronously*, then launch
     * the coroutine — so a stop can arrive before `listen()` has asked the engine what languages it
     * has (up to five seconds on a cold one) or opened a microphone. That is why the flag is now
     * cleared at the head of the wait, before either of those, and why a stop landing before the
     * first session ends the window then and there instead of opening one.
     *
     * Driven the way an impatient thumb really behaves — «Στοπ» pressed and pressed again until
     * something happens — because *which* millisecond a single tap lands in is the one thing a test
     * on a shared emulator cannot pin. What it does pin is the promise: a window he stopped comes
     * back as a success with nothing heard, never as a failure about the phone. Before the first
     * session that is an empty transcript; inside one, `stopListening()` brings back whatever was
     * heard, which on a silent emulator is nothing. A window nobody stopped comes back as this
     * device's `ERROR_SERVER_DISCONNECTED` (11), which is a failure — so the two are told apart.
     *
     * The order the flag is cleared in is code, not timing, and is not reachable from here: see the
     * report's fix-round-2 notes.
     *
     * Independent of what this particular image can hear, too. A phone whose engine resolves to
     * [OnDeviceSupport.Engine.NONE] — no network, no on-device model — used to answer the stopped
     * window with «Χρειάζεται σύνδεση» instead, because the engine's verdict was read before his:
     * that check now comes second, so a window he ended is his answer on every image.
     */
    @Test fun aStoppedWindowComesBackAsHisStopAndNotAsAFailure() = runBlocking {
        val stt = recogniser()
        val heard = async(Dispatchers.Default) { stt.listen() }
        val thumb = launch(Dispatchers.Default) {
            while (heard.isActive) {
                withContext(Dispatchers.Main) { stt.stop() }
                delay(STOP_EVERY_MS)
            }
        }

        val result = heard.await()
        thumb.cancelAndJoin()

        assertTrue("«Στοπ» was swallowed: ${result.exceptionOrNull()}", result.isSuccess)
        assertEquals("nothing was said into a silent microphone", "", result.getOrThrow().text)
    }

    /**
     * And the wait it ended really is over: nothing keeps listening, and the next «Μίλα» is free to
     * open its own window rather than being refused as a second one.
     */
    @Test fun theStoppedWaitReleasesItsTurn() = runBlocking {
        val stt = recogniser()
        val first = async(Dispatchers.Default) { stt.listen() }
        withContext(Dispatchers.Main) { stt.stop() }
        first.await()

        // No `ALREADY_LISTENING` (-2): the previous wait let go of its claim on the way out.
        val second = stt.listen()
        val code = (second.exceptionOrNull() as? SpeechFailure.NotWorking)?.code
        assertTrue("the second window was refused as an overlap", code != AndroidSpeechToText.ALREADY_LISTENING)
        assertEquals("and the bar is back to nothing", 0f, stt.level.value, 0.001f)
    }

    /** How often the thumb presses «Στοπ» again while it waits for something to happen. */
    private val STOP_EVERY_MS = 10L

    /** A wait that nobody stopped still ends by itself, and does not hold the next one out. */
    @Test fun aWindowNobodyStoppedStillEnds() = runBlocking {
        val stt = recogniser()
        val result = stt.listen()
        // Whatever this device's engine made of it, the wait is over and its claim is released.
        delay(50)
        val again = stt.listen()
        val code = (again.exceptionOrNull() as? SpeechFailure.NotWorking)?.code
        assertTrue("the wait never let go: ${result.exceptionOrNull()}", code != AndroidSpeechToText.ALREADY_LISTENING)
    }
}
