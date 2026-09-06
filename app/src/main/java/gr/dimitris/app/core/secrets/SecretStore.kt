package gr.dimitris.app.core.secrets

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File

/**
 * All [gr.dimitris.app.caregiver.insights.ClaudeAdvisor] is allowed to know about the store: one
 * key, or none. The advisor holds this and not the class, so a JVM test can hand it a key from the
 * environment without an Android context — and so nothing but the store itself can ever *write* one.
 */
fun interface Secrets {
    fun claudeKey(): String?
}

/**
 * The one place a secret is kept: an [EncryptedSharedPreferences] file whose master key lives in the
 * Android keystore, so the bytes on disk are useless to anything that is not this app on this phone.
 *
 * Only one secret so far — the caregiver's own Anthropic key, which is the only thing in the whole
 * app that could cost somebody money if it leaked.
 *
 * Three deliberate absences:
 *
 * * it is **not** in the backup zip — [gr.dimitris.app.core.backup.Backup] packs the database and
 *   the two media folders and nothing else, so a zip handed to the father's server carries Dimitris'
 *   words and never the key;
 * * it is **not** in `android:allowBackup`, which the manifest turns off for the whole app;
 * * it is never logged, never put in `error_logs`, never in the summary sent to Claude and never
 *   shown whole on a screen. [gr.dimitris.app.caregiver.insights.ClaudeAdvisor] takes care that no
 *   exception carrying a request ever reaches the error log.
 *
 * Opening the file touches the keystore and the disk, so every call belongs off the main thread.
 */
class SecretStore(context: Context) : Secrets {
    private val app: Context = context.applicationContext

    @Volatile private var cached: SharedPreferences? = null

    /**
     * The key the caregiver saved, or null when there is none. A store that cannot be opened at all
     * reads as "no key" rather than as a crash: without a key the advisor is simply switched off,
     * which is exactly the state the caregiver should then see.
     */
    fun getClaudeKey(): String? = runCatching { prefs().getString(CLAUDE_KEY, null) }
        .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    override fun claudeKey(): String? = getClaudeKey()

    /** Saves the key, or removes it when [key] is null or blank. Throws if the store cannot be written. */
    fun setClaudeKey(key: String?) = put(CLAUDE_KEY, key)

    /**
     * The shared secret both caregiver phones send to the sync server, or null when sync has not
     * been set up. It lives here rather than in the settings for the same reason the Claude key
     * does: whoever holds it can read and write everything on the server, and the backup zip is
     * handed to other machines. It is never logged and never put in `error_logs`.
     */
    fun getSyncToken(): String? = runCatching { prefs().getString(SYNC_TOKEN, null) }
        .getOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    /** Saves the token, or removes it when [token] is null or blank. Throws if the store cannot be written. */
    fun setSyncToken(token: String?) = put(SYNC_TOKEN, token)

    private fun put(name: String, value: String?) {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() }
        val editor = prefs().edit()
        if (trimmed == null) editor.remove(name) else editor.putString(name, trimmed)
        // commit, not apply: the caregiver taps «Αποθήκευση κλειδιού» and then asks Claude, and a
        // write still in flight would look like a key that did not save.
        editor.commit()
    }

    /**
     * The encrypted file, opened once. A file the keystore can no longer read — a restored phone, a
     * cleared lock screen — is emptied and made again rather than left as a permanent failure: the
     * caregiver retypes the key, which is a minute, instead of reinstalling the app.
     */
    private fun prefs(): SharedPreferences = cached ?: synchronized(this) {
        cached ?: open().also { cached = it }
    }

    /**
     * The self-heal has to go through the framework, not the filesystem.
     * `EncryptedSharedPreferences` keeps its Tink keyset *inside this same preferences file* and
     * reads it with `Context.getSharedPreferences`, so by the time `create()` throws, this process
     * already holds a `SharedPreferencesImpl` for the file with the undecryptable keyset in memory.
     * Deleting the XML would leave that instance untouched and the retry would fail exactly as the
     * first attempt did — the caregiver would get «Δεν μπόρεσα να αποθηκεύσω το κλειδί.» until the
     * app was force-stopped. Clearing through `getSharedPreferences(...).edit().clear().commit()`
     * empties the cached instance *and* the file, so the retry generates a fresh keyset.
     *
     * The file is deleted afterwards as well, to leave nothing behind on disk.
     */
    private fun open(): SharedPreferences = try {
        create()
    } catch (_: Throwable) {
        runCatching { app.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit() }
        runCatching { File(app.filesDir.parentFile, "shared_prefs/$FILE.xml").delete() }
        create()
    }

    private fun create(): SharedPreferences {
        val master = MasterKey.Builder(app, MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            app,
            FILE,
            master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    companion object {
        /** The name of the encrypted file. Kept out of the backup on purpose — see the class KDoc. */
        const val FILE = "secrets"
        private const val CLAUDE_KEY = "claude_api_key"
        private const val SYNC_TOKEN = "sync_token"

        /** What a key looks like on a screen: enough to recognise it, not enough to use it. */
        fun mask(key: String?): String {
            val k = key?.trim().orEmpty()
            if (k.isEmpty()) return ""
            return if (k.length <= TAIL) "•".repeat(k.length) else "•".repeat(MASK) + k.takeLast(TAIL)
        }

        private const val TAIL = 4
        private const val MASK = 8
    }
}
