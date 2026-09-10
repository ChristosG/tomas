package gr.dimitris.app.core.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.modules.arcade.Adaptive
import gr.dimitris.app.modules.arcade.ArcadeGame
import gr.dimitris.app.modules.singsay.Key
import gr.dimitris.app.modules.singsay.Tempo
import gr.dimitris.app.modules.trace.TraceStrictness
import gr.dimitris.app.modules.trace.TraceViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        // Two since phase 12: the arcade is a reward, and sing-then-say is for the long phrases that
        // will not come out yet — not something a man who says most everyday words meets by default.
        assertEquals(setOf(ModuleId.ARCADE, ModuleId.SINGSAY), Settings.DEFAULT_OFF)
    }

    @Test fun `a default-off module stays on once someone asks for it`() = runBlocking {
        val s = newSettings()
        s.setModuleEnabled(ModuleId.ARCADE, true)
        s.setModuleEnabled(ModuleId.SINGSAY, true)
        assertEquals(ModuleId.entries.toSet(), s.enabledModules.first())
        s.setModuleEnabled(ModuleId.ARCADE, false)
        assertEquals(ModuleId.entries.toSet() - ModuleId.ARCADE, s.enabledModules.first())
    }

    // ---- «Τραγούδα και πες το» became default-off with phones already using it -------------------

    /** A phone installed today gets the defaults as they are today: no singing tile on the grid. */
    @Test fun `a new install keeps the newly default-off modules off`() = runBlocking {
        val s = newSettings()
        s.grandfatherNewlyDefaultOff()
        assertEquals(ModuleId.entries.toSet() - Settings.DEFAULT_OFF, s.enabledModules.first())
    }

    /**
     * His own phone, which has had «Τραγούδα και πες το» since phase 4. A build that took a tile off
     * his Today screen because a default moved would be the app deciding something nobody asked it
     * to decide, so the module he already had is written on as though somebody had switched it on.
     */
    @Test fun `an existing install keeps the modules it already had`() = runBlocking {
        val s = newSettings()
        s.setSeedVersion(3)               // anything at all in the store: this phone was here before
        s.grandfatherNewlyDefaultOff()
        assertTrue(ModuleId.SINGSAY in s.enabledModules.first())
        assertEquals(ModuleId.entries.toSet() - ModuleId.ARCADE, s.enabledModules.first())
    }

    /**
     * A caregiver who had already switched it off keeps it off — and the switch still works
     * afterwards. Before phase 12 "off" for this module meant its name in `disabled_modules`, which
     * [gr.dimitris.app.core.settings.Settings.enabledModules] reads first; a migration that left the
     * name there would have handed her a toggle she could turn on with nothing happening.
     */
    @Test fun `an existing install that had switched it off keeps it off, and can switch it on`() = runBlocking {
        val dir = createTempDirectory("settings").toFile()
        val store = PreferenceDataStoreFactory.create(
            scope = CoroutineScope(Dispatchers.IO + SupervisorJob()),
            produceFile = { File(dir, "settings.preferences_pb") },
        )
        store.edit { it[stringSetPreferencesKey("disabled_modules")] = setOf(ModuleId.SINGSAY.name) }
        val s = Settings(store)

        s.grandfatherNewlyDefaultOff()
        assertFalse(ModuleId.SINGSAY in s.enabledModules.first())

        s.setModuleEnabled(ModuleId.SINGSAY, true)
        assertTrue(ModuleId.SINGSAY in s.enabledModules.first())
    }

    /**
     * The one write that can get in front of the pass. «Ποιανού είναι το τηλέφωνο;» is the first
     * screen a brand-new phone ever shows and it writes `device_role` from the UI thread, which is
     * not ordered against the startup coroutine. Tap the role, lose the process before the pass
     * finishes, open the app again: the store is no longer empty, and a phone that has never had
     * «Τραγούδα και πες το» would be handed it. The role is therefore not evidence of use.
     *
     * The same test covers the second half of "opened twice": the pass writes its own generation
     * key, so on any launch after the first the store is non-empty by the pass's own doing. That is
     * why the generation is checked before emptiness is.
     */
    @Test fun `a new install opened twice is still a new install`() = runBlocking {
        val s = newSettings()
        s.setDeviceRole(DeviceRole.DIMITRIS)     // first launch: the role screen, then the process dies
        s.grandfatherNewlyDefaultOff()           // second launch
        assertFalse(ModuleId.SINGSAY in s.enabledModules.first())

        s.grandfatherNewlyDefaultOff()           // third launch, over the store the pass itself left
        assertEquals(ModuleId.entries.toSet() - Settings.DEFAULT_OFF, s.enabledModules.first())
    }

    /** Once per install: a later switch-off is not undone by the next launch. */
    @Test fun `the grandfathering runs once and never again`() = runBlocking {
        val s = newSettings()
        s.setSeedVersion(3)
        s.grandfatherNewlyDefaultOff()
        s.setModuleEnabled(ModuleId.SINGSAY, false)
        s.grandfatherNewlyDefaultOff()
        assertFalse(ModuleId.SINGSAY in s.enabledModules.first())
    }

    @Test fun `an ordinary module can be switched off and back on`() = runBlocking {
        val s = newSettings()
        val defaults = ModuleId.entries.toSet() - Settings.DEFAULT_OFF
        s.setModuleEnabled(ModuleId.WORDCOACH, false)
        assertEquals(defaults - ModuleId.WORDCOACH, s.enabledModules.first())
        s.setModuleEnabled(ModuleId.WORDCOACH, true)
        assertEquals(defaults, s.enabledModules.first())
    }

    /** Fifteen since phase 12: the ladder runs past the tables, the clock and change from a note. */
    @Test fun `numbers level starts at 1 and is clamped to 1__15`() = runBlocking {
        val s = newSettings()
        assertEquals(1, s.numbersLevel.first())
        s.setNumbersLevel(9); assertEquals(9, s.numbersLevel.first())
        s.setNumbersLevel(99); assertEquals(15, s.numbersLevel.first())
        s.setNumbersLevel(0); assertEquals(1, s.numbersLevel.first())
    }

    /** Its own counter with its own ceiling: four sentence levels, not the numbers module's fifteen. */
    /** Eight since phase 12: four levels of word order, and then the small words between them. */
    @Test fun `sentences level starts at 1 and is clamped to 1__8`() = runBlocking {
        val s = newSettings()
        assertEquals(1, s.sentencesLevel.first())
        s.setSentencesLevel(9); assertEquals(8, s.sentencesLevel.first())
        s.setSentencesLevel(0); assertEquals(1, s.sentencesLevel.first())
        s.setSentencesLevel(3); assertEquals(3, s.sentencesLevel.first())
        s.setSentencesLevel(7); assertEquals(7, s.sentencesLevel.first())
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

    // --------------------------------------------------- the difficulty he sets himself (spec §13)

    @Test fun `difficulty defaults to two per module and persists`() = runBlocking {
        val s = newSettings()
        ModuleId.entries.forEach { assertEquals("default for $it", Difficulty.DEFAULT, s.difficulty(it).first()) }
        s.setDifficulty(ModuleId.NUMBERS, 4)
        assertEquals(4, s.difficulty(ModuleId.NUMBERS).first())
        // One key per module: a man on single letters in «Γράψε» may still build four-word sentences.
        assertEquals(Difficulty.DEFAULT, s.difficulty(ModuleId.TRACE).first())
    }

    @Test fun `difficulty is clamped to one to five`() = runBlocking {
        val s = newSettings()
        s.setDifficulty(ModuleId.WORDCOACH, 99)
        assertEquals(5, s.difficulty(ModuleId.WORDCOACH).first())
        s.setDifficulty(ModuleId.WORDCOACH, -3)
        assertEquals(1, s.difficulty(ModuleId.WORDCOACH).first())
    }

    @Test fun `difficulty bounds default wide open and persist`() = runBlocking {
        val s = newSettings()
        assertEquals(Difficulty.MIN, s.difficultyFloor(ModuleId.ARCADE).first())
        assertEquals(Difficulty.MAX, s.difficultyCeiling(ModuleId.ARCADE).first())
        s.setDifficultyFloor(ModuleId.ARCADE, 2)
        s.setDifficultyCeiling(ModuleId.ARCADE, 3)
        assertEquals(2, s.difficultyFloor(ModuleId.ARCADE).first())
        assertEquals(3, s.difficultyCeiling(ModuleId.ARCADE).first())
    }

    /** A tap outside the fence changes nothing. The row says why; the store says nothing new. */
    @Test fun `a tap outside the caregiver's bounds is refused`() = runBlocking {
        val s = newSettings()
        s.setDifficultyCeiling(ModuleId.SENTENCES, 3)
        s.setDifficulty(ModuleId.SENTENCES, 5)
        assertEquals(3, s.difficulty(ModuleId.SENTENCES).first())
        s.setDifficultyFloor(ModuleId.SENTENCES, 2)
        s.setDifficulty(ModuleId.SENTENCES, 1)
        assertEquals(2, s.difficulty(ModuleId.SENTENCES).first())
    }

    /** Neither bound may cross the other, whichever is moved. */
    @Test fun `the bounds never cross`() = runBlocking {
        val s = newSettings()
        s.setDifficultyCeiling(ModuleId.NUMBERS, 2)
        s.setDifficultyFloor(ModuleId.NUMBERS, 5)
        assertEquals(2, s.difficultyFloor(ModuleId.NUMBERS).first())
        s.setDifficultyFloor(ModuleId.NUMBERS, 2)
        s.setDifficultyCeiling(ModuleId.NUMBERS, 1)
        assertEquals(2, s.difficultyCeiling(ModuleId.NUMBERS).first())
    }

    /**
     * A bound set after he has chosen brings his own setting with it. A fence that silently disagrees
     * with the dots he is looking at is worse than no fence: the dots would promise work the module
     * would then refuse to give him.
     */
    @Test fun `a new bound clamps the value he had already set`() = runBlocking {
        val s = newSettings()
        s.setDifficulty(ModuleId.WORDCOACH, 5)
        s.setDifficultyCeiling(ModuleId.WORDCOACH, 2)
        assertEquals(2, s.difficulty(ModuleId.WORDCOACH).first())

        s.setDifficulty(ModuleId.SINGSAY, 1)
        s.setDifficultyFloor(ModuleId.SINGSAY, 4)
        assertEquals(4, s.difficulty(ModuleId.SINGSAY).first())
    }

    /**
     * The dots move, and the module's level goes to the *bottom* of the new band: a man who has just
     * asked for harder work meets the easiest of the harder work first, and the progression climbs
     * from there. See [Difficulty].
     */
    @Test fun `moving the dots jumps each module's level to the bottom of the band`() = runBlocking {
        val s = newSettings()
        s.setDifficulty(ModuleId.NUMBERS, 3)
        assertEquals(Difficulty.numbers(3).first, s.numbersLevel.first())
        s.setDifficulty(ModuleId.SENTENCES, 4)
        assertEquals(Difficulty.sentences(4).first, s.sentencesLevel.first())
        s.setDifficulty(ModuleId.TRACE, 4)
        assertEquals(4, s.traceLevel.first())
    }

    /**
     * «Δεξί χέρι» has four sizes instead of a level, and a size is a **ceiling** whichever door the
     * change comes through — his own tap included.
     *
     * The first version of this asserted the opposite, and it was the I3 harm wearing the fix's
     * clothes: a hand at 40 dp, the hardest target the games can draw, was handed a 94 dp circle back
     * by a tap on dot 3. Worse on his real device, where the pinch sits near 120 dp and the tap game
     * near 50: the dot is derived from the biggest, so a tap on the *hardest* dot made one game
     * harder and the other easier, with nothing said.
     */
    @Test fun `a tap on the arcade dots never hands a game a bigger target`() = runBlocking {
        val s = newSettings()
        s.setArcadeTargetDp(ArcadeGame.TAP, Adaptive.MIN)          // 40 dp, worked down to over months
        s.setArcadeTargetDp(ArcadeGame.PINCH, Adaptive.MAX)        // 130 dp, the game he finds hardest
        s.setDifficulty(ModuleId.ARCADE, 3)

        assertEquals("40 dp earned was handed back", Adaptive.MIN, s.arcadeTargetDp(ArcadeGame.TAP).first(), 0.01f)
        // Bigger than the dot asks for is what does move — that is the dot doing its job.
        assertEquals(Difficulty.arcadeStart(3), s.arcadeTargetDp(ArcadeGame.PINCH).first(), 0.01f)
        // A game he has never played starts where a hand that has never played starts, held to the dot.
        assertEquals(Difficulty.arcadeClamp(Adaptive.START, 3), s.arcadeTargetDp(ArcadeGame.DRAG).first(), 0.01f)
    }

    /** And a 50 dp hand keeps its 50 dp at every dot whose ceiling still admits it. */
    @Test fun `a hand at fifty dp stays at fifty dp on every dot that admits it`() = runBlocking {
        val s = newSettings()
        ArcadeGame.entries.forEach { s.setArcadeTargetDp(it, 50f) }
        (Difficulty.MIN..Difficulty.MAX).filter { 50f <= Difficulty.arcadeStart(it) }.forEach { dot ->
            s.setDifficulty(ModuleId.ARCADE, dot)
            ArcadeGame.entries.forEach {
                assertEquals("dot $dot moved $it", 50f, s.arcadeTargetDp(it).first(), 0.01f)
            }
        }
    }

    /** A bound that moves his setting moves the level with it: from his side the dots have moved. */
    @Test fun `a bound that moves his setting moves the level too`() = runBlocking {
        val s = newSettings()
        s.setDifficulty(ModuleId.NUMBERS, 3)
        s.setDifficultyCeiling(ModuleId.NUMBERS, 1)
        assertEquals(1, s.difficulty(ModuleId.NUMBERS).first())
        assertEquals(Difficulty.numbers(1).last, s.numbersLevel.first())
    }

    /**
     * A caregiver's fence **clamps** his level; it does not send him back to the bottom of the band.
     *
     * He is on «Αριθμοί» dot 5 and has worked up to level 14; the therapist caps him at 3 to keep this
     * month on change-making. Band 3 is 5..7, so 14 becomes 7 — the hardest the new fence allows,
     * which is the whole of what the fence asked for. Resetting to 5 would take two more levels
     * nobody asked him to give up, with nothing on any screen to say so.
     */
    @Test fun `a caregiver's bound clamps the level rather than resetting it`() = runBlocking {
        val s = newSettings()
        s.setDifficulty(ModuleId.NUMBERS, 5)
        s.setNumbersLevel(14)
        s.setDifficultyCeiling(ModuleId.NUMBERS, 3)
        assertEquals(3, s.difficulty(ModuleId.NUMBERS).first())
        assertEquals("the fence took more than it asked for", 7, s.numbersLevel.first())
    }

    /** A level already inside the new band is not moved at all. */
    @Test fun `a bound that still contains his level leaves it exactly where it is`() = runBlocking {
        val s = newSettings()
        s.setDifficulty(ModuleId.NUMBERS, 5)
        s.setNumbersLevel(6)              // the dot follows the level down to 3
        assertEquals(3, s.difficulty(ModuleId.NUMBERS).first())
        s.setDifficultyCeiling(ModuleId.NUMBERS, 3)
        assertEquals(6, s.numbersLevel.first())
    }

    /** His own tap still jumps to the band's floor: harder work, met at its easiest end first. */
    @Test fun `his own tap still jumps to the bottom of the band`() = runBlocking {
        val s = newSettings()
        s.setNumbersLevel(14)
        s.setDifficulty(ModuleId.NUMBERS, 3)
        assertEquals(Difficulty.numbers(3).first, s.numbersLevel.first())
    }

    /**
     * The caregiver's own level stepper on the progress screen moves the dot with it. Otherwise the
     * module would clamp her level straight back out of the band at load and her stepper would be a
     * control that silently does nothing.
     */
    @Test fun `setting the level moves the dot to the band that owns it`() = runBlocking {
        val s = newSettings()
        s.setNumbersLevel(13)
        assertEquals(Difficulty.numbersDot(13), s.difficulty(ModuleId.NUMBERS).first())
        assertEquals(13, s.numbersLevel.first())

        s.setSentencesLevel(4)
        assertEquals(Difficulty.sentencesDot(4), s.difficulty(ModuleId.SENTENCES).first())

        // «Γράψε»'s level *is* its dot, from both directions.
        s.setTraceLevel(5)
        assertEquals(5, s.difficulty(ModuleId.TRACE).first())
        assertEquals(5, s.traceLevel.first())
    }

    // ------------------------------------------------- the phase-13 renumbering of «Γράψε»

    /**
     * A phone that was practising before phase 13 renumbered the writing levels.
     *
     * Old 4 was words and old 5 was words from memory; new 4 is a word said and never shown, and new
     * 5 is a whole sentence on a keyboard. Nothing in the store says what a level *means*, so a man
     * left on either would have opened the app on an exercise nobody had ever shown him. Both land on
     * the word level, which is where the work he was actually doing now lives — and the dot has to
     * come with the level, or [Difficulty.levelAtLoad] would clamp him straight back up.
     */
    @Test fun `an upgrading phone comes off the two levels that changed exercise`() = runBlocking {
        for (before in listOf(TraceViewModel.DICTATION_LEVEL, TraceViewModel.TYPED_LEVEL)) {
            val s = newSettings()
            s.setTraceLevel(before)
            s.renumberTraceForPhase13()
            assertEquals("level $before", TraceViewModel.WORD_LEVEL, s.traceLevel.first())
            assertEquals("the dot has to come with it", TraceViewModel.WORD_LEVEL, s.difficulty(ModuleId.TRACE).first())
            // Once, ever: a man who has since tapped his way to the dictation is not stepped back.
            s.setTraceLevel(TraceViewModel.DICTATION_LEVEL)
            s.renumberTraceForPhase13()
            assertEquals(TraceViewModel.DICTATION_LEVEL, s.traceLevel.first())
        }
    }

    /** The three levels whose exercise did not change are left exactly where they were. */
    @Test fun `the levels that still mean what they meant are not moved`() = runBlocking {
        for (before in 1..TraceViewModel.WORD_LEVEL) {
            val s = newSettings()
            s.setTraceLevel(before)
            s.renumberTraceForPhase13()
            assertEquals("level $before", before, s.traceLevel.first())
        }
    }

    /**
     * A fresh install has nothing to renumber and is left alone: no level written, so no dot derived
     * from one, and «Γράψε» opens at level 1 exactly as it did before this migration existed.
     */
    @Test fun `a fresh install is untouched by the renumbering`() = runBlocking {
        val s = newSettings()
        s.renumberTraceForPhase13()
        assertEquals(1, s.traceLevel.first())
        assertTrue("the migration decided a dot nobody had set", s.difficultyNeedsInit(ModuleId.TRACE).first())
        // And it still runs for the first phone that *does* have something, which is the flag's job.
        s.setTraceLevel(TraceViewModel.TYPED_LEVEL)
        s.renumberTraceForPhase13()
        assertEquals("the flag was spent on an empty store", TraceViewModel.TYPED_LEVEL, s.traceLevel.first())
    }

    /**
     * Her fence moves with the levels it was drawn around. A floor of 4 or 5 would pin him *on* an
     * exercise she never chose; a ceiling of 4 meant "not the hardest thing there is" and still does;
     * a ceiling of 5 meant "everything", and everything is still everything.
     */
    @Test fun `the caregiver's bounds are renumbered with the levels`() = runBlocking {
        val floors = newSettings()
        floors.setDifficultyFloor(ModuleId.TRACE, TraceViewModel.TYPED_LEVEL)
        floors.renumberTraceForPhase13()
        assertEquals(TraceViewModel.WORD_LEVEL, floors.difficultyFloor(ModuleId.TRACE).first())

        val capped = newSettings()
        capped.setDifficultyCeiling(ModuleId.TRACE, TraceViewModel.DICTATION_LEVEL)
        capped.renumberTraceForPhase13()
        assertEquals(TraceViewModel.WORD_LEVEL, capped.difficultyCeiling(ModuleId.TRACE).first())

        val open = newSettings()
        open.setDifficultyCeiling(ModuleId.TRACE, Difficulty.MAX)
        open.renumberTraceForPhase13()
        assertEquals("«everything» is still everything", Difficulty.MAX, open.difficultyCeiling(ModuleId.TRACE).first())
    }

    /** …but never past the fence: a level the bounds refuse is pulled back into the band. */
    @Test fun `a level above the caregiver's ceiling is pulled back into the band`() = runBlocking {
        val s = newSettings()
        s.setDifficultyCeiling(ModuleId.NUMBERS, 2)
        s.setNumbersLevel(13)
        assertEquals(2, s.difficulty(ModuleId.NUMBERS).first())
        assertEquals(Difficulty.numbers(2).last, s.numbersLevel.first())
    }

    // ------------------------------------------- where the dots start on a phone already in use

    /**
     * The dots arrived on a device his family had been practising with for months. Reading them off
     * his stored progress is the difference between "you are halfway down the ladder" and the truth —
     * and, since a sitting now runs at a level held inside the band, between keeping level 14 and
     * being quietly dropped to 4.
     */
    @Test fun `the first run derives each dot from the progress already stored`() = runBlocking {
        val s = newSettings()
        s.setNumbersLevel(13)
        s.setSentencesLevel(4)
        s.setTraceLevel(3)
        // setNumbersLevel already marks those three as derived; the arcade has no level, so it is the
        // one that proves the flag and the derivation on its own.
        assertEquals(true, s.difficultyNeedsInit(ModuleId.ARCADE).first())
        s.setArcadeTargetDp(ArcadeGame.TAP, 50f)
        s.setArcadeTargetDp(ArcadeGame.PINCH, 120f)
        s.initialiseDifficulty(ModuleId.ARCADE)

        assertEquals(Difficulty.numbersDot(13), s.difficulty(ModuleId.NUMBERS).first())
        assertEquals(Difficulty.sentencesDot(4), s.difficulty(ModuleId.SENTENCES).first())
        assertEquals(3, s.difficulty(ModuleId.TRACE).first())
        // Read off the *biggest* stored size, so the ceiling it implies moves none of the four.
        assertEquals(Difficulty.arcadeDot(120f), s.difficulty(ModuleId.ARCADE).first())
        assertEquals(50f, s.arcadeTargetDp(ArcadeGame.TAP).first(), 0.01f)
        assertEquals(120f, s.arcadeTargetDp(ArcadeGame.PINCH).first(), 0.01f)
    }

    /**
     * A module with nothing to read on a phone that *has* a history: the app exactly as it was the
     * day before. «Λέξεις» is the one this is really for — its dot is derived from nothing at all,
     * so this is the only value it can ever take, and at dot 1 it would lose its phrases.
     */
    @Test fun `a module with nothing to read on a used phone starts at the default`() = runBlocking {
        val s = newSettings()
        ModuleId.entries.forEach { s.initialiseDifficulty(it) }
        ModuleId.entries.forEach { assertEquals("$it", Difficulty.DEFAULT, s.difficulty(it).first()) }
    }

    /**
     * A phone the app has never met. `DifficultyInit` decides which of the two answers this is —
     * it is the only caller that can see the database as well — and hands it down.
     *
     * Dot 1 and not dot 2, because dot 2 is a band whose *floor* is level 3 in both «Αριθμοί» and
     * «Προτάσεις»: a phone installed this morning would have opened them a third of the way up two
     * ladders, for somebody it has never met.
     */
    @Test fun `a phone with no stored progress at all starts everyone at one`() = runBlocking {
        val s = newSettings()
        assertEquals(true, s.noStoredProgress.first())
        ModuleId.entries.forEach { s.initialiseDifficulty(it, whenNothing = Difficulty.MIN) }

        ModuleId.entries.forEach { assertEquals("$it", Difficulty.MIN, s.difficulty(it).first()) }
        assertEquals(1, Difficulty.numbers(s.difficulty(ModuleId.NUMBERS).first()).first)
        assertEquals(1, Difficulty.sentences(s.difficulty(ModuleId.SENTENCES).first()).first)
    }

    /** And a phone with any progress at all is not that phone, whichever module wrote it. */
    @Test fun `one stored level is enough to make a phone an old one`() = runBlocking {
        val s = newSettings()
        assertEquals(true, s.noStoredProgress.first())
        s.setTraceLevel(3)
        assertEquals(false, s.noStoredProgress.first())
    }

    /** Once, and only once: the second run is his own setting, not the migration's. */
    @Test fun `the derivation never runs twice`() = runBlocking {
        val s = newSettings()
        s.setNumbersLevel(13)
        s.initialiseDifficulty(ModuleId.NUMBERS)
        assertEquals(false, s.difficultyNeedsInit(ModuleId.NUMBERS).first())
        s.setDifficulty(ModuleId.NUMBERS, 1)
        s.initialiseDifficulty(ModuleId.NUMBERS)
        assertEquals(1, s.difficulty(ModuleId.NUMBERS).first())
    }

    /** A derived dot the caregiver's fence refuses is still held to the fence. */
    @Test fun `the derived dot obeys the caregiver's bounds`() = runBlocking {
        val s = newSettings()
        s.setDifficultyCeiling(ModuleId.SINGSAY, 2)
        s.initialiseDifficulty(ModuleId.SINGSAY, derived = 5)
        assertEquals(2, s.difficulty(ModuleId.SINGSAY).first())
    }

    /**
     * A tap of his own ends the migration for that module, before it has run.
     *
     * The two seed importers go first at startup and the derivation waits for them, so there is a
     * window — a few seconds on a first launch — in which he can reach a module's first screen and
     * tap a dot. His own tap is the strongest evidence there is, and it was being overwritten a
     * second later by a migration that had never met him.
     */
    @Test fun `a dot he taps is never overwritten by the migration`() = runBlocking {
        val s = newSettings()
        s.setDifficulty(ModuleId.SINGSAY, 5)
        assertEquals(false, s.difficultyNeedsInit(ModuleId.SINGSAY).first())
        s.initialiseDifficulty(ModuleId.SINGSAY, derived = 1)
        assertEquals(5, s.difficulty(ModuleId.SINGSAY).first())
    }

    /** So does a caregiver's level stepper, which is the same decision from the other side. */
    @Test fun `a level a caregiver set is never overwritten by the migration`() = runBlocking {
        val s = newSettings()
        s.setNumbersLevel(13)
        s.initialiseDifficulty(ModuleId.NUMBERS, derived = 1)
        assertEquals(Difficulty.numbersDot(13), s.difficulty(ModuleId.NUMBERS).first())
    }

    /**
     * The database can arrive after the app has started — a backup restored, or the first sync pull
     * onto a second phone — and two of the six dots are read out of it. Forgetting the derivation
     * lets the next run work them out again over the data that has actually turned up.
     */
    @Test fun `forgetting a derived dot lets the migration run again`() = runBlocking {
        val s = newSettings()
        s.initialiseDifficulty(ModuleId.SINGSAY, derived = null)   // nothing to read yet: the default
        assertEquals(Difficulty.DEFAULT, s.difficulty(ModuleId.SINGSAY).first())

        s.forgetDerivedDifficulty(setOf(ModuleId.SINGSAY))
        assertEquals(true, s.difficultyNeedsInit(ModuleId.SINGSAY).first())
        s.initialiseDifficulty(ModuleId.SINGSAY, derived = 4)
        assertEquals(4, s.difficulty(ModuleId.SINGSAY).first())
    }

    /** But a decision a person made is not evidence the database can invalidate. */
    @Test fun `forgetting never reaches a dot a person set`() = runBlocking {
        val s = newSettings()
        s.setDifficulty(ModuleId.SCRIPTS, 5)
        s.forgetDerivedDifficulty(setOf(ModuleId.SCRIPTS))
        assertEquals(false, s.difficultyNeedsInit(ModuleId.SCRIPTS).first())
        s.initialiseDifficulty(ModuleId.SCRIPTS, derived = 1)
        assertEquals(5, s.difficulty(ModuleId.SCRIPTS).first())
    }

    /** And forgetting one module says nothing about the other five. */
    @Test fun `forgetting one module leaves the rest derived`() = runBlocking {
        val s = newSettings()
        ModuleId.entries.forEach { s.initialiseDifficulty(it) }
        s.forgetDerivedDifficulty(setOf(ModuleId.SINGSAY))
        assertEquals(true, s.difficultyNeedsInit(ModuleId.SINGSAY).first())
        (ModuleId.entries - ModuleId.SINGSAY).forEach {
            assertEquals("$it", false, s.difficultyNeedsInit(it).first())
        }
    }

    /**
     * A tap or a bound that changes nothing changes nothing — not even the level. He re-reads the row
     * more than once, and tapping the dot he is already on must not cost him the level he has climbed
     * to inside the band.
     */
    @Test fun `a tap or a bound that changes nothing leaves the level alone`() = runBlocking {
        val s = newSettings()
        s.setNumbersLevel(6)
        val his = Difficulty.numbersDot(6)
        s.setDifficultyCeiling(ModuleId.NUMBERS, 5)
        s.setDifficultyFloor(ModuleId.NUMBERS, 1)
        assertEquals(6, s.numbersLevel.first())
        s.setDifficulty(ModuleId.NUMBERS, his)
        assertEquals(6, s.numbersLevel.first())
        s.setDifficulty(ModuleId.NUMBERS, his)
        assertEquals(6, s.numbersLevel.first())
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
