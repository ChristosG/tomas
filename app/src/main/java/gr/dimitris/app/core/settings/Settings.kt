package gr.dimitris.app.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import gr.dimitris.app.core.data.ModuleId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

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
        private val DISABLED_MODULES = stringSetPreferencesKey("disabled_modules")

        /** The [DEFAULT_OFF] ones someone has switched on. Meaningless for every other module. */
        private val ENABLED_EXTRAS = stringSetPreferencesKey("enabled_extras")
    }
}
