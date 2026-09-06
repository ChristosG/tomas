package gr.dimitris.app.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.arcade.Adaptive
import gr.dimitris.app.modules.arcade.ArcadeGame
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

    /** Its own counter with its own ceiling: four sentence levels, not the numbers module's seven. */
    @Test fun `sentences level starts at 1 and is clamped to 1__4`() = runBlocking {
        val s = newSettings()
        assertEquals(1, s.sentencesLevel.first())
        s.setSentencesLevel(9); assertEquals(4, s.sentencesLevel.first())
        s.setSentencesLevel(0); assertEquals(1, s.sentencesLevel.first())
        s.setSentencesLevel(3); assertEquals(3, s.sentencesLevel.first())
        assertEquals(1, s.numbersLevel.first())
    }

    /** Five writing levels of its own: capitals, small letters, his name, words, words from memory. */
    @Test fun `trace level starts at 1 and is clamped to 1__5`() = runBlocking {
        val s = newSettings()
        assertEquals(1, s.traceLevel.first())
        s.setTraceLevel(9); assertEquals(5, s.traceLevel.first())
        s.setTraceLevel(0); assertEquals(1, s.traceLevel.first())
        s.setTraceLevel(4); assertEquals(4, s.traceLevel.first())
        assertEquals(1, s.sentencesLevel.first())
    }

    /**
     * The arcade's difficulty, and the only thing it remembers between sittings. A stored size out
     * of range — an old backup, a version that moved the range — is pulled back to something he can
     * still touch rather than handed to the games as it is.
     */
    @Test fun `the arcade target starts at 96 dp and is clamped to 40__130`() = runBlocking {
        val s = newSettings()
        val tap = ArcadeGame.TAP
        assertEquals(Adaptive.START, s.arcadeTargetDp(tap).first())
        s.setArcadeTargetDp(tap, 9f); assertEquals(Adaptive.MIN, s.arcadeTargetDp(tap).first())
        s.setArcadeTargetDp(tap, 900f); assertEquals(Adaptive.MAX, s.arcadeTargetDp(tap).first())
        s.setArcadeTargetDp(tap, 72f); assertEquals(72f, s.arcadeTargetDp(tap).first())
        // Its own key: the writing levels are not the arcade's difficulty.
        assertEquals(1, s.traceLevel.first())
    }

    /**
     * Four games, four difficulties. Pressing a circle is the movement he keeps longest and pinching
     * is the one he loses first, so a good round of tapping must not drag the photo down to the
     * floor with it.
     */
    @Test fun `each arcade game keeps its own target size`() = runBlocking {
        val s = newSettings()
        s.setArcadeTargetDp(ArcadeGame.TAP, 44f)
        s.setArcadeTargetDp(ArcadeGame.PINCH, 128f)
        assertEquals(44f, s.arcadeTargetDp(ArcadeGame.TAP).first())
        assertEquals(128f, s.arcadeTargetDp(ArcadeGame.PINCH).first())
        // The two nobody has played are still at the start.
        assertEquals(Adaptive.START, s.arcadeTargetDp(ArcadeGame.TRACE).first())
        assertEquals(Adaptive.START, s.arcadeTargetDp(ArcadeGame.DRAG).first())
    }

    /**
     * A device that played the arcade before the games had their own keys: whatever he had worked
     * down to is where each of them starts, and the first game he plays afterwards moves only its
     * own.
     */
    @Test fun `the one size the arcade used to keep is handed to every game`() = runBlocking {
        val dir = createTempDirectory("settings").toFile()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { File(dir, "settings.preferences_pb") },
        )
        store.edit { it[floatPreferencesKey("arcade_target_dp")] = 58f }
        val s = Settings(store)
        for (game in ArcadeGame.entries) assertEquals(58f, s.arcadeTargetDp(game).first())

        s.setArcadeTargetDp(ArcadeGame.DRAG, 70f)
        assertEquals(70f, s.arcadeTargetDp(ArcadeGame.DRAG).first())
        assertEquals(58f, s.arcadeTargetDp(ArcadeGame.TAP).first())
    }

    /**
     * The left hand by default: his right is the side the stroke took. Nothing but the two hands can
     * ever come out, so a store written by a future version cannot leave the screen with no hint.
     */
    @Test fun `writing hand is the left one until someone says otherwise`() = runBlocking {
        val s = newSettings()
        assertEquals(Settings.HAND_LEFT, s.traceHand.first())
        s.setTraceHand(Settings.HAND_RIGHT)
        assertEquals(Settings.HAND_RIGHT, s.traceHand.first())
        s.setTraceHand("BOTH")
        assertEquals(Settings.HAND_LEFT, s.traceHand.first())
    }
}
