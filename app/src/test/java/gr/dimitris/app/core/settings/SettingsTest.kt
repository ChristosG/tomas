package gr.dimitris.app.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.arcade.Adaptive
import gr.dimitris.app.modules.arcade.ArcadeGame
import gr.dimitris.app.modules.singsay.Key
import gr.dimitris.app.modules.singsay.Tempo
import gr.dimitris.app.modules.trace.TraceStrictness
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

    /**
     * How hard the writing is marked. Κανονικό until a caregiver says otherwise, and a name that is
     * no longer one of the three — an old backup — reads as Κανονικό rather than throwing him out of
     * the module.
     */
    @Test fun `writing is marked at Κανονικό until a caregiver says otherwise`() = runBlocking {
        val s = newSettings()
        assertEquals(TraceStrictness.NORMAL, s.traceStrictness.first())
        s.setTraceStrictness(TraceStrictness.STRICT)
        assertEquals(TraceStrictness.STRICT, s.traceStrictness.first())
        s.setTraceStrictness(TraceStrictness.LOOSE)
        assertEquals(TraceStrictness.LOOSE, s.traceStrictness.first())
        // Its own key: how hard he is marked is not which level he is on.
        assertEquals(1, s.traceLevel.first())
    }

    /** A value from a version that named them differently is not a crash. */
    @Test fun `a strictness nobody recognises reads as Κανονικό`() = runBlocking {
        val dir = createTempDirectory("settings").toFile()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { File(dir, "settings.preferences_pb") },
        )
        store.edit { it[stringPreferencesKey("trace_strictness")] = "VERY_STRICT" }
        assertEquals(TraceStrictness.NORMAL, Settings(store).traceStrictness.first())
    }

    /**
     * How fast the sing-then-say melody moves. Κανονικός until a caregiver slows it down, and a
     * name that is no longer one of the two — an old backup — reads as Κανονικός rather than
     * crashing him out of the module.
     */
    @Test fun `the melody moves at Κανονικός tempo until a caregiver slows it down`() = runBlocking {
        val s = newSettings()
        assertEquals(Tempo.NORMAL, s.melodyTempo.first())
        s.setMelodyTempo(Tempo.SLOW)
        assertEquals(Tempo.SLOW, s.melodyTempo.first())
        s.setMelodyTempo(Tempo.NORMAL)
        assertEquals(Tempo.NORMAL, s.melodyTempo.first())

        val dir = createTempDirectory("settings").toFile()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { File(dir, "settings.preferences_pb") },
        )
        store.edit { it[stringPreferencesKey("melody_tempo")] = "PRESTO" }
        assertEquals(Tempo.NORMAL, Settings(store).melodyTempo.first())
    }

    /**
     * Which key the sing-then-say melody sings in. Κανονικός until a caregiver drops it, and an
     * unrecognised name reads as Κανονικός the same way.
     */
    @Test fun `the melody sings in Κανονικός key until a caregiver lowers it`() = runBlocking {
        val s = newSettings()
        assertEquals(Key.NORMAL, s.melodyKey.first())
        s.setMelodyKey(Key.LOW)
        assertEquals(Key.LOW, s.melodyKey.first())
        s.setMelodyKey(Key.NORMAL)
        assertEquals(Key.NORMAL, s.melodyKey.first())

        val dir = createTempDirectory("settings").toFile()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { File(dir, "settings.preferences_pb") },
        )
        store.edit { it[stringPreferencesKey("melody_key")] = "MEZZO" }
        assertEquals(Key.NORMAL, Settings(store).melodyKey.first())
    }

    /** Empty means the app never touches the network by itself; a trailing slash is not a difference. */
    @Test fun `the sync url is empty until someone types one`() = runBlocking {
        val s = newSettings()
        assertEquals("", s.syncUrl.first())
        s.setSyncUrl("  https://sync.example.com/  ")
        assertEquals("https://sync.example.com", s.syncUrl.first())
        s.setSyncUrl("   ")
        assertEquals("", s.syncUrl.first())
    }

    /** Nobody has been asked yet, and until they are the phone behaves as his. */
    @Test fun `the device role is unanswered first and Dimitris in the meantime`() = runBlocking {
        val s = newSettings()
        assertEquals(false, s.rolePick.first().chosen)
        assertEquals(DeviceRole.DIMITRIS, s.deviceRole.first())

        s.setDeviceRole(DeviceRole.CAREGIVER)
        assertEquals(true, s.rolePick.first().chosen)
        assertEquals(DeviceRole.CAREGIVER, s.deviceRole.first())
        assertEquals(DeviceRole.CAREGIVER, s.rolePick.first().role)
    }

    /**
     * Spec §13: per-turn judging is opt-in. Off is the default and the default is the consent, so a
     * fresh install judges every word on the phone and touches nothing outside it.
     */
    @Test fun `judging with Claude is off until a caregiver turns it on`() = runBlocking {
        val s = newSettings()
        assertEquals(false, s.claudeJudging.first())
        s.setClaudeJudging(true)
        assertEquals(true, s.claudeJudging.first())
        s.setClaudeJudging(false)
        assertEquals(false, s.claudeJudging.first())
        // Its own key: the advisor's model is untouched by it either way.
        assertEquals(Settings.DEFAULT_CLAUDE_MODEL, s.claudeModel.first())
    }

    @Test fun `both sync cursors start at zero and persist`() = runBlocking {
        val s = newSettings()
        assertEquals(0L, s.syncCursor.first())
        assertEquals(0L, s.syncPushedUpTo.first())
        assertEquals(0L, s.lastSyncAt.first())

        s.setSyncCursor(42)
        s.setSyncPushedUpTo(1_757_000_000_000)
        s.setLastSyncAt(1_757_000_000_500)
        assertEquals(42L, s.syncCursor.first())
        assertEquals(1_757_000_000_000L, s.syncPushedUpTo.first())
        assertEquals(1_757_000_000_500L, s.lastSyncAt.first())
    }

    /**
     * After a backup is restored the database is not the one the cursors were counted against, so
     * the only honest position is the start. Everything else the caregiver set stays where it was.
     */
    @Test fun `restoring a backup resets both cursors and nothing else`() = runBlocking {
        val s = newSettings()
        s.setSyncCursor(42)
        s.setSyncPushedUpTo(1_757_000_000_000)
        s.setSyncUrl("https://sync.example.com")
        s.setDeviceRole(DeviceRole.CAREGIVER)

        s.resetSyncCursors()

        assertEquals(0L, s.syncCursor.first())
        assertEquals(0L, s.syncPushedUpTo.first())
        assertEquals("https://sync.example.com", s.syncUrl.first())
        assertEquals(DeviceRole.CAREGIVER, s.deviceRole.first())
    }
}
