package gr.dimitris.app.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import gr.dimitris.app.core.data.ModuleId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempDirectory

class SettingsTest {
    private fun newSettings(): Settings {
        val dir = createTempDirectory("settings").toFile()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { File(dir, "settings.preferences_pb") },
        )
        return Settings(store)
    }

    @Test fun `speech rate defaults to 0_8 and persists`() = runBlocking {
        val s = newSettings()
        assertEquals(0.8f, s.speechRate.first())
        s.setSpeechRate(1.0f)
        assertEquals(1.0f, s.speechRate.first())
    }

    @Test fun `speech rate is clamped`() = runBlocking {
        val s = newSettings()
        s.setSpeechRate(9f)
        assertEquals(1.3f, s.speechRate.first())
    }

    @Test fun `caregiver lock and seed version default off and zero`() = runBlocking {
        val s = newSettings()
        assertEquals(false, s.caregiverLock.first())
        assertEquals(0, s.seedVersion.first())
        s.setCaregiverLock(true); s.setSeedVersion(3)
        assertEquals(true, s.caregiverLock.first())
        assertEquals(3, s.seedVersion.first())
    }

    /** Its own counter, so bumping the dialogues never re-imports the vocabulary or the other way round. */
    @Test fun `scripts seed version defaults to zero and persists`() = runBlocking {
        val s = newSettings()
        assertEquals(0, s.scriptsSeedVersion.first())
        s.setScriptsSeedVersion(2)
        assertEquals(2, s.scriptsSeedVersion.first())
        assertEquals(0, s.seedVersion.first())
    }

    @Test fun `speech recognition is off by default`() = runBlocking {
        val s = newSettings()
        assertEquals(false, s.sttEnabled.first())
        s.setSttEnabled(true)
        assertEquals(true, s.sttEnabled.first())
    }

    @Test fun `every module but the default-off ones is enabled to begin with`() = runBlocking {
        val s = newSettings()
        assertEquals(ModuleId.entries.toSet() - Settings.DEFAULT_OFF, s.enabledModules.first())
        assertEquals(setOf(ModuleId.ARCADE), Settings.DEFAULT_OFF)
    }

    @Test fun `a default-off module stays on once someone asks for it`() = runBlocking {
        val s = newSettings()
        s.setModuleEnabled(ModuleId.ARCADE, true)
        assertEquals(ModuleId.entries.toSet(), s.enabledModules.first())
        s.setModuleEnabled(ModuleId.ARCADE, false)
        assertEquals(ModuleId.entries.toSet() - ModuleId.ARCADE, s.enabledModules.first())
    }

    @Test fun `an ordinary module can be switched off and back on`() = runBlocking {
        val s = newSettings()
        val defaults = ModuleId.entries.toSet() - Settings.DEFAULT_OFF
        s.setModuleEnabled(ModuleId.WORDCOACH, false)
        assertEquals(defaults - ModuleId.WORDCOACH, s.enabledModules.first())
        s.setModuleEnabled(ModuleId.WORDCOACH, true)
        assertEquals(defaults, s.enabledModules.first())
    }

    @Test fun `numbers level starts at 1 and is clamped to 1__7`() = runBlocking {
        val s = newSettings()
        assertEquals(1, s.numbersLevel.first())
        s.setNumbersLevel(9); assertEquals(7, s.numbersLevel.first())
        s.setNumbersLevel(0); assertEquals(1, s.numbersLevel.first())
    }
}
