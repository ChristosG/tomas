package gr.dimitris.app.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
}
