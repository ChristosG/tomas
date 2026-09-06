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

/** Where `EncryptedSharedPreferences` keeps the keyset it cannot start without. */
private const val KEYSET = "__androidx_security_crypto_encrypted_prefs_key_keyset__"

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

    /**
     * A phone that was restored, or whose screen lock was cleared, can end up with a keyset the
     * master key no longer decrypts. Deleting the XML is not enough to recover inside a running
     * process: `EncryptedSharedPreferences` keeps its keyset *in this same preferences file* and
     * reads it through `Context.getSharedPreferences`, so the framework is already holding an
     * instance with the unreadable keyset in memory and the retry would fail exactly as the first
     * attempt did — leaving the caregiver with «Δεν μπόρεσα να αποθηκεύσω το κλειδί.» until someone
     * force-stopped the app. Clearing through the framework empties the cached instance too.
     *
     * The corruption here is written the same way the real thing arrives: through
     * `getSharedPreferences`, so the process cache holds it.
     */
    @Test fun aKeysetItCannotReadIsRebuiltInsteadOfBreakingForever() {
        SecretStore(context).setClaudeKey("sk-ant-test-before-the-crash")

        context.getSharedPreferences(SecretStore.FILE, Context.MODE_PRIVATE).edit()
            .putString(KEYSET, "αυτό δεν είναι keyset")
            .commit()

        val store = SecretStore(context)
        // Reads as "no key" rather than throwing: without a key the advisor is simply switched off.
        assertNull(store.getClaudeKey())
        // And the caregiver can put a new one in, in the same process, without reinstalling.
        store.setClaudeKey("sk-ant-test-after-the-crash")
        assertEquals("sk-ant-test-after-the-crash", store.getClaudeKey())
        assertEquals("sk-ant-test-after-the-crash", SecretStore(context).getClaudeKey())
    }

    /** The mask is what the settings screen shows: recognisable, useless. */
    @Test fun theMaskShowsTheTailAndNothingElse() {
        assertEquals("", SecretStore.mask(null))
        assertEquals("", SecretStore.mask("  "))
        assertEquals("••••", SecretStore.mask("abcd"))
        assertEquals("••••••••6789", SecretStore.mask("sk-ant-test-0123456789"))
    }
}
