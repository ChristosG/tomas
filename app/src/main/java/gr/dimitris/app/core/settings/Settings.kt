package gr.dimitris.app.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.arcade.Adaptive
import gr.dimitris.app.modules.arcade.ArcadeGame
import gr.dimitris.app.modules.trace.TraceStrictness
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * Whose phone this is. Asked once, on the very first launch, and changeable afterwards in the
 * caregiver settings.
 *
 * It decides where the app opens — Dimitris lands on «Δημήτρης» and a caregiver on «Φροντιστής» —
 * and nothing else. Sync itself does not care: his phone syncs too, or his practice would never
 * reach the people looking after him.
 */
enum class DeviceRole { DIMITRIS, CAREGIVER }

/** The answer to "whose phone is this?", including "nobody has been asked yet". */
@JvmInline
value class RolePick(val role: DeviceRole?) {
    val chosen: Boolean get() = role != null
    val effective: DeviceRole get() = role ?: DeviceRole.DIMITRIS
}

class Settings(private val store: DataStore<Preferences>) {
    constructor(context: Context) : this(context.applicationContext.settingsStore)

    val speechRate: Flow<Float> = store.data.map { it[SPEECH_RATE] ?: DEFAULT_RATE }
    suspend fun setSpeechRate(rate: Float) { store.edit { it[SPEECH_RATE] = rate.coerceIn(MIN_RATE, MAX_RATE) } }

    val caregiverLock: Flow<Boolean> = store.data.map { it[CAREGIVER_LOCK] ?: false }
    suspend fun setCaregiverLock(on: Boolean) { store.edit { it[CAREGIVER_LOCK] = on } }

    val seedVersion: Flow<Int> = store.data.map { it[SEED_VERSION] ?: 0 }
    suspend fun setSeedVersion(version: Int) { store.edit { it[SEED_VERSION] = version } }

    /** Its own counter: the dialogues and the vocabulary are bumped on different days. */
    val scriptsSeedVersion: Flow<Int> = store.data.map { it[SCRIPTS_SEED_VERSION] ?: 0 }
    suspend fun setScriptsSeedVersion(version: Int) { store.edit { it[SCRIPTS_SEED_VERSION] = version } }

    val sttEnabled: Flow<Boolean> = store.data.map { it[STT_ENABLED] ?: false }
    suspend fun setSttEnabled(on: Boolean) { store.edit { it[STT_ENABLED] = on } }

    /** Where he is in the seven number-sense levels. The numbers module moves it; nothing else does. */
    val numbersLevel: Flow<Int> = store.data.map { it[NUMBERS_LEVEL] ?: 1 }
    suspend fun setNumbersLevel(level: Int) { store.edit { it[NUMBERS_LEVEL] = level.coerceIn(1, 7) } }

    /** How long a sentence he is building, 1..4. The sentence builder moves it; nothing else does. */
    val sentencesLevel: Flow<Int> = store.data.map { it[SENTENCES_LEVEL] ?: 1 }
    suspend fun setSentencesLevel(level: Int) { store.edit { it[SENTENCES_LEVEL] = level.coerceIn(1, 4) } }

    /** What he is writing, 1..5: capitals, small letters, his name, words, words from memory. */
    val traceLevel: Flow<Int> = store.data.map { it[TRACE_LEVEL] ?: 1 }
    suspend fun setTraceLevel(level: Int) { store.edit { it[TRACE_LEVEL] = level.coerceIn(1, 5) } }

    /**
     * Which hand he writes with, [HAND_LEFT] or [HAND_RIGHT]. It is the left one by default because
     * his right is the side the stroke took, and the writing screen says so out loud on every letter
     * — one line of certainty for a man who cannot ask which hand he is supposed to use.
     *
     * A caregiver sets it, and anything else that ever lands in the store reads as left rather than
     * as an empty hint.
     */
    val traceHand: Flow<String> = store.data.map { p -> p[TRACE_HAND]?.takeIf { it in HANDS } ?: HAND_LEFT }
    suspend fun setTraceHand(hand: String) { store.edit { it[TRACE_HAND] = if (hand in HANDS) hand else HAND_LEFT } }

    /**
     * How hard «Γράψε» marks him: «Χαλαρό», «Κανονικό» or «Αυστηρό». Κανονικό until a caregiver says
     * otherwise, and Κανονικό is the line the app is designed around — a hand-like trace of the right
     * letter passes it, and a wrong letter form does not, because a wrong movement learned is worse
     * than an exercise repeated.
     *
     * A name that is no longer one of the three — an old backup, a version that renamed them — reads
     * as [TraceStrictness.DEFAULT] rather than crashing a man out of the one module he can do alone.
     */
    val traceStrictness: Flow<TraceStrictness> = store.data.map { TraceStrictness.named(it[TRACE_STRICTNESS]) }
    suspend fun setTraceStrictness(level: TraceStrictness) { store.edit { it[TRACE_STRICTNESS] = level.name } }

    /**
     * How big one arcade game's targets are, in dp. It is his difficulty and the arcade's whole
     * memory: every hit takes a little off it and every miss gives more back, and it is kept between
     * sittings so the size he worked down to is where he starts tomorrow.
     *
     * One key per game, because the four ask his hand for four different things. Pressing a circle
     * is the movement he keeps longest and pinching is the one he loses first, so a good round of
     * tapping used to drag the pinch game down to the floor with it — and then the photo he could
     * not open was the app's idea, not his hand's.
     *
     * Clamped on the way out as well as in, because a value from a restored backup — or from a
     * version that moved the range — must never leave him with a target too small to touch. A device
     * that stored the single pre-per-game size hands it to every game that has not been played since
     * ([LEGACY_ARCADE_TARGET_DP]): whatever he had worked down to is where each of them starts.
     */
    fun arcadeTargetDp(game: ArcadeGame): Flow<Float> = store.data.map { p ->
        Adaptive.clamp(p[arcadeKey(game)] ?: p[LEGACY_ARCADE_TARGET_DP] ?: Adaptive.START)
    }

    suspend fun setArcadeTargetDp(game: ArcadeGame, dp: Float) {
        store.edit { it[arcadeKey(game)] = Adaptive.clamp(dp) }
    }

    /**
     * Which Claude model the optional advisor asks. A caregiver never has to touch it; it is here
     * so a newer model can be tried on the phone without a new build, and so a model that stops
     * being served is one text field away from being fixed.
     *
     * Blank — or a value from a restored backup that is no longer a model id — reads as
     * [DEFAULT_CLAUDE_MODEL] rather than as an empty request the API would refuse.
     */
    val claudeModel: Flow<String> = store.data.map { p -> p[CLAUDE_MODEL]?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_CLAUDE_MODEL }
    suspend fun setClaudeModel(model: String) {
        store.edit { p ->
            val trimmed = model.trim()
            if (trimmed.isEmpty()) p.remove(CLAUDE_MODEL) else p[CLAUDE_MODEL] = trimmed
        }
    }

    /**
     * Where the sync server lives, e.g. `https://sync.example.com`. Empty until a caregiver types
     * it in, and empty means the app never touches the network by itself.
     *
     * The token that goes with it is **not** here: it lives in
     * [gr.dimitris.app.core.secrets.SecretStore], encrypted, and never travels in a backup.
     */
    val syncUrl: Flow<String> = store.data.map { it[SYNC_URL]?.trim().orEmpty() }
    suspend fun setSyncUrl(url: String) {
        store.edit { p ->
            val trimmed = url.trim().trimEnd('/')
            if (trimmed.isEmpty()) p.remove(SYNC_URL) else p[SYNC_URL] = trimmed
        }
    }

    /** Null until the first-run question is answered; see [RolePick]. */
    val rolePick: Flow<RolePick> = store.data.map { p ->
        RolePick(p[DEVICE_ROLE]?.let { name -> runCatching { DeviceRole.valueOf(name) }.getOrNull() })
    }

    /** What the app should behave as. An unanswered question reads as Dimitris' phone. */
    val deviceRole: Flow<DeviceRole> = rolePick.map { it.effective }
    suspend fun setDeviceRole(role: DeviceRole) { store.edit { it[DEVICE_ROLE] = role.name } }

    /**
     * How far this phone has read the server's log: the highest sequence number it has taken in.
     * Advanced to the highest `seq` **received**, never to the number the server reports as its
     * own top — a capped page would otherwise leave rows behind that nothing would ever ask for
     * again.
     */
    val syncCursor: Flow<Long> = store.data.map { it[SYNC_CURSOR] ?: 0L }
    suspend fun setSyncCursor(seq: Long) { store.edit { it[SYNC_CURSOR] = seq.coerceAtLeast(0L) } }

    /** The `updatedAt` of the newest row this phone has pushed. Everything above it goes next time. */
    val syncPushedUpTo: Flow<Long> = store.data.map { it[SYNC_PUSHED_UP_TO] ?: 0L }
    suspend fun setSyncPushedUpTo(at: Long) { store.edit { it[SYNC_PUSHED_UP_TO] = at.coerceAtLeast(0L) } }

    /** When the last sync finished, for the one line the caregiver reads. 0 = never. */
    val lastSyncAt: Flow<Long> = store.data.map { it[LAST_SYNC_AT] ?: 0L }
    suspend fun setLastSyncAt(at: Long) { store.edit { it[LAST_SYNC_AT] = at } }

    /**
     * Both cursors back to the beginning. Called after a backup is restored: the database that
     * arrived is not the one the cursors were counted against, so the only honest position is the
     * start. It costs one long pull, and last-write-wins sorts out what comes back.
     */
    suspend fun resetSyncCursors() {
        store.edit { p ->
            p[SYNC_CURSOR] = 0L
            p[SYNC_PUSHED_UP_TO] = 0L
        }
    }

    /**
     * Which modules Dimitris gets. Everything is on unless a caregiver switched it off, except the
     * few in [DEFAULT_OFF], which are on only once someone deliberately asks for them.
     *
     * Two keys, because "never switched on" and "switched off" are different states: absence cannot
     * mean on for most modules and off for the extras at the same time.
     */
    val enabledModules: Flow<Set<ModuleId>> = store.data.map { p ->
        val off = p[DISABLED_MODULES].orEmpty()
        val extras = p[ENABLED_EXTRAS].orEmpty()
        ModuleId.entries.filter { it.name !in off && (it !in DEFAULT_OFF || it.name in extras) }.toSet()
    }
    suspend fun setModuleEnabled(id: ModuleId, on: Boolean) {
        store.edit { p ->
            if (id in DEFAULT_OFF) {
                val extras = p[ENABLED_EXTRAS].orEmpty().toMutableSet()
                if (on) extras.add(id.name) else extras.remove(id.name)
                p[ENABLED_EXTRAS] = extras
            } else {
                val off = p[DISABLED_MODULES].orEmpty().toMutableSet()
                if (on) off.remove(id.name) else off.add(id.name)
                p[DISABLED_MODULES] = off
            }
        }
    }

    companion object {
        /** Off until asked for: the arcade is a reward, not part of the daily work. */
        val DEFAULT_OFF = setOf(ModuleId.ARCADE)

        const val HAND_LEFT = "LEFT"
        const val HAND_RIGHT = "RIGHT"
        val HANDS = setOf(HAND_LEFT, HAND_RIGHT)

        /** The advisor's model unless a caregiver types another one. */
        const val DEFAULT_CLAUDE_MODEL = "claude-opus-5"

        const val DEFAULT_RATE = 0.8f
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 1.3f
        private val SPEECH_RATE = floatPreferencesKey("speech_rate")
        private val CAREGIVER_LOCK = booleanPreferencesKey("caregiver_lock")
        private val SEED_VERSION = intPreferencesKey("seed_version")
        private val SCRIPTS_SEED_VERSION = intPreferencesKey("scripts_seed_version")
        private val STT_ENABLED = booleanPreferencesKey("stt_enabled")
        private val NUMBERS_LEVEL = intPreferencesKey("numbers_level")
        private val SENTENCES_LEVEL = intPreferencesKey("sentences_level")
        private val TRACE_LEVEL = intPreferencesKey("trace_level")
        private val TRACE_HAND = stringPreferencesKey("trace_hand")
        private val TRACE_STRICTNESS = stringPreferencesKey("trace_strictness")
        private val CLAUDE_MODEL = stringPreferencesKey("claude_model")
        private val SYNC_URL = stringPreferencesKey("sync_url")
        private val DEVICE_ROLE = stringPreferencesKey("device_role")
        private val SYNC_CURSOR = longPreferencesKey("sync_cursor")
        private val SYNC_PUSHED_UP_TO = longPreferencesKey("sync_pushed_up_to")
        private val LAST_SYNC_AT = longPreferencesKey("last_sync_at")

        /** One target size per game: `arcade_target_dp_tap` and its three siblings. */
        private fun arcadeKey(game: ArcadeGame) = floatPreferencesKey("arcade_target_dp_${game.id}")

        /**
         * The one size the arcade had before each game kept its own. Read as the starting point for
         * a game that has not been played since, never written again.
         */
        private val LEGACY_ARCADE_TARGET_DP = floatPreferencesKey("arcade_target_dp")
        private val DISABLED_MODULES = stringSetPreferencesKey("disabled_modules")

        /** The [DEFAULT_OFF] ones someone has switched on. Meaningless for every other module. */
        private val ENABLED_EXTRAS = stringSetPreferencesKey("enabled_extras")
    }
}
