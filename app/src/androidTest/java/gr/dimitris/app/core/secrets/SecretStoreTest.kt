package gr.dimitris.app.core.secrets

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * The key store on a real device, because the keystore it leans on only exists on one. Three
 * things matter and all three are here: a key survives being written and read back, deleting it
 * really deletes it, and the file it lands in is not one of the files the backup packs.
 */
class SecretStoreTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val store get() = SecretStore(context)
    private var before: String? = null

    @Before fun rememberWhatWasThere() {
        before = store.getClaudeKey()
    }

    /** Whatever the caregiver had on this device is what they have after the test. */
    @After fun putItBack() {
        store.setClaudeKey(before)
    }

    @Test fun theKeyComesBackTheWayItWentIn() {
        store.setClaudeKey("sk-ant-test-0123456789")

        assertEquals("sk-ant-test-0123456789", store.getClaudeKey())
        // A second instance, because the caregiver saves it on one screen and asks with it on another.
        assertEquals("sk-ant-test-0123456789", SecretStore(context).getClaudeKey())
    }

    @Test fun aBlankKeyIsNoKeyAndSpacesAreTrimmed() {
        store.setClaudeKey("  sk-ant-test-padded  ")
        assertEquals("sk-ant-test-padded", store.getClaudeKey())

        store.setClaudeKey("   ")
        assertNull(store.getClaudeKey())
    }

    @Test fun deletingTheKeyReallyDeletesIt() {
        store.setClaudeKey("sk-ant-test-0123456789")
        store.setClaudeKey(null)

        assertNull(store.getClaudeKey())
        assertNull(SecretStore(context).getClaudeKey())
    }

    /** What is on disk is ciphertext: the key must not be greppable out of the file. */
    @Test fun theKeyIsNotOnDiskInPlainText() {
        store.setClaudeKey("sk-ant-test-needle-0123456789")

        val file = File(context.filesDir.parentFile, "shared_prefs/${SecretStore.FILE}.xml")
        assertTrue("the encrypted file was never written", file.isFile)
        assertFalse(file.readText().contains("sk-ant-test-needle"))
    }

    /** The mask is what the settings screen shows: recognisable, useless. */
    @Test fun theMaskShowsTheTailAndNothingElse() {
        assertEquals("", SecretStore.mask(null))
        assertEquals("", SecretStore.mask("  "))
        assertEquals("••••", SecretStore.mask("abcd"))
        assertEquals("••••••••6789", SecretStore.mask("sk-ant-test-0123456789"))
    }
}
