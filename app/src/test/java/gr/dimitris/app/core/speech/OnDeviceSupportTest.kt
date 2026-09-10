package gr.dimitris.app.core.speech

import gr.dimitris.app.core.speech.OnDeviceSupport.Engine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision table, exhaustively, including the two failures Chris actually saw on his phone.
 *
 * None of this can be exercised on the emulator: it has no speech engine at all, so
 * `isOnDeviceRecognitionAvailable` is false there and every row below would collapse to the same
 * answer. This is the proof, and Chris' phone is the confirmation.
 */
class OnDeviceSupportTest {
    private val greek = listOf("el-GR")
    private val none = emptyList<String>()

    // Chris' two cases, first, by name.

    /**
     * Code 12, `ERROR_LANGUAGE_NOT_SUPPORTED`: the phone had an on-device engine that knows Greek
     * but had not downloaded it. Before this phase that was a dead «Μίλα» and one useless line; now
     * it is a button in the settings.
     */
    @Test fun `an engine that knows Greek but has not downloaded it asks for the download`() =
        assertEquals(
            Engine.NEEDS_DOWNLOAD,
            OnDeviceSupport.decide(sdk = 34, onDeviceAvailable = true, installedLanguages = none, supportedLanguages = greek, online = true),
        )

    /** The same phone offline. The download is still the answer: nothing about it needs a connection to be *said*. */
    @Test fun `and it still asks for it offline`() =
        assertEquals(
            Engine.NEEDS_DOWNLOAD,
            OnDeviceSupport.decide(sdk = 34, onDeviceAvailable = true, installedLanguages = none, supportedLanguages = greek, online = false),
        )

    /**
     * Code 2, `ERROR_NETWORK`: a cloud-only engine and no connection. This is the row that earns the
     * «σύνδεση» line — the app's own trouble, said as the app's own trouble.
     */
    @Test fun `a cloud engine with no connection can do nothing, and says so`() =
        assertEquals(
            Engine.NONE,
            OnDeviceSupport.decide(sdk = 34, onDeviceAvailable = false, installedLanguages = none, supportedLanguages = none, online = false),
        )

    // Greek on the phone: the whole point of the phase.

    @Test fun `Greek downloaded is the on-device engine`() =
        assertEquals(
            Engine.ON_DEVICE,
            OnDeviceSupport.decide(sdk = 33, onDeviceAvailable = true, installedLanguages = greek, supportedLanguages = greek, online = true),
        )

    /** Free and offline is the promise of spec §13: a connection must change nothing here. */
    @Test fun `and it does not care whether the phone is online`() =
        assertEquals(
            Engine.ON_DEVICE,
            OnDeviceSupport.decide(sdk = 36, onDeviceAvailable = true, installedLanguages = greek, supportedLanguages = none, online = false),
        )

    // Android 12 and below have none of the on-device APIs.

    @Test fun `below Android 13 there is no on-device path, whatever is installed`() {
        assertEquals(
            Engine.NETWORK,
            OnDeviceSupport.decide(sdk = 32, onDeviceAvailable = true, installedLanguages = greek, supportedLanguages = greek, online = true),
        )
        assertEquals(
            Engine.NONE,
            OnDeviceSupport.decide(sdk = 26, onDeviceAvailable = true, installedLanguages = greek, supportedLanguages = greek, online = false),
        )
    }

    @Test fun `Android 13 is where it starts`() {
        assertEquals(33, OnDeviceSupport.ON_DEVICE_SDK)
        assertEquals(
            Engine.ON_DEVICE,
            OnDeviceSupport.decide(sdk = OnDeviceSupport.ON_DEVICE_SDK, onDeviceAvailable = true, installedLanguages = greek, supportedLanguages = none, online = false),
        )
    }

    // An engine that does not know Greek at all. No tap of his will fix it, so it is said plainly.

    @Test fun `an engine with no Greek in it falls back to the cloud`() =
        assertEquals(
            Engine.NETWORK,
            OnDeviceSupport.decide(sdk = 35, onDeviceAvailable = true, installedLanguages = listOf("en-US"), supportedLanguages = listOf("en-US", "de-DE"), online = true),
        )

    @Test fun `and offline it can do nothing`() =
        assertEquals(
            Engine.NONE,
            OnDeviceSupport.decide(sdk = 35, onDeviceAvailable = true, installedLanguages = listOf("en-US"), supportedLanguages = listOf("en-US"), online = false),
        )

    /** The emulator this app is tested on: API 36, no recognition engine, a working connection. */
    @Test fun `the emulator falls back to the network path`() =
        assertEquals(
            Engine.NETWORK,
            OnDeviceSupport.decide(sdk = 36, onDeviceAvailable = false, installedLanguages = none, supportedLanguages = none, online = true),
        )

    // How the lists are matched. The platform is free to spell Greek several ways.

    @Test fun `every spelling of Greek the platform may answer with is Greek`() {
        for (tag in listOf("el", "el-GR", "el_GR", "EL-gr", "ell", "ell-GR", " el-GR ")) {
            assertTrue("«$tag» is the language he speaks", OnDeviceSupport.speaksGreek(listOf(tag)))
        }
    }

    @Test fun `and nothing else is`() {
        for (tag in listOf("en", "en-GB", "de-DE", "", "e", "fr-el")) {
            assertFalse("«$tag» is not Greek", OnDeviceSupport.speaksGreek(listOf(tag)))
        }
    }

    @Test fun `Greek anywhere in the list counts`() =
        assertTrue(OnDeviceSupport.speaksGreek(listOf("en-US", "de-DE", "el-GR")))

    @Test fun `an empty list is no Greek`() = assertFalse(OnDeviceSupport.speaksGreek(emptyList()))

    // Which engine folds the two buttons into one, and which can listen at all.

    @Test fun `only the on-device engine keeps his take`() {
        with(OnDeviceSupport) {
            assertTrue(Engine.ON_DEVICE.keepsTake)
            assertFalse(Engine.NEEDS_DOWNLOAD.keepsTake)
            assertFalse(Engine.NETWORK.keepsTake)
            assertFalse(Engine.NONE.keepsTake)
        }
    }

    @Test fun `everything but nothing can open a window`() {
        with(OnDeviceSupport) {
            assertTrue(Engine.ON_DEVICE.listens)
            assertTrue(Engine.NEEDS_DOWNLOAD.listens)
            assertTrue(Engine.NETWORK.listens)
            assertFalse(Engine.NONE.listens)
        }
    }

    // The four lines the caregiver's row can say, and which engine says which.

    @Test fun `the row says what the phone has`() {
        assertEquals(OnDeviceSupport.Greek.INSTALLED, OnDeviceSupport.greekFor(Engine.ON_DEVICE, downloading = false))
        assertEquals(OnDeviceSupport.Greek.MISSING, OnDeviceSupport.greekFor(Engine.NEEDS_DOWNLOAD, downloading = false))
        assertEquals(OnDeviceSupport.Greek.DOWNLOADING, OnDeviceSupport.greekFor(Engine.NEEDS_DOWNLOAD, downloading = true))
        assertEquals(OnDeviceSupport.Greek.UNSUPPORTED, OnDeviceSupport.greekFor(Engine.NETWORK, downloading = false))
        assertEquals(OnDeviceSupport.Greek.UNSUPPORTED, OnDeviceSupport.greekFor(Engine.NONE, downloading = false))
    }

    /** A download that has landed is news, and the row must not go on claiming to be fetching it. */
    @Test fun `a finished download reads as installed even before the poll stops`() =
        assertEquals(OnDeviceSupport.Greek.INSTALLED, OnDeviceSupport.greekFor(Engine.ON_DEVICE, downloading = true))

    @Test fun `the lines themselves, word for word`() {
        assertEquals("Ελληνικά χωρίς σύνδεση: εγκατεστημένα", OnDeviceSupport.lineFor(OnDeviceSupport.Greek.INSTALLED))
        assertEquals("Ελληνικά χωρίς σύνδεση: δεν υπάρχουν", OnDeviceSupport.lineFor(OnDeviceSupport.Greek.MISSING))
        assertEquals("Ελληνικά χωρίς σύνδεση: λήψη…", OnDeviceSupport.lineFor(OnDeviceSupport.Greek.DOWNLOADING))
        assertEquals("Ελληνικά χωρίς σύνδεση: δεν υποστηρίζεται", OnDeviceSupport.lineFor(OnDeviceSupport.Greek.UNSUPPORTED))
        assertEquals("Λήψη ελληνικών", OnDeviceSupport.DOWNLOAD)
    }
}
