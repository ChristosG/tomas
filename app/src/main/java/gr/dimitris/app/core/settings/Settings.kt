package gr.dimitris.app.core.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
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

    companion object {
        const val DEFAULT_RATE = 0.8f
        const val MIN_RATE = 0.5f
        const val MAX_RATE = 1.3f
        private val SPEECH_RATE = floatPreferencesKey("speech_rate")
        private val CAREGIVER_LOCK = booleanPreferencesKey("caregiver_lock")
        private val SEED_VERSION = intPreferencesKey("seed_version")
    }
}
