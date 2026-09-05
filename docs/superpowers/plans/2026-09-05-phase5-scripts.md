# Dimitris' App — Phase 5 (Script Practice) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rehearse the fixed dialogues Dimitris needs in life: ordering coffee, a taxi, a phone call to his parents, the doctor's reception, small talk. The app plays the other side in a caregiver's voice, waits for his line with the cue ladder available, and he taps through.

**Architecture:** DB v5 adds `scripts` and `script_lines`; each line points at an `Item` of kind `SCRIPT_LINE` so recordings, cues and attempts reuse everything that exists. A `ScriptRepository` owns the two tables; five seed scripts ship as an asset; caregivers get a list + editor; the `ScriptsModule` runs a dialogue screen built on `CueLadder`, `ItemSpeaker`, `Scheduler` (keyed by script id). Attempts: one per Dimitris line (module SCRIPTS, cueLevel), plus one schedule update per script.

**Tech Stack:** as before. Gson for the seed asset.

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§5 Script, §7 phase 5)

## Global Constraints

- Greek-only UI; 72dp minimum; primary actions at the bottom; no timers; success = icon + sound + haptic.
- Other-side lines are spoken with the caregiver recording when present, else TTS; Dimitris' lines use the cue ladder exactly as the word coach (levels 0–4, level 2 skipped without a syllable; confirm at 0–2 CORRECT, 3–4 ASSISTED, skip SKIPPED).
- Attempts for Dimitris' lines: `itemId = line.itemId`, `module = SCRIPTS`, `cueLevel`, `detail = {"scriptId":"…","position":n}`. Scheduler row per script: `Scheduler.record(script.id, SCRIPTS, outcome, cueLevel)` with the worst (highest) cue level used in the run, outcome CORRECT if no line was skipped and worst cue ≤ 2, ASSISTED if worst cue ≥ 3, SKIPPED if any line was skipped.
- Every Room change: sync-ready columns, auto-migration, schema committed.
- Build from `/mnt/nvme2TB/tomas/.claude/worktrees/phase0`; instrumented with `ANDROID_SERIAL=emulator-5554` and `-Pandroid.testInstrumentationRunnerArguments.package=…` filters. Commits `feat(phase5): ...` ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

- **Module contract (after the phase-2 fix wave):** `Module.Screen(items, sessionId, onDone, onLeave)` — `onDone` = all exercises finished, `onLeave` = the user pressed back (the screen's `onBack` calls `vm.leave(onLeave)`: the ViewModel cancels a running take, quiets the voice, joins its last app-scope write, then invokes `onLeave` — both callbacks are invoked only after the module's Attempt rows have landed). Attempt/schedule writes go on `graph.scope` (they must survive the screen); the last write is joined before `done` is published. Every module screen has `DisposableEffect(Unit) { onDispose { graph.voice.quiet() } }` semantics through the session/practice hosts. Sessions cap at 15 items across modules (`SessionBudget.allowance(n)`), so `planFor` lists may be truncated.

---

## File structure

```
app/src/main/assets/seed/scripts.json                          NEW (5 scripts)
app/src/main/java/gr/dimitris/app/
  core/data/Entities.kt (+ Script, ScriptLine, Speaker), Daos.kt (+ ScriptDao), AppDatabase.kt (v5)
  core/data/ScriptRepository.kt                                NEW
  core/seed/ScriptSeed.kt                                      NEW (manifest + importer)
  core/settings/Settings.kt                                    + scriptsSeedVersion
  caregiver/scripts/ScriptListScreen.kt, ScriptEditScreen.kt, ScriptEditViewModel.kt   NEW
  modules/scripts/ScriptsModule.kt, ScriptsViewModel.kt, ScriptsScreen.kt              NEW
  AppGraph.kt (+ scripts repo, modules += ScriptsModule), Nav.kt (+ routes), DimitrisApp.kt (+ seed), CaregiverHomeScreen.kt (+ entry)
app/src/test/.../core/data/FakeScriptDao.kt, ScriptRepositoryTest.kt
app/src/test/.../core/seed/ScriptSeedTest.kt
app/src/androidTest/.../core/data/MigrationTest.kt (+ 4→5)
```

---

### Task 1: Scripts in the database and a repository

**Files:**
- Modify: `core/data/Entities.kt`, `Daos.kt`, `AppDatabase.kt`
- Create: `core/data/ScriptRepository.kt`
- Test: `test/.../core/data/FakeScriptDao.kt`, `ScriptRepositoryTest.kt`; `androidTest/.../core/data/MigrationTest.kt` (+ case)

**Interfaces:**
- Produces: `enum class Speaker { OTHER, DIMITRIS }`; `@Entity("scripts") Script(id, title, createdAt, updatedAt, deleted)`; `@Entity("script_lines") ScriptLine(id, scriptId, position, speaker, itemId, createdAt, updatedAt, deleted)`; `ScriptDao` (`upsertScript`, `upsertLines`, `observeScripts(): Flow<List<Script>>`, `get(id)`, `linesFor(scriptId): List<ScriptLine>` ordered by position, `softDeleteScript(id, now)`, `softDeleteLinesOf(scriptId, now)`, `activeScripts(): List<Script>`); `data class LineDraft(speaker, text, recordingFile: File? = null, recordingMs: Long = 0)`; `data class ScriptWithLines(script, lines: List<Pair<ScriptLine, Item>>)`; `ScriptRepository(scripts, items: ItemRepository, clock)` with `observeAll()`, `load(id): ScriptWithLines?`, `save(id: String?, title, lines: List<LineDraft>): Script` (creates SCRIPT_LINE items, replaces old lines by soft-deleting them), `delete(id)`.

- [ ] **Step 1: Failing tests**

`test/.../core/data/FakeScriptDao.kt`:
```kotlin
package gr.dimitris.app.core.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

class FakeScriptDao : ScriptDao {
    val scripts = MutableStateFlow<Map<String, Script>>(emptyMap())
    val lines = mutableMapOf<String, ScriptLine>()
    override suspend fun upsertScript(script: Script) { scripts.value = scripts.value + (script.id to script) }
    override suspend fun upsertLines(lines: List<ScriptLine>) { lines.forEach { this.lines[it.id] = it } }
    override fun observeScripts(): Flow<List<Script>> = scripts.map { m -> m.values.filter { !it.deleted }.sortedBy { it.title } }
    override suspend fun activeScripts(): List<Script> = scripts.value.values.filter { !it.deleted }.sortedBy { it.title }
    override suspend fun get(id: String): Script? = scripts.value[id]
    override suspend fun linesFor(scriptId: String): List<ScriptLine> = lines.values.filter { it.scriptId == scriptId && !it.deleted }.sortedBy { it.position }
    override suspend fun softDeleteScript(id: String, now: Long) { scripts.value[id]?.let { upsertScript(it.copy(deleted = true, updatedAt = now)) } }
    override suspend fun softDeleteLinesOf(scriptId: String, now: Long) { lines.values.filter { it.scriptId == scriptId }.forEach { lines[it.id] = it.copy(deleted = true, updatedAt = now) } }
}
```

`test/.../core/data/ScriptRepositoryTest.kt`:
```kotlin
package gr.dimitris.app.core.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class ScriptRepositoryTest {
    private val itemDao = FakeItemDao()
    private val recDao = FakeRecordingDao()
    private val scriptDao = FakeScriptDao()
    private var clock = 5_000L
    private val items = ItemRepository(itemDao, recDao) { clock }
    private val repo = ScriptRepository(scriptDao, items) { clock }

    private val coffee = listOf(
        LineDraft(Speaker.OTHER, "Καλημέρα! Τι θα πάρετε;"),
        LineDraft(Speaker.DIMITRIS, "Έναν καφέ, παρακαλώ."),
        LineDraft(Speaker.OTHER, "Ζάχαρη;", recordingFile = File("/tmp/z.m4a"), recordingMs = 700),
        LineDraft(Speaker.DIMITRIS, "Μέτριο."),
    )

    @Test fun `save creates script lines and items in order`() = runTest {
        val s = repo.save(null, "Καφές", coffee)
        val loaded = repo.load(s.id)!!
        assertEquals("Καφές", loaded.script.title)
        assertEquals(listOf(0, 1, 2, 3), loaded.lines.map { it.first.position })
        assertEquals(listOf(Speaker.OTHER, Speaker.DIMITRIS, Speaker.OTHER, Speaker.DIMITRIS), loaded.lines.map { it.first.speaker })
        assertEquals("Έναν καφέ, παρακαλώ.", loaded.lines[1].second.text)
        assertEquals(ItemKind.SCRIPT_LINE, loaded.lines[1].second.kind)
        assertEquals("έ", loaded.lines[1].second.firstSound)
        assertEquals("/tmp/z.m4a", items.modelRecording(loaded.lines[2].second)?.path)
    }

    @Test fun `saving again replaces lines and keeps the script id`() = runTest {
        val s = repo.save(null, "Καφές", coffee)
        val again = repo.save(s.id, "Καφές το πρωί", coffee.take(2))
        assertEquals(s.id, again.id)
        assertEquals("Καφές το πρωί", again.title)
        assertEquals(2, repo.load(s.id)!!.lines.size)
        assertEquals(2, scriptDao.lines.values.count { !it.deleted })
    }

    @Test fun `delete is soft and hides the script`() = runTest {
        val s = repo.save(null, "Ταξί", coffee.take(1))
        repo.delete(s.id)
        assertEquals(emptyList<Script>(), repo.observeAll().first())
        assertNull(repo.load(s.id))
    }
}
```

`MigrationTest.kt` add:
```kotlin
    @Test fun migrate4To5CreatesScriptTables() {
        val name = "migration-test-5.db"
        helper.createDatabase(name, 4).close()
        helper.runMigrationsAndValidate(name, 5, true).use { db ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('scripts','script_lines')").use { c -> assert(c.count == 2) }
        }
    }
```

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.core.data.ScriptRepositoryTest'` — Expected: compilation error.

- [ ] **Step 2: Implement**

`Entities.kt` additions:
```kotlin
enum class Speaker { OTHER, DIMITRIS }

/** A rehearsed dialogue. Lines live in script_lines and point at SCRIPT_LINE items. */
@Entity(tableName = "scripts")
data class Script(
    @PrimaryKey val id: String = newId(),
    val title: String,
    val source: Source = Source.CAREGIVER,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
)

@Entity(tableName = "script_lines", indices = [Index("scriptId")])
data class ScriptLine(
    @PrimaryKey val id: String = newId(),
    val scriptId: String,
    val position: Int,
    val speaker: Speaker,
    val itemId: String,
    val createdAt: Long = now(),
    val updatedAt: Long = now(),
    val deleted: Boolean = false,
)
```

`Daos.kt` addition:
```kotlin
@Dao
interface ScriptDao {
    @Upsert suspend fun upsertScript(script: Script)
    @Upsert suspend fun upsertLines(lines: List<ScriptLine>)
    @Query("SELECT * FROM scripts WHERE deleted = 0 ORDER BY title") fun observeScripts(): Flow<List<Script>>
    @Query("SELECT * FROM scripts WHERE deleted = 0 ORDER BY title") suspend fun activeScripts(): List<Script>
    @Query("SELECT * FROM scripts WHERE id = :id") suspend fun get(id: String): Script?
    @Query("SELECT * FROM script_lines WHERE scriptId = :scriptId AND deleted = 0 ORDER BY position") suspend fun linesFor(scriptId: String): List<ScriptLine>
    @Query("UPDATE scripts SET deleted = 1, updatedAt = :now WHERE id = :id") suspend fun softDeleteScript(id: String, now: Long)
    @Query("UPDATE script_lines SET deleted = 1, updatedAt = :now WHERE scriptId = :scriptId AND deleted = 0") suspend fun softDeleteLinesOf(scriptId: String, now: Long)
}
```
`AppDatabase.kt`: add `Script::class, ScriptLine::class` to entities, `version = 5`, `AutoMigration(from = 4, to = 5)`, `abstract fun scripts(): ScriptDao`.

`core/data/ScriptRepository.kt`:
```kotlin
package gr.dimitris.app.core.data

import kotlinx.coroutines.flow.Flow
import java.io.File

data class LineDraft(val speaker: Speaker, val text: String, val recordingFile: File? = null, val recordingMs: Long = 0)
data class ScriptWithLines(val script: Script, val lines: List<Pair<ScriptLine, Item>>)

class ScriptRepository(private val scripts: ScriptDao, private val items: ItemRepository, private val clock: () -> Long = ::now) {
    fun observeAll(): Flow<List<Script>> = scripts.observeScripts()

    suspend fun load(id: String): ScriptWithLines? {
        val script = scripts.get(id)?.takeIf { !it.deleted } ?: return null
        val lines = scripts.linesFor(id).mapNotNull { line -> items.get(line.itemId)?.let { line to it } }
        return ScriptWithLines(script, lines)
    }

    /** Creates or replaces a script. Old lines are soft-deleted; their items stay (history keeps pointing at them). */
    suspend fun save(id: String?, title: String, lines: List<LineDraft>, source: Source = Source.CAREGIVER): Script {
        val t = clock()
        val existing = id?.let { scripts.get(it) }
        val script = (existing ?: Script(title = title.trim(), source = source, createdAt = t)).copy(title = title.trim(), updatedAt = t)
        scripts.upsertScript(script)
        if (existing != null) scripts.softDeleteLinesOf(script.id, t)
        val rows = lines.filter { it.text.isNotBlank() }.mapIndexed { i, d ->
            val item = items.save(Item(text = d.text, kind = ItemKind.SCRIPT_LINE, category = Category.CUSTOM, source = source))
            if (d.recordingFile != null) items.addRecording(item.id, d.recordingFile, d.recordingMs, Who.CAREGIVER)
            ScriptLine(scriptId = script.id, position = i, speaker = d.speaker, itemId = item.id, createdAt = t, updatedAt = t)
        }
        scripts.upsertLines(rows)
        return script
    }

    suspend fun delete(id: String) { val t = clock(); scripts.softDeleteLinesOf(id, t); scripts.softDeleteScript(id, t) }
}
```
(SCRIPT_LINE items are hidden from the talk board and word coach because those filter on WORD/PHRASE kinds; `ItemListScreen` shows all active items, so add a filter there in Task 3: `all.filter { it.kind != ItemKind.SCRIPT_LINE }`.)

- [ ] **Step 3: Tests, schema, commit**

Run: `./gradlew -q testDebugUnitTest && ANDROID_SERIAL=emulator-5554 ./gradlew -q connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=gr.dimitris.app.core.data` — pass; `app/schemas/.../5.json` exists.
```bash
git add app/schemas app/src/main app/src/test app/src/androidTest
git commit -m "feat(phase5): scripts and script lines (db v5) with repository

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Five seed scripts

**Files:**
- Create: `app/src/main/assets/seed/scripts.json`, `core/seed/ScriptSeed.kt`
- Modify: `core/settings/Settings.kt` (+ `scriptsSeedVersion`), `DimitrisApp.kt` (run importer), `AppGraph.kt` (+ `scripts` repository)
- Test: `test/.../core/seed/ScriptSeedTest.kt`, `SettingsTest.kt` (+ case)

**Interfaces:**
- Produces: `AppGraph.scripts: ScriptRepository`; `Settings.scriptsSeedVersion` + setter; `ScriptSeedManifest(version, scripts: List<SeedScript>)`, `SeedScript(title, lines: List<SeedLine>)`, `SeedLine(speaker: String, text: String)`, `ScriptSeedManifest.parse(json)`, `ScriptSeedImporter(graph).importIfNeeded()`.

- [ ] **Step 1: The asset**

`app/src/main/assets/seed/scripts.json`:
```json
{"version": 1, "scripts": [
 {"title": "Στην καφετέρια", "lines": [
  {"speaker": "OTHER", "text": "Καλημέρα! Τι θα πάρετε;"},
  {"speaker": "DIMITRIS", "text": "Έναν καφέ, παρακαλώ."},
  {"speaker": "OTHER", "text": "Τι καφέ θέλετε;"},
  {"speaker": "DIMITRIS", "text": "Φραπέ μέτριο με γάλα."},
  {"speaker": "OTHER", "text": "Κάτι άλλο;"},
  {"speaker": "DIMITRIS", "text": "Όχι, ευχαριστώ."},
  {"speaker": "OTHER", "text": "Τρία ευρώ."},
  {"speaker": "DIMITRIS", "text": "Ορίστε."}
 ]},
 {"title": "Στο ταξί", "lines": [
  {"speaker": "OTHER", "text": "Καλησπέρα, πού πάμε;"},
  {"speaker": "DIMITRIS", "text": "Στο σπίτι μου, παρακαλώ."},
  {"speaker": "OTHER", "text": "Ποια διεύθυνση;"},
  {"speaker": "DIMITRIS", "text": "Θα σας δείξω στο κινητό."},
  {"speaker": "OTHER", "text": "Εντάξει. Φτάσαμε."},
  {"speaker": "DIMITRIS", "text": "Ευχαριστώ πολύ. Καλό βράδυ."}
 ]},
 {"title": "Τηλέφωνο στη μαμά", "lines": [
  {"speaker": "OTHER", "text": "Ναι; Δημήτρη μου;"},
  {"speaker": "DIMITRIS", "text": "Γεια σου μαμά. Τι κάνεις;"},
  {"speaker": "OTHER", "text": "Καλά είμαι. Εσύ;"},
  {"speaker": "DIMITRIS", "text": "Καλά. Πήγα γυμναστήριο."},
  {"speaker": "OTHER", "text": "Μπράβο! Θα έρθεις το βράδυ;"},
  {"speaker": "DIMITRIS", "text": "Ναι, θα έρθω."},
  {"speaker": "OTHER", "text": "Φιλιά, σε περιμένω."},
  {"speaker": "DIMITRIS", "text": "Φιλιά. Τα λέμε."}
 ]},
 {"title": "Στον γιατρό", "lines": [
  {"speaker": "OTHER", "text": "Καλημέρα. Έχετε ραντεβού;"},
  {"speaker": "DIMITRIS", "text": "Ναι. Δημήτρης, στις δέκα."},
  {"speaker": "OTHER", "text": "Πώς αισθάνεστε σήμερα;"},
  {"speaker": "DIMITRIS", "text": "Καλά. Λίγο κουρασμένος."},
  {"speaker": "OTHER", "text": "Πονάτε κάπου;"},
  {"speaker": "DIMITRIS", "text": "Όχι, δεν πονάω."},
  {"speaker": "OTHER", "text": "Ωραία. Περάστε μέσα."},
  {"speaker": "DIMITRIS", "text": "Ευχαριστώ."}
 ]},
 {"title": "Με έναν φίλο", "lines": [
  {"speaker": "OTHER", "text": "Γεια σου Δημήτρη! Τι κάνεις;"},
  {"speaker": "DIMITRIS", "text": "Γεια! Καλά, εσύ;"},
  {"speaker": "OTHER", "text": "Μια χαρά. Πάμε για καφέ;"},
  {"speaker": "DIMITRIS", "text": "Ναι, πάμε."},
  {"speaker": "OTHER", "text": "Πού θέλεις;"},
  {"speaker": "DIMITRIS", "text": "Στη θάλασσα."},
  {"speaker": "OTHER", "text": "Τέλεια, πάμε."},
  {"speaker": "DIMITRIS", "text": "Πάμε!"}
 ]}
]}
```

- [ ] **Step 2: Failing tests**

`test/.../core/seed/ScriptSeedTest.kt`:
```kotlin
package gr.dimitris.app.core.seed

import org.junit.Assert.assertEquals
import org.junit.Test

class ScriptSeedTest {
    @Test fun `parses scripts and lines`() {
        val m = ScriptSeedManifest.parse("""{"version":2,"scripts":[{"title":"Α","lines":[{"speaker":"OTHER","text":"Γεια"},{"speaker":"DIMITRIS","text":"Γεια σου"}]}]}""")
        assertEquals(2, m.version)
        assertEquals("Α", m.scripts.single().title)
        assertEquals(listOf("OTHER", "DIMITRIS"), m.scripts.single().lines.map { it.speaker })
    }
}
```
`SettingsTest.kt` add: `scriptsSeedVersion` defaults to 0 and persists 2.

- [ ] **Step 3: Implement**

`Settings.kt`: `scriptsSeedVersion: Flow<Int>` (key `scripts_seed_version`, default 0) + `setScriptsSeedVersion`.
`AppGraph.kt`: `val scripts: ScriptRepository get() = ScriptRepository(db.scripts(), items)`.

`core/seed/ScriptSeed.kt`:
```kotlin
package gr.dimitris.app.core.seed

import com.google.gson.Gson
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.LineDraft
import gr.dimitris.app.core.data.Source
import gr.dimitris.app.core.data.Speaker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

data class SeedLine(val speaker: String, val text: String)
data class SeedScript(val title: String, val lines: List<SeedLine>)
data class ScriptSeedManifest(val version: Int, val scripts: List<SeedScript>) {
    companion object { fun parse(json: String): ScriptSeedManifest = Gson().fromJson(json, ScriptSeedManifest::class.java) }
}

/** Loads the bundled dialogues once per manifest version; never touches caregiver-made scripts. */
class ScriptSeedImporter(private val graph: AppGraph) {
    suspend fun importIfNeeded() = withContext(Dispatchers.IO) {
        val manifest = runCatching { graph.app.assets.open("seed/scripts.json").bufferedReader().use { ScriptSeedManifest.parse(it.readText()) } }
            .getOrElse { graph.errors.record("script seed manifest", it); return@withContext }
        if (graph.settings.scriptsSeedVersion.first() >= manifest.version) return@withContext
        val existing = graph.db.scripts().activeScripts().filter { it.source == Source.SEED }.map { it.title }.toSet()
        for (s in manifest.scripts) {
            if (s.title in existing) continue
            graph.scripts.save(null, s.title, s.lines.map { LineDraft(runCatching { Speaker.valueOf(it.speaker) }.getOrDefault(Speaker.OTHER), it.text) }, source = Source.SEED)
        }
        graph.settings.setScriptsSeedVersion(manifest.version)
    }
}
```
`DimitrisApp.onCreate`: after the item seed, `graph.scope.launch { ScriptSeedImporter(graph).importIfNeeded() }` (run it after the item importer in the same coroutine).

- [ ] **Step 4: Tests, install, commit**

`./gradlew -q testDebugUnitTest && ANDROID_SERIAL=emulator-5554 ./gradlew -q installDebug` — a fresh install (`adb shell pm clear gr.dimitris.app`) imports five scripts.
```bash
git add app/src/main app/src/test
git commit -m "feat(phase5): five seed dialogues imported on first run

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Caregiver script list and editor

**Files:**
- Create: `caregiver/scripts/ScriptListScreen.kt`, `ScriptEditViewModel.kt`, `ScriptEditScreen.kt`
- Modify: `Nav.kt` (routes `caregiver/scripts`, `caregiver/scripts/{scriptId}`), `CaregiverHomeScreen.kt` (entry "Διάλογοι"), `caregiver/content/ItemListScreen.kt` (hide SCRIPT_LINE items)

**Interfaces:**
- Produces: `Routes.SCRIPTS`, `Routes.SCRIPT_EDIT`, `Routes.scriptEdit(id)`, `ScriptListScreen(onBack, onEdit: (String?) -> Unit)`, `ScriptEditScreen(scriptId, onClose)`, `ScriptEditViewModel(graph, scriptId)` with state `title`, `lines: List<EditLine(speaker, text, recordingPath?, newRecording?)>`, `recordingIndex: Int?`, actions `setTitle`, `addLine`, `setLineText(i, t)`, `toggleSpeaker(i)`, `moveUp(i)`, `moveDown(i)`, `removeLine(i)`, `toggleRecording(i)`, `playLine(i)`, `save(onSaved)`, `delete(onDeleted)`.

- [ ] **Step 1: ViewModel** — same recording pattern as `ItemEditViewModel` (start/stop `graph.recorder`, keep the `Recorded` until save, delete unsaved files in `onCleared`). `save` maps lines to `LineDraft(speaker, text, newRecording?.file ?: null, newRecording?.durationMs ?: 0)`; existing recordings are kept because `ScriptRepository.save` only attaches a recording when a file is given — so for unchanged lines pass the existing recording's `File(path)` and duration (load them in `init` via `graph.items.modelRecording(item)`), which re-attaches the same file to the new item.

- [ ] **Step 2: Screens** — `ScriptListScreen`: `DimitrisScreen(title = "Διάλογοι", onBack, bottom = BigButton("Νέος διάλογος"))`, rows of titles (72dp) → edit. `ScriptEditScreen`: title field; for each line a card with a `FilterChip` toggle "Άλλος"/"Δημήτρης", an `OutlinedTextField`, and for OTHER lines a record/stop `QuietButton` + "Άκου"; up/down/delete `IconButton`s (72dp); "Προσθήκη γραμμής"; bottom "Αποθήκευση"; delete with confirm dialog. All strings Greek.

- [ ] **Step 3: Wire** routes + entry `CaregiverEntry("Διάλογοι", Icons.Rounded.Chat, Routes.SCRIPTS)`; in `ItemListScreen` filter `all.filter { it.kind != ItemKind.SCRIPT_LINE }` before search.

- [ ] **Step 4: Build, install, edit a seed script (change a line, record the other side), commit** `feat(phase5): caregiver dialogue editor`.

---

### Task 4: Scripts module — the dialogue screen

**Files:**
- Create: `modules/scripts/ScriptsModule.kt`, `ScriptsViewModel.kt`, `ScriptsScreen.kt`
- Modify: `AppGraph.kt` (`modules += ScriptsModule`)

**Interfaces:**
- Produces: `ScriptsModule` (id SCRIPTS, title "Διάλογοι", icon `Icons.Rounded.Chat`); `planFor`: due scripts via `Scheduler.due(SCRIPTS)` mapped to their first line's item (the module needs items only for counting), else the least-recently-practised script; returns the lines' items of ONE script; `practiceFor` same with a random script; `Screen` reads the script id from `items.first()` via a companion map — simpler: `ScriptsModule.planFor` stores the chosen script id in `ScriptsModule.nextScriptId` (a `@Volatile var`) and `Screen` reads it. `ScriptsScreen(scriptId, sessionId, onDone, onLeave)`, `ScriptsViewModel(graph, scriptId, sessionId)`.

- [ ] **Step 1: ViewModel state and flow**
`ScriptsState(title, lines: List<Pair<ScriptLine, Item>>, index, phase: Phase, ladderLevel, cueText, showsWord, canHint, confirmed, done, worstCue, skipped)`, `Phase { OTHER_SPEAKING, WAITING_FOR_DIMITRIS, FINISHED }`.
- On load: `graph.scripts.load(scriptId)`; start at index 0; if the line is OTHER: `graph.speaker.speak(item)` then advance automatically to the next line (an OTHER line never waits on a timer, it waits for the utterance to end); if DIMITRIS: build `CueLadder(item)`, phase WAITING.
- `hint()` / `repeatCue()` exactly like the word coach (levels 1–2 `speakText(cue)`, 3–4 `speaker.speak(item)`).
- `confirm()`: outcome via ladder; insert `Attempt(itemId = item.id, module = SCRIPTS, sessionId, cueLevel = level, detail = {"scriptId":..,"position":n})`; `worstCue = max`; success feedback; advance.
- `skip()`: SKIPPED attempt, `skipped = true`, nudge, advance.
- After the last line: `Scheduler.record(scriptId, SCRIPTS, outcome per Global Constraints, worstCue)`; phase FINISHED; TTS "Μπράβο! Τέλος διαλόγου."; screen shows "Εντάξει" → `onDone`.

- [ ] **Step 2: Screen** — a chat-like column: spoken lines appear as bubbles (OTHER left in surfaceVariant, DIMITRIS right in primary), the current DIMITRIS line shows the cue text large under the last bubble; bottom: `Row { BigButton("Βοήθεια", Secondary, enabled canHint), BigButton("Το είπα!", Success) }` + `QuietButton("Παράλειψη")`; while OTHER speaks, the bottom shows a disabled `QuietButton("Ακούω...")`. Title `"${title}"` with `onBack = onDone`.

- [ ] **Step 3: Register in `AppGraph.modules`; build; instrumented smoke via the Today grid ("Διάλογοι" card opens a dialogue and shows "Το είπα!"); commit** `feat(phase5): dialogue practice module`.

---

### Task 5: Phase 5 verification

- [ ] Full suites green; on `R5CWC2C1KSJ`: run "Στην καφετέρια" end to end; record Chris's voice for its OTHER lines in the editor and run it again; Σφάλματα empty. Append notes; commit `docs(phase5): verification notes`.
