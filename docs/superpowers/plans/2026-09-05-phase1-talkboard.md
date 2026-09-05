# Dimitris' App — Phase 1 (Talk Board) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A communication board Dimitris can reach from any screen: tap a picture and the phone says the word in a caregiver's voice or Greek TTS, chain words into a sentence, quick phrases always on top, favourites sorted by his own usage.

**Architecture:** Phase 0 plumbing is in place (Room, `ItemRepository`, `TextToSpeech`, `Player`, design system, `AppGraph`, `Nav`). This phase adds a `pinned` flag to items (DB v2, auto-migration), an `ItemSpeaker` that picks recording-or-TTS, a pure `SentenceStrip`, the `TalkBoardScreen` + ViewModel, a global "open talk board" affordance, and a pin switch in the caregiver editor. The talk board is not a session `Module`; it is a communication aid that logs every tap as an `Attempt(TALKBOARD)` for usage ranking and insights.

**Tech Stack:** as Phase 0 (Kotlin 2.4, Compose M3, Room 2.8.4 with auto-migration, Navigation Compose).

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§3 shape, §7 phase 1)

## Global Constraints

- Every user-visible string is Greek.
- Minimum touch target `Sizes.touchMin` (72dp). Primary actions at the bottom.
- No timers. Success = icon + sound + haptic via `Feedback`.
- Every Room table keeps `id/createdAt/updatedAt/deleted`; never `fallbackToDestructiveMigration`; schema JSON committed under `app/schemas`.
- Works offline. Talk board taps write `Attempt(module = TALKBOARD, outcome = CORRECT, cueLevel = null)`.
- Package `gr.dimitris.app`. Build from `/mnt/nvme2TB/tomas/.claude/worktrees/phase0` with `./gradlew`; instrumented tests with `ANDROID_SERIAL=emulator-5554`.
- Commits: `feat(phase1): ...` / `test(phase1): ...`, ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## File structure (new or modified)

```
app/build.gradle.kts                                   + androidTest assets = schemas (migration test)
app/src/main/java/gr/dimitris/app/
  core/data/Entities.kt                                + Item.pinned
  core/data/Daos.kt                                    + ItemDao.observePinned, byIds
  core/data/AppDatabase.kt                             version 2, AutoMigration 1→2
  core/speech/ItemSpeaker.kt                           NEW: recording-or-TTS
  modules/talkboard/SentenceStrip.kt                   NEW: pure state
  modules/talkboard/Favourites.kt                      NEW: pure ranking
  modules/talkboard/TalkBoardViewModel.kt              NEW
  modules/talkboard/TalkBoardScreen.kt                 NEW
  AppGraph.kt                                          + speaker
  Nav.kt                                               + TALKBOARD route, LocalOpenTalkBoard
  ui/components/DimitrisScreen.kt                      + talk-board icon button
  today/TodayScreen.kt                                 + "Μίλα" button
  caregiver/content/ItemEditViewModel.kt, ItemEditScreen.kt   + pinned switch
app/src/test/.../core/speech/ItemSpeakerTest.kt
app/src/test/.../modules/talkboard/SentenceStripTest.kt, FavouritesTest.kt
app/src/androidTest/.../core/data/MigrationTest.kt, ItemDaoTest.kt (+1 case)
app/src/androidTest/.../modules/talkboard/TalkBoardScreenTest.kt
```

---

### Task 0: `Voice` — one owner for everything that makes sound

**Files:**
- Create: `core/audio/Voice.kt`
- Modify: `AppGraph.kt` (+ `voice`), `caregiver/content/ItemEditViewModel.kt` (route TTS/playback/recording through `graph.voice`), `today/SessionScreen.kt` (speak through `graph.voice`)
- Test: `androidTest/.../core/audio/VoiceTest.kt`

**Interfaces:**
- Produces: `class Voice(context, tts: TextToSpeech, player: Player, recorder: Recorder)` with `suspend fun speak(text: String, rate: Float): Result<Unit>`, `suspend fun play(file: File): Result<Unit>`, `fun startRecording(): File`, `fun stopRecording(): Recorded`, `fun cancelRecording()`, `val isRecording`, `fun quiet()`. Every operation first calls `quiet()` (stops TTS and playback; a running recording is left alone unless the new operation is a recording), requests transient audio focus (`AudioFocusRequest` with `AUDIOFOCUS_GAIN_TRANSIENT`, `USAGE_ASSISTANCE_ACCESSIBILITY`) for speak/play and abandons it afterwards; recording requests `AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE`. `AppGraph.voice` is the only thing screens and modules use from now on; `tts`, `player`, `recorder` stay on the graph for `Voice` itself and for tests.

- [ ] Implement; instrumented test: `speak` then `play` on a tiny generated m4a does not throw and `quiet()` is safe when idle; `startRecording` twice throws the Greek `check` message.
- [ ] Replace the phase-0 cross-stop calls in `ItemEditViewModel` and `SessionScreen` with `graph.voice.*`.
- [ ] `./gradlew -q testDebugUnitTest` and connected suite; commit `feat(phase1): Voice facade with audio focus`.

---

### Task 1: `Item.pinned` with database version 2

**Files:**
- Modify: `app/src/main/java/gr/dimitris/app/core/data/Entities.kt`, `Daos.kt`, `AppDatabase.kt`, `app/build.gradle.kts`
- Test: `app/src/androidTest/java/gr/dimitris/app/core/data/MigrationTest.kt`, `ItemDaoTest.kt`

**Interfaces:**
- Produces: `Item.pinned: Boolean` (default false), `ItemDao.observePinned(): Flow<List<Item>>`, `ItemDao.byIds(ids): List<Item>`, `AppDatabase` version 2.

- [ ] **Step 1: Expose schemas to instrumented tests**

In `app/build.gradle.kts`, inside `android { }`, add:
```kotlin
    sourceSets {
        getByName("androidTest").assets.srcDir("$projectDir/schemas")
    }
```

- [ ] **Step 2: Failing migration test and DAO case**

`app/src/androidTest/java/gr/dimitris/app/core/data/MigrationTest.kt`:
```kotlin
package gr.dimitris.app.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test

class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate1To2AddsPinnedWithDefaultFalse() {
        val name = "migration-test.db"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO items (id, text, kind, category, firstSound, source, createdAt, updatedAt, deleted) " +
                    "VALUES ('a', 'καφές', 'WORD', 'FOOD', 'κ', 'SEED', 1, 1, 0)"
            )
        }
        helper.runMigrationsAndValidate(name, 2, true).use { db ->
            db.query("SELECT pinned FROM items WHERE id = 'a'").use { c ->
                c.moveToFirst()
                assert(c.getInt(0) == 0) { "pinned should default to 0" }
            }
        }
    }
}
```

Add to `ItemDaoTest.kt`:
```kotlin
    @Test fun observePinnedReturnsOnlyPinnedActiveItems() = runTest {
        val pinned = Item(text = "νερό", category = Category.FOOD, pinned = true)
        val plain = Item(text = "ψωμί", category = Category.FOOD)
        val gone = Item(text = "τυρί", category = Category.FOOD, pinned = true, deleted = true)
        db.items().upsertAll(listOf(pinned, plain, gone))
        assertEquals(listOf(pinned), db.items().observePinned().first())
        assertEquals(setOf(pinned.id, plain.id), db.items().byIds(listOf(pinned.id, plain.id, gone.id)).map { it.id }.toSet())
    }
```

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew -q connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=gr.dimitris.app.core.data.ItemDaoTest` — Expected: compilation error (`pinned` unknown).

- [ ] **Step 3: Entity, DAO and database changes**

In `Entities.kt`, add the import `androidx.room.ColumnInfo` and, to `Item`, after `source`:
```kotlin
    /** Caregiver-pinned to the talk board favourites. */
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
```

In `Daos.kt`, add to `ItemDao`:
```kotlin
    @Query("SELECT * FROM items WHERE deleted = 0 AND pinned = 1 ORDER BY text") fun observePinned(): Flow<List<Item>>
    @Query("SELECT * FROM items WHERE deleted = 0 AND id IN (:ids)") suspend fun byIds(ids: List<String>): List<Item>
```

In `AppDatabase.kt`, change the annotation to:
```kotlin
@Database(
    entities = [Item::class, Recording::class, Attempt::class, Schedule::class, Session::class, ErrorLog::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)],
)
```
with `import androidx.room.AutoMigration`.

- [ ] **Step 4: Build and run the instrumented data tests**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew -q connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=gr.dimitris.app.core.data`
Expected: MigrationTest 1 pass, ItemDaoTest 4 pass, and `app/schemas/gr.dimitris.app.core.data.AppDatabase/2.json` now exists (commit it).

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle.kts app/schemas app/src/main/java/gr/dimitris/app/core/data app/src/androidTest
git commit -m "feat(phase1): pinned items with database v2 auto-migration

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: ItemSpeaker, SentenceStrip, Favourites (pure logic + tests)

**Files:**
- Create: `app/src/main/java/gr/dimitris/app/core/speech/ItemSpeaker.kt`, `modules/talkboard/SentenceStrip.kt`, `modules/talkboard/Favourites.kt`
- Modify: `AppGraph.kt` (add `speaker`)
- Test: `app/src/test/java/gr/dimitris/app/core/speech/ItemSpeakerTest.kt`, `modules/talkboard/SentenceStripTest.kt`, `modules/talkboard/FavouritesTest.kt`

**Interfaces:**
- Produces: `ItemSpeaker(tts, recordingFor, play, rate, resolve)` with `suspend fun speak(item): VoiceUsed`, `suspend fun speakText(text)`; `enum VoiceUsed { RECORDING, TTS }`; `SentenceStrip(max = 6)` with `items: StateFlow<List<Item>>`, `add(item): Boolean`, `removeLast()`, `clear()`, `text`; `Favourites.rank(all, pinnedIds, usage: List<ItemCount>, limit = 12): List<Item>`; `AppGraph.speaker`.

- [ ] **Step 1: Failing tests**

`ItemSpeakerTest.kt`:
```kotlin
package gr.dimitris.app.core.speech

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Recording
import gr.dimitris.app.core.data.Who
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import kotlin.io.path.createTempFile

class ItemSpeakerTest {
    private val tts = FakeTextToSpeech()
    private val played = mutableListOf<File>()
    private val item = Item(text = "καφές")

    private fun speaker(recording: Recording?, playResult: Result<Unit> = Result.success(Unit)) =
        ItemSpeaker(tts, recordingFor = { recording }, play = { played += it; playResult }, rate = { 0.8f })

    @Test fun `plays the caregiver recording when the file exists`() = runTest {
        val file = createTempFile("rec", ".m4a").toFile()
        val voice = speaker(Recording(itemId = item.id, path = file.absolutePath, who = Who.CAREGIVER, durationMs = 500)).speak(item)
        assertEquals(VoiceUsed.RECORDING, voice)
        assertEquals(listOf(file), played)
        assertEquals(emptyList<String>(), tts.spoken)
    }

    @Test fun `falls back to TTS when there is no recording`() = runTest {
        assertEquals(VoiceUsed.TTS, speaker(null).speak(item))
        assertEquals(listOf("καφές"), tts.spoken)
    }

    @Test fun `falls back to TTS when the recording file is missing`() = runTest {
        val voice = speaker(Recording(itemId = item.id, path = "/nowhere/x.m4a", who = Who.CAREGIVER, durationMs = 500)).speak(item)
        assertEquals(VoiceUsed.TTS, voice)
        assertEquals(listOf("καφές"), tts.spoken)
    }

    @Test fun `falls back to TTS when playback fails`() = runTest {
        val file = createTempFile("rec", ".m4a").toFile()
        val voice = speaker(Recording(itemId = item.id, path = file.absolutePath, who = Who.CAREGIVER, durationMs = 500), Result.failure(RuntimeException("x"))).speak(item)
        assertEquals(VoiceUsed.TTS, voice)
    }
}
```

`SentenceStripTest.kt`:
```kotlin
package gr.dimitris.app.modules.talkboard

import gr.dimitris.app.core.data.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceStripTest {
    private val want = Item(text = "Θέλω")
    private val coffee = Item(text = "καφέ")

    @Test fun `joins texts with spaces`() {
        val s = SentenceStrip()
        assertTrue(s.add(want)); assertTrue(s.add(coffee))
        assertEquals("Θέλω καφέ", s.text)
    }

    @Test fun `removeLast and clear`() {
        val s = SentenceStrip().apply { add(want); add(coffee) }
        s.removeLast()
        assertEquals(listOf(want), s.items.value)
        s.clear()
        assertEquals("", s.text)
        s.removeLast()   // no-op on empty
        assertEquals(emptyList<Item>(), s.items.value)
    }

    @Test fun `refuses beyond max`() {
        val s = SentenceStrip(max = 2).apply { add(want); add(coffee) }
        assertFalse(s.add(Item(text = "τώρα")))
        assertEquals(2, s.items.value.size)
    }
}
```

`FavouritesTest.kt`:
```kotlin
package gr.dimitris.app.modules.talkboard

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemCount
import org.junit.Assert.assertEquals
import org.junit.Test

class FavouritesTest {
    private val a = Item(text = "α"); private val b = Item(text = "β"); private val c = Item(text = "γ"); private val d = Item(text = "δ")
    private val all = listOf(a, b, c, d)

    @Test fun `pinned first, then by usage, then nothing else`() {
        val ranked = Favourites.rank(all, pinnedIds = setOf(c.id), usage = listOf(ItemCount(b.id, 5), ItemCount(a.id, 2), ItemCount(c.id, 9)))
        assertEquals(listOf(c, b, a), ranked)
    }

    @Test fun `limit applies after pinned`() {
        val ranked = Favourites.rank(all, pinnedIds = setOf(d.id), usage = listOf(ItemCount(a.id, 3), ItemCount(b.id, 2), ItemCount(c.id, 1)), limit = 2)
        assertEquals(listOf(d, a), ranked)
    }

    @Test fun `usage of unknown or deleted ids is ignored`() {
        assertEquals(listOf(a), Favourites.rank(all, emptySet(), listOf(ItemCount("ghost", 99), ItemCount(a.id, 1))))
    }
}
```

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.core.speech.ItemSpeakerTest' --tests 'gr.dimitris.app.modules.talkboard.*'` — Expected: compilation errors.

- [ ] **Step 2: Implement**

`core/speech/ItemSpeaker.kt`:
```kotlin
package gr.dimitris.app.core.speech

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Recording
import java.io.File

enum class VoiceUsed { RECORDING, TTS }

/** Says an item the best way available: the caregiver's recording if it exists and plays, else Greek TTS. */
class ItemSpeaker(
    private val tts: TextToSpeech,
    private val recordingFor: suspend (Item) -> Recording?,
    private val play: suspend (File) -> Result<Unit>,
    private val rate: suspend () -> Float,
    /** Paths are stored relative to filesDir since the phase-0 hardening; absolute paths pass through. */
    private val resolve: (String) -> File = { File(it) },
) {
    suspend fun speak(item: Item): VoiceUsed {
        val recording = recordingFor(item)
        if (recording != null) {
            val file = resolve(recording.path)
            if (file.exists() && play(file).isSuccess) return VoiceUsed.RECORDING
        }
        tts.speak(item.text, rate())
        return VoiceUsed.TTS
    }

    suspend fun speakText(text: String) {
        if (text.isNotBlank()) tts.speak(text, rate())
    }
}
```

`modules/talkboard/SentenceStrip.kt`:
```kotlin
package gr.dimitris.app.modules.talkboard

import gr.dimitris.app.core.data.Item
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The row of chosen words at the top of the talk board. */
class SentenceStrip(private val max: Int = 6) {
    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    val text: String get() = _items.value.joinToString(" ") { it.text }

    fun add(item: Item): Boolean {
        if (_items.value.size >= max) return false
        _items.value = _items.value + item
        return true
    }

    fun removeLast() { _items.value = _items.value.dropLast(1) }
    fun clear() { _items.value = emptyList() }
}
```

`modules/talkboard/Favourites.kt`:
```kotlin
package gr.dimitris.app.modules.talkboard

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemCount

/** Pinned items first (alphabetical), then the most-used ones, up to [limit]. Only items in [all] count. */
object Favourites {
    fun rank(all: List<Item>, pinnedIds: Set<String>, usage: List<ItemCount>, limit: Int = 12): List<Item> {
        val byId = all.associateBy { it.id }
        val pinned = all.filter { it.id in pinnedIds }.sortedBy { it.text }
        val used = usage.sortedByDescending { it.n }.mapNotNull { byId[it.itemId] }.filter { it.id !in pinnedIds }
        return (pinned + used).take(limit)
    }
}
```

In `AppGraph.kt` add (with imports `gr.dimitris.app.core.speech.ItemSpeaker` and `kotlinx.coroutines.flow.first`):
```kotlin
    /** Recording-or-TTS voice for items. Built per use so it always sees the current db and settings. */
    val speaker: ItemSpeaker
        get() = ItemSpeaker(tts, recordingFor = { items.modelRecording(it) }, play = { voice.play(it) }, rate = { settings.speechRate.first() }, resolve = { files.resolve(it) })
```

- [ ] **Step 3: Run tests**

Run: `./gradlew -q testDebugUnitTest` — Expected: all pass (4 + 3 + 3 new).

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/gr/dimitris/app/core/speech/ItemSpeaker.kt app/src/main/java/gr/dimitris/app/modules/talkboard app/src/main/java/gr/dimitris/app/AppGraph.kt app/src/test
git commit -m "feat(phase1): item speaker, sentence strip and favourites ranking

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Talk board screen, route, and the always-one-tap-away button

**Files:**
- Create: `app/src/main/java/gr/dimitris/app/modules/talkboard/TalkBoardViewModel.kt`, `TalkBoardScreen.kt`
- Modify: `Nav.kt`, `ui/components/DimitrisScreen.kt`, `today/TodayScreen.kt`
- Test: `app/src/androidTest/java/gr/dimitris/app/modules/talkboard/TalkBoardScreenTest.kt`

**Interfaces:**
- Consumes: `AppGraph.speaker`, `ItemRepository.observeAll`, `ItemDao.observePinned`, `AttemptDao.insert/mostUsed`, `SentenceStrip`, `Favourites`, `PictureCard`, `BigButton`, `QuietButton`, `DimitrisScreen`.
- Produces: `Routes.TALKBOARD = "talk"`, `LocalOpenTalkBoard: ProvidableCompositionLocal<(() -> Unit)?>`, `TalkBoardScreen(onBack)`, `TalkBoardViewModel(graph)`, `DimitrisScreen(..., talkButton: Boolean = true, ...)`.

- [ ] **Step 1: ViewModel**

`modules/talkboard/TalkBoardViewModel.kt`:
```kotlin
package gr.dimitris.app.modules.talkboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemCount
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class Tab(val label: String) {
    object Favourites : Tab("Αγαπημένα")
    data class Cat(val category: Category) : Tab(category.greek)
}

class TalkBoardViewModel(private val graph: AppGraph) : ViewModel() {
    val strip = SentenceStrip()

    private val all: StateFlow<List<Item>> = graph.items.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val usage = MutableStateFlow<List<ItemCount>>(emptyList())
    private val pinnedIds: StateFlow<Set<String>> = graph.db.items().observePinned().map { l -> l.map { it.id }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _tab = MutableStateFlow<Tab>(Tab.Favourites)
    val tab: StateFlow<Tab> = _tab.asStateFlow()

    /** Tabs: favourites, then every category that has at least one item, QUICK excluded (it has its own row). */
    val tabs: StateFlow<List<Tab>> = all.map { items ->
        listOf(Tab.Favourites) + Category.entries.filter { c -> c != Category.QUICK && items.any { it.category == c } }.map { Tab.Cat(it) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), listOf(Tab.Favourites))

    val quick: StateFlow<List<Item>> = all.map { l -> l.filter { it.category == Category.QUICK } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val shown: StateFlow<List<Item>> = combine(all, pinnedIds, usage, _tab) { items, pinned, use, tab ->
        when (tab) {
            Tab.Favourites -> Favourites.rank(items, pinned, use)
            is Tab.Cat -> items.filter { it.category == tab.category }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _stripFull = MutableStateFlow(false)
    val stripFull: StateFlow<Boolean> = _stripFull.asStateFlow()

    init { refreshUsage() }

    fun selectTab(t: Tab) { _tab.value = t }

    /** Grid tap: say it and add it to the sentence. */
    fun tap(item: Item) {
        _stripFull.value = !strip.add(item)
        viewModelScope.launch { graph.speaker.speak(item); log(item, inStrip = true) }
    }

    /** Quick row tap: say it immediately, never added to the sentence. */
    fun tapQuick(item: Item) {
        viewModelScope.launch { graph.speaker.speak(item); log(item, inStrip = false) }
    }

    fun speakStrip() {
        val items = strip.items.value
        if (items.isEmpty()) return
        viewModelScope.launch {
            graph.speaker.speakText(strip.text)
            graph.feedback.success()
        }
    }

    fun undo() { strip.removeLast(); _stripFull.value = false }
    fun clear() { strip.clear(); _stripFull.value = false }

    private suspend fun log(item: Item, inStrip: Boolean) {
        val t = now()
        runCatching {
            graph.db.attempts().insert(
                Attempt(itemId = item.id, module = ModuleId.TALKBOARD, startedAt = t, durationMs = 0, outcome = Outcome.CORRECT,
                    cueLevel = null, detail = if (inStrip) """{"strip":true}""" else "{}")
            )
        }.onFailure { graph.errors.record("talkboard log", it) }
        refreshUsage()
    }

    private fun refreshUsage() {
        viewModelScope.launch { runCatching { usage.value = graph.db.attempts().mostUsed(ModuleId.TALKBOARD, 24) } }
    }
}
```

- [ ] **Step 2: Screen**

`modules/talkboard/TalkBoardScreen.kt`:
```kotlin
package gr.dimitris.app.modules.talkboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Backspace
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.PictureCard
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun TalkBoardScreen(onBack: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: TalkBoardViewModel = viewModel { TalkBoardViewModel(graph) }
    val strip by vm.strip.items.collectAsStateWithLifecycle()
    val stripFull by vm.stripFull.collectAsStateWithLifecycle()
    val tabs by vm.tabs.collectAsStateWithLifecycle()
    val tab by vm.tab.collectAsStateWithLifecycle()
    val quick by vm.quick.collectAsStateWithLifecycle()
    val shown by vm.shown.collectAsStateWithLifecycle()

    DimitrisScreen(
        title = "Μίλα",
        onBack = onBack,
        talkButton = false,
        bottom = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BigButton("Πες το", onClick = vm::speakStrip, icon = Icons.Rounded.VolumeUp, tone = ButtonTone.Secondary,
                    enabled = strip.isNotEmpty(), modifier = Modifier.weight(2f))
                Spacer(Modifier.width(Sizes.gapSmall))
                QuietButton("", onClick = vm::undo, icon = Icons.AutoMirrored.Rounded.Backspace, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(Sizes.gapSmall))
                QuietButton("", onClick = vm::clear, icon = Icons.Rounded.Clear, modifier = Modifier.weight(1f))
            }
        },
    ) {
        StripRow(strip, stripFull)
        Spacer(Modifier.height(Sizes.gapSmall))

        if (quick.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
                items(quick, key = { it.id }) { item -> QuickChip(item.text) { vm.tapQuick(item) } }
            }
            Spacer(Modifier.height(Sizes.gapSmall))
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall)) {
            items(tabs, key = { it.label }) { t ->
                FilterChip(selected = t == tab, onClick = { vm.selectTab(t) },
                    label = { Text(t.label, style = MaterialTheme.typography.bodyLarge) }, modifier = Modifier.heightIn(min = 56.dp))
            }
        }
        Spacer(Modifier.height(Sizes.gapSmall))

        if (shown.isEmpty()) {
            Text(
                if (tab == Tab.Favourites) "Ό,τι χρησιμοποιείς πιο συχνά θα εμφανίζεται εδώ." else "Τίποτα εδώ ακόμα.",
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(Sizes.pictureCard),
            horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
            verticalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
            modifier = Modifier.fillMaxWidth(),
        ) {
            items(shown, key = { it.id }) { item ->
                PictureCard(imageFile = item.imagePath?.let { graph.files.resolve(it) }, label = item.text, onClick = { vm.tap(item) })
            }
        }
    }
}

@Composable
private fun StripRow(items: List<Item>, full: Boolean) {
    Surface(shape = RoundedCornerShape(Sizes.corner), color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth().heightIn(min = Sizes.touchMin)) {
        Box(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), contentAlignment = Alignment.CenterStart) {
            if (items.isEmpty()) {
                Text("Πάτα εικόνες για να φτιάξεις πρόταση.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Column {
                    Text(items.joinToString(" ") { it.text }, style = MaterialTheme.typography.headlineMedium)
                    if (full) Text("Γεμάτο. Πες το ή σβήσε.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun QuickChip(text: String, onClick: () -> Unit) {
    FilterChip(selected = false, onClick = onClick, label = { Text(text, style = MaterialTheme.typography.labelLarge) },
        modifier = Modifier.heightIn(min = Sizes.touchMin))
}
```

- [ ] **Step 3: Route, global affordance, Today button**

`Nav.kt`: add `const val TALKBOARD = "talk"` to `Routes`; add at file level:
```kotlin
val LocalOpenTalkBoard = staticCompositionLocalOf<(() -> Unit)?> { null }
```
(import `androidx.compose.runtime.staticCompositionLocalOf`, `androidx.compose.runtime.CompositionLocalProvider`). Wrap the `NavHost` in
```kotlin
    CompositionLocalProvider(LocalOpenTalkBoard provides { nav.navigate(Routes.TALKBOARD) { launchSingleTop = true } }) {
        NavHost(...) { ... }
    }
```
and register:
```kotlin
        composable(Routes.TALKBOARD) { TalkBoardScreen(onBack = { nav.popBackStack() }) }
```

`ui/components/DimitrisScreen.kt`: add parameter `talkButton: Boolean = true` (after `onBack`). Change the header condition to `if (onBack != null || title != null || (talkButton && LocalOpenTalkBoard.current != null))` and, inside the header `Row`, after the title add:
```kotlin
                val openTalk = LocalOpenTalkBoard.current
                if (talkButton && openTalk != null) {
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = { feedback.tap(); openTalk() }, modifier = Modifier.size(Sizes.touchMin)) {
                        Icon(Icons.Rounded.Forum, contentDescription = "Μίλα", modifier = Modifier.size(Sizes.icon), tint = MaterialTheme.colorScheme.secondary)
                    }
                }
```
with imports `gr.dimitris.app.LocalOpenTalkBoard`, `androidx.compose.material.icons.rounded.Forum`. Give the title `Modifier.weight(1f, fill = false)` only if the row needs it; simplest is: title, then `Spacer(Modifier.weight(1f))`, then the icon.

`today/TodayScreen.kt`: change `bottom` to a `Column`:
```kotlin
        bottom = {
            val openTalk = LocalOpenTalkBoard.current
            if (openTalk != null) {
                BigButton("Μίλα", onClick = openTalk, icon = Icons.Rounded.Forum, tone = ButtonTone.Secondary)
                Spacer(Modifier.height(Sizes.gapSmall))
            }
            BigButton("Ξεκίνα", onClick = onStart, icon = Icons.Rounded.PlayArrow)
        },
```
and pass `talkButton = false` to Today's `DimitrisScreen` (it has its own big button). Imports: `gr.dimitris.app.LocalOpenTalkBoard`, `gr.dimitris.app.ui.components.ButtonTone`, `androidx.compose.material.icons.rounded.Forum`.

- [ ] **Step 4: Instrumented test**

`app/src/androidTest/java/gr/dimitris/app/modules/talkboard/TalkBoardScreenTest.kt`:
```kotlin
package gr.dimitris.app.modules.talkboard

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import gr.dimitris.app.MainActivity
import org.junit.Rule
import org.junit.Test

class TalkBoardScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun opensFromTodayAndShowsFavouritesTab() {
        compose.onNodeWithText("Μίλα").performClick()
        compose.onNodeWithText("Αγαπημένα").assertIsDisplayed()
        compose.onNodeWithText("Πες το").assertIsDisplayed()
    }
}
```

- [ ] **Step 5: Build, run instrumented tests, use it**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew -q installDebug connectedDebugAndroidTest`
Expected: all pass. On the emulator: Today shows "Μίλα" above "Ξεκίνα"; the board shows the quick row (Ναι, Όχι, ...), tabs, pictograms; tapping a card speaks and fills the strip; "Πες το" speaks the sentence; backspace removes the last word. Caregiver screens show a small chat icon top-right that opens the board.

- [ ] **Step 6: Commit**

```bash
git add app/src/main app/src/androidTest
git commit -m "feat(phase1): talk board with sentence strip, quick phrases and favourites

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Caregiver pin switch

**Files:**
- Modify: `caregiver/content/ItemEditViewModel.kt` (state + setter + save), `ItemEditScreen.kt` (switch)

**Interfaces:**
- Consumes: `Item.pinned`.
- Produces: `ItemEditState.pinned`, `ItemEditViewModel.setPinned(Boolean)`.

- [ ] **Step 1: ViewModel**

In `ItemEditState` add `val pinned: Boolean = false,` after `category`. In `init`, when loading, add `pinned = item.pinned`. Add `fun setPinned(on: Boolean) = _state.update { it.copy(pinned = on) }`. In `save`, include `pinned = s.pinned` in the `.copy(...)`.

- [ ] **Step 2: Screen**

In `ItemEditScreen`, after the category `FlowRow` block and its spacer, add:
```kotlin
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("Στα αγαπημένα του πίνακα", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Switch(checked = s.pinned, onCheckedChange = vm::setPinned)
            }
            Spacer(Modifier.height(Sizes.gap))
```
with imports `androidx.compose.material3.Switch`, `androidx.compose.ui.Alignment`.

- [ ] **Step 3: Build, check on the emulator**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew -q installDebug`
Expected: pin "καφές" in the editor → it appears first under "Αγαπημένα" on the talk board.

- [ ] **Step 4: Commit**

```bash
git add app/src/main
git commit -m "feat(phase1): caregivers can pin items to talk board favourites

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Phase 1 verification

- [x] **Step 1: Full suites**

Run: `./gradlew -q testDebugUnitTest && ANDROID_SERIAL=emulator-5554 ./gradlew -q connectedDebugAndroidTest` — Expected: all green.

- [x] **Step 2: Install on the phone and walk through**

`ANDROID_SERIAL=R5CWC2C1KSJ ./gradlew -q installDebug`, then: Today → Μίλα → quick "Ναι" speaks immediately; FOOD tab → tap καφές (speaks, strip shows "καφές"); VERBS → θέλω; "Πες το" speaks "καφές θέλω"; backspace twice empties the strip; favourites now lists καφές and θέλω. Record a caregiver voice for καφές, tap it again: the recording plays instead of TTS.

- [x] **Step 3: Note and commit**

Append "Phase 1 verified on <date>, <device>" with any problems under this task in this plan file; commit with `docs(phase1): verification notes`.

---

#### Phase 1 verified on 2026-09-05 (emulator-5554)

Chris's phone (R5CWC2C1KSJ) was disconnected for this session, so the physical-device install/walkthrough in Step 2 was run on `emulator-5554` instead; the phone step itself was skipped.

**Test suites**

| Suite | Command | Result |
|---|---|---|
| Unit tests | `./gradlew -q testDebugUnitTest` | 51/51 passed, 0 failures, 0 errors (13 test classes) |
| Instrumented tests | `ANDROID_SERIAL=emulator-5554 ./gradlew -q connectedDebugAndroidTest` | 21/21 passed, 0 failures, 0 errors, run on `Medium_Phone_API_36(AVD)` |

`connectedDebugAndroidTest` uninstalled the app afterwards as expected; `installDebug` was re-run before the manual walkthrough.

**Manual walkthrough checklist**

| Step | Result |
|---|---|
| Today → Μίλα | Talk board opens, quick-phrase row and category tabs render |
| Quick "Ναι" speaks immediately | Confirmed — tapping "Ναι" triggered a real Greek TTS synthesis (`GoogleTTSServiceImpl: Synthesis request for locale ell-GRC`, dispatch `el-gr-x-vfz-seanet-embedded`) |
| FOOD tab → tap καφές (speaks, strip shows "καφές") | Confirmed — TTS synthesis fired and the sentence strip showed "καφές" |
| VERBS tab → θέλω | Confirmed — added to strip |
| "Πες το" speaks the strip | Confirmed — TTS synthesis fired for the full strip text |
| Backspace twice empties the strip | Confirmed once tapped on the actual button bounds (see "odd" note below) |
| Favourites lists καφές and θέλω | Confirmed, see note below — the ranking briefly failed to surface the top-used items within one long-lived screen instance, then was correct after reopening the board |
| Record a caregiver voice for καφές, save | Confirmed — recorded via caregiver → Λέξεις και εικόνες → καφές → Ηχογράφηση → Στοπ → Αποθήκευση. Row and file verified in `recordings` table and on disk (`recordings/59228fd5-….m4a`, ~603 KB, `who=CAREGIVER`) |
| Tap καφές again: recording plays instead of TTS | Confirmed — logcat showed `MediaPlayerService` activity and no `GoogleTTSServiceImpl`/"Synthesis request" line for that tap |
| Caregiver home → Ρυθμίσεις → talk icon (top-right) opens the board | Confirmed — opens Μίλα directly from Settings, and back navigation returns to Ρυθμίσεις |
| Caregiver → Σφάλματα | Confirmed empty: "Κανένα σφάλμα. Ωραία." |
| Crash → restart-to-Today | Not exercised — no in-app crash trigger available (`adb shell am crash` kills the process externally and does not go through the app's handler); covered instead by `CrashHandlerTest` for the log-write path |

**Database check**

Pulled `dimitris.db` (+ `-wal`/`-shm`) via `run-as` after the walkthrough:
- `attempts`: 11 rows, all with `module='TALKBOARD'` (0 rows for any other module)
- `error_logs`: 0 rows
- `recordings`: 1 row for καφές (`who=CAREGIVER`), backing file present under `files/recordings/`

**Findings / anything odd**

1. **Favourites ranking can go stale within one screen instance.** While rapidly tapping several items in the same `TalkBoardScreen`/`TalkBoardViewModel` instance, the "Αγαπημένα" tab twice rendered only 4 of the ≥6 used items and — reproducibly — omitted the two items with the *highest* usage counts (θέλω, n=3; Ναι, n=2), while showing several n=1 items instead. A direct SQL replica of `AttemptsDao.mostUsed` against the pulled DB returned the correct, complete ranking, so the DB and query are fine; the discrepancy is in the ViewModel's in-memory `usage` `MutableStateFlow`. `refreshUsage()` is fired fire-and-forget (`viewModelScope.launch`) on every tap with no de-duplication/sequencing, so out-of-order completion of concurrent `mostUsed` queries can let an older result overwrite a newer one. Re-entering the Talk board (fresh ViewModel) immediately showed the correct order (θέλω, Ναι, καφές, …). Not a data-loss issue (the DB itself is always correct) and not blocking for Phase 1, but worth a defensive fix later (e.g. track a monotonic request id, or use `mapLatest`/`collectLatest` instead of ad-hoc launches).
2. **Quick-phrase and item-grid ordering follows raw SQLite text ordering, not Greek alphabetical order.** `observeActive()` uses `ORDER BY category, text`, which is byte/codepoint order under SQLite's default `BINARY` collation. Accented capitals like "Ό" (U+038C) sort before plain capitals like "Β"/"Δ"/"Ν" (U+0392+), so the QUICK row shows "Όχι" before "Ναι", "Βοήθεια", etc. instead of true alphabetical order. Purely cosmetic (everything is still reachable and correct), but worth a note for a future polish pass (e.g. a Greek-aware collation or explicit ordering).
3. **No app code changes were made for this task** — both findings above are recorded for a future task, per the brief.

Commit: `docs(phase1): verification notes`.
