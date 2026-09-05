# Dimitris' App — Phase 4 (Sing Then Say) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Melodic Intonation Therapy on the phone: daily phrases set to a two-note melody, tapped with the left hand, sung along with a caregiver's sung model or a synthesized melody, with the backing fading over stages until he simply speaks the phrase.

**Architecture:** Pure Kotlin `Melody` (syllables → HIGH/LOW notes from stress), `Pcm` (sine tones with envelope, JVM-testable), an Android `ToneSynth` over `AudioTrack`, DB v4 adding a `style` (SPOKEN/SUNG) to recordings so caregivers can record a sung model, and the `SingSayModule` with a five-stage screen. Attempts carry the stage reached in `detail`; the scheduler maps stage to cue level so the Leitner boxes still drive what comes back.

**Tech Stack:** as before; `android.media.AudioTrack` (static mode, 16-bit PCM mono 44.1 kHz).

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§6 melody engine, §7 phase 4)

## Global Constraints

- Greek-only UI; 72dp minimum; primary actions at the bottom; no timers (the melody plays at its own tempo but nothing fails him for being slow); success = icon + sound + haptic.
- Two pitches only: LOW = G3 (196.0 Hz), HIGH = B3 (246.9 Hz). One note per syllable, 550 ms each, 80 ms gap. Stressed syllable HIGH, others LOW; monosyllables LOW.
- Stages exactly: 1 listen; 2 sing together (full backing); 3 sing with fading backing (gain 0.6 → 0.3 → 0.0 over three repetitions); 4 speak with taps only; 5 speak. Progress = stage reached, stored as `{"stage":N}` in `Attempt.detail`, module SINGSAY, cueLevel = 5 − stage (so stage 5 → 0 CORRECT, stage 4 → 1 CORRECT, stage 3 → 2 CORRECT, stages 1–2 → 3 ASSISTED), SKIPPED when skipped.
- The model is the caregiver's SUNG recording when it exists, else TTS of the phrase followed by the synthesized melody with syllables lighting up.
- DB change by auto-migration only, schema committed. Build from `/mnt/nvme2TB/tomas/.claude/worktrees/phase0`; instrumented with `ANDROID_SERIAL=emulator-5554`. Commits `feat(phase4): ...` ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

- **Module contract (after the phase-2 fix wave):** `Module.Screen(items, sessionId, onDone, onLeave)` — `onDone` = all exercises finished, `onLeave` = the user pressed back (the screen's `onBack` calls the module's own cleanup then `onLeave`). Attempt/schedule writes go on `graph.scope` (they must survive the screen); the last write is joined before `done` is published. Every module screen has `DisposableEffect(Unit) { onDispose { graph.voice.quiet() } }` semantics through the session/practice hosts. Sessions cap at 15 items across modules (`SessionBudget.allowance(n)`), so `planFor` lists may be truncated.

---

## File structure

```
app/src/main/java/gr/dimitris/app/
  core/audio/Pcm.kt, ToneSynth.kt                          NEW
  core/data/Entities.kt (+ Recording.style), Daos.kt (+ latestFor with style), AppDatabase.kt (v4)
  core/data/ItemRepository.kt (+ style param on addRecording, sungRecording)
  modules/singsay/Melody.kt, SingStage.kt, SingSayModule.kt, SingSayViewModel.kt, SingSayScreen.kt   NEW
  caregiver/content/ItemEditViewModel.kt, ItemEditScreen.kt  + "Τραγούδησέ το" recording
  AppGraph.kt                                              + synth, modules += SingSayModule
app/src/test/.../core/audio/PcmTest.kt
app/src/test/.../modules/singsay/MelodyTest.kt, SingStageTest.kt
app/src/androidTest/.../core/data/MigrationTest.kt (+ 3→4)
```

---

### Task 1: Melody from a phrase (pure)

**Files:**
- Create: `modules/singsay/Melody.kt`
- Test: `modules/singsay/MelodyTest.kt`

**Interfaces:**
- Consumes: `Syllabifier.syllables`, `Greek.stripAccents`.
- Produces: `enum class Pitch(val hz: Double) { LOW(196.0), HIGH(246.94) }`, `data class Note(val syllable: String, val pitch: Pitch, val wordIndex: Int)`, `object Melody { const val NOTE_MS = 550; const val GAP_MS = 80; fun forPhrase(text: String): List<Note> }`.

- [ ] **Step 1: Failing tests**

`modules/singsay/MelodyTest.kt`:
```kotlin
package gr.dimitris.app.modules.singsay

import org.junit.Assert.assertEquals
import org.junit.Test

class MelodyTest {
    private fun pitches(text: String) = Melody.forPhrase(text).map { it.pitch }
    private fun syllables(text: String) = Melody.forPhrase(text).map { it.syllable }

    @Test fun `one note per syllable across words`() = assertEquals(listOf("θέ", "λω", "κα", "φέ"), syllables("θέλω καφέ"))
    @Test fun `stressed syllable is high, others low`() = assertEquals(listOf(Pitch.HIGH, Pitch.LOW, Pitch.LOW, Pitch.HIGH), pitches("θέλω καφέ"))
    @Test fun `monosyllables are low`() = assertEquals(listOf(Pitch.LOW, Pitch.LOW, Pitch.HIGH), pitches("να πά με"))
    @Test fun `word index follows the words`() = assertEquals(listOf(0, 0, 1), Melody.forPhrase("πάμε σπίτι").map { it.wordIndex }.take(3))
    @Test fun `punctuation and extra spaces are ignored`() = assertEquals(listOf("κα", "λη", "μέ", "ρα"), syllables("  Καλημέρα!  "))
    @Test fun `unaccented capitalised word still gets one high note on its last syllable`() = assertEquals(listOf(Pitch.LOW, Pitch.HIGH), pitches("ΝΕΡΟ"))
    @Test fun `empty gives empty`() = assertEquals(emptyList<Note>(), Melody.forPhrase("  "))
}
```

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.modules.singsay.MelodyTest'` — Expected: compilation error.

- [ ] **Step 2: Implement**

`modules/singsay/Melody.kt`:
```kotlin
package gr.dimitris.app.modules.singsay

import gr.dimitris.app.core.greek.Greek
import gr.dimitris.app.core.greek.Syllabifier
import java.text.Normalizer

enum class Pitch(val hz: Double) { LOW(196.0), HIGH(246.94) }

data class Note(val syllable: String, val pitch: Pitch, val wordIndex: Int)

/**
 * Two-note melody for Melodic Intonation Therapy: the stressed syllable of each word is HIGH,
 * everything else LOW. Monosyllables are LOW. A multi-syllable word with no written accent
 * (all capitals) gets HIGH on its last syllable so every word still has a peak.
 */
object Melody {
    const val NOTE_MS = 550
    const val GAP_MS = 80

    fun forPhrase(text: String): List<Note> {
        val words = text.trim().split(Regex("\\s+")).map { it.trim { c -> !c.isLetter() } }.filter { it.isNotEmpty() }
        return words.flatMapIndexed { wi, word ->
            val syl = Syllabifier.syllables(word) ?: return@flatMapIndexed emptyList()
            if (syl.size == 1) return@flatMapIndexed listOf(Note(syl[0], Pitch.LOW, wi))
            val stressed = syl.indexOfFirst { hasTonos(it) }.let { if (it == -1) syl.lastIndex else it }
            syl.mapIndexed { i, s -> Note(s, if (i == stressed) Pitch.HIGH else Pitch.LOW, wi) }
        }
    }

    /** True when the syllable carries a written accent (tonos, U+0301 in NFD). */
    private fun hasTonos(s: String): Boolean = Normalizer.normalize(s, Normalizer.Form.NFD).contains('́')
}
```
(`Greek` import is unused if `hasTonos` is self-contained; drop the import in that case.)

- [ ] **Step 3: Run tests, commit**

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.modules.singsay.*'` — Expected: 7 pass.
```bash
git add app/src/main/java/gr/dimitris/app/modules/singsay app/src/test
git commit -m "feat(phase4): two-note melody from Greek stress

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: PCM tone generation and the Android synth

**Files:**
- Create: `core/audio/Pcm.kt`, `core/audio/ToneSynth.kt`
- Modify: `AppGraph.kt` (+ `synth`)
- Test: `core/audio/PcmTest.kt`

**Interfaces:**
- Produces: `object Pcm { const val SAMPLE_RATE = 44_100; fun tone(hz: Double, ms: Int, gain: Float): ShortArray; fun silence(ms: Int): ShortArray; fun concat(parts: List<ShortArray>): ShortArray }`; `class ToneSynth { suspend fun play(notes: List<Pitch>, noteMs: Int, gapMs: Int, gain: Float, onNote: (Int) -> Unit = {}); fun stop() }`; `AppGraph.synth`.

- [ ] **Step 1: Failing tests**

`core/audio/PcmTest.kt`:
```kotlin
package gr.dimitris.app.core.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PcmTest {
    @Test fun `length matches duration`() = assertEquals(44_100 / 2, Pcm.tone(440.0, 500, 1f).size)
    @Test fun `silence is zeros`() = assertTrue(Pcm.silence(10).all { it == 0.toShort() })
    @Test fun `starts and ends near zero because of the envelope`() {
        val t = Pcm.tone(440.0, 200, 1f)
        assertTrue(abs(t[0].toInt()) < 200 && abs(t[t.lastIndex].toInt()) < 200)
    }
    @Test fun `peak scales with gain`() {
        val loud = Pcm.tone(440.0, 200, 1f).maxOf { abs(it.toInt()) }
        val soft = Pcm.tone(440.0, 200, 0.5f).maxOf { abs(it.toInt()) }
        assertTrue(loud > 20_000 && soft in (loud / 2 - 600)..(loud / 2 + 600))
    }
    @Test fun `zero gain is silent`() = assertTrue(Pcm.tone(440.0, 50, 0f).all { it == 0.toShort() })
    @Test fun `concat joins in order`() {
        val a = shortArrayOf(1, 2); val b = shortArrayOf(3)
        assertEquals(listOf<Short>(1, 2, 3), Pcm.concat(listOf(a, b)).toList())
    }
}
```

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.core.audio.PcmTest'` — Expected: compilation error.

- [ ] **Step 2: Implement**

`core/audio/Pcm.kt`:
```kotlin
package gr.dimitris.app.core.audio

import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/** 16-bit mono PCM helpers. Pure Kotlin so the shapes can be unit-tested. */
object Pcm {
    const val SAMPLE_RATE = 44_100
    private const val ATTACK_MS = 20
    private const val RELEASE_MS = 60

    fun tone(hz: Double, ms: Int, gain: Float): ShortArray {
        val n = SAMPLE_RATE * ms / 1000
        val attack = SAMPLE_RATE * ATTACK_MS / 1000
        val release = SAMPLE_RATE * RELEASE_MS / 1000
        val amp = 32_000.0 * gain.coerceIn(0f, 1f)
        return ShortArray(n) { i ->
            val env = min(1.0, min(i.toDouble() / attack, (n - 1 - i).toDouble() / release)).coerceAtLeast(0.0)
            (sin(2 * PI * hz * i / SAMPLE_RATE) * amp * env).toInt().toShort()
        }
    }

    fun silence(ms: Int): ShortArray = ShortArray(SAMPLE_RATE * ms / 1000)

    fun concat(parts: List<ShortArray>): ShortArray {
        val out = ShortArray(parts.sumOf { it.size })
        var pos = 0
        parts.forEach { it.copyInto(out, pos); pos += it.size }
        return out
    }
}
```

`core/audio/ToneSynth.kt`:
```kotlin
package gr.dimitris.app.core.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import gr.dimitris.app.modules.singsay.Melody
import gr.dimitris.app.modules.singsay.Pitch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Plays a sequence of two-pitch notes and calls [onNote] as each one starts, so the screen can light the syllable. */
class ToneSynth {
    @Volatile private var track: AudioTrack? = null

    suspend fun play(notes: List<Pitch>, noteMs: Int = Melody.NOTE_MS, gapMs: Int = Melody.GAP_MS, gain: Float = 1f, onNote: (Int) -> Unit = {}) {
        if (notes.isEmpty()) return
        stop()
        val pcm = Pcm.concat(notes.flatMap { listOf(Pcm.tone(it.hz, noteMs, gain), Pcm.silence(gapMs)) })
        val t = withContext(Dispatchers.IO) {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(Pcm.SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(pcm.size * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build()
                .also { it.write(pcm, 0, pcm.size) }
        }
        track = t
        try {
            t.play()
            for (i in notes.indices) {
                if (track !== t) return
                onNote(i)
                delay((noteMs + gapMs).toLong())
            }
        } finally {
            if (track === t) { runCatching { t.stop() }; t.release(); track = null }
        }
    }

    fun stop() {
        track?.let { runCatching { it.stop() }; it.release() }
        track = null
    }
}
```
In `AppGraph.kt` add `val synth = ToneSynth()` (import `gr.dimitris.app.core.audio.ToneSynth`).

- [ ] **Step 3: Tests, build, commit**

Run: `./gradlew -q testDebugUnitTest assembleDebug` — Expected: pass.
```bash
git add app/src/main app/src/test
git commit -m "feat(phase4): PCM tone generation and AudioTrack synth

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Sung recordings (DB v4) and the caregiver "sing it" button

**Files:**
- Modify: `core/data/Entities.kt` (+ `RecordingStyle`, `Recording.style`), `Daos.kt` (`latestFor` gains `style`), `AppDatabase.kt` (v4), `core/data/ItemRepository.kt` (`addRecording(..., style)`, `sungRecording(item)`), `caregiver/content/ItemEditViewModel.kt`, `ItemEditScreen.kt`
- Test: `androidTest/.../core/data/MigrationTest.kt` (+ 3→4), `test/.../core/data/FakeRecordingDao.kt` (signature), `ItemRepositoryTest.kt` (+ case)

**Interfaces:**
- Produces: `enum class RecordingStyle { SPOKEN, SUNG }`, `Recording.style` (default SPOKEN, `@ColumnInfo(defaultValue = "SPOKEN")`), `RecordingDao.latestFor(itemId, who, style)`, `ItemRepository.addRecording(itemId, file, durationMs, who, style = SPOKEN)`, `ItemRepository.sungRecording(item): Recording?`, `ItemEditState.newSungRecording / savedSungPath / isRecordingSung`, `ItemEditViewModel.toggleSungRecording()`, `playSung()`.

- [ ] **Step 1: Failing tests**

`MigrationTest.kt` add:
```kotlin
    @Test fun migrate3To4AddsRecordingStyleDefaultSpoken() {
        val name = "migration-test-4.db"
        helper.createDatabase(name, 3).use { db ->
            db.execSQL("INSERT INTO recordings (id, itemId, path, who, durationMs, recordedAt, createdAt, updatedAt, deleted) VALUES ('r', 'a', '/x.m4a', 'CAREGIVER', 500, 1, 1, 1, 0)")
        }
        helper.runMigrationsAndValidate(name, 4, true).use { db ->
            db.query("SELECT style FROM recordings WHERE id = 'r'").use { c -> c.moveToFirst(); assert(c.getString(0) == "SPOKEN") }
        }
    }
```
`ItemRepositoryTest.kt` add:
```kotlin
    @Test fun `sung recordings are kept apart from spoken ones`() = runTest {
        val item = repo.save(Item(text = "θέλω καφέ"))
        val spoken = repo.addRecording(item.id, File("/tmp/s.m4a"), 900, Who.CAREGIVER)
        val sung = repo.addRecording(item.id, File("/tmp/g.m4a"), 1800, Who.CAREGIVER, RecordingStyle.SUNG)
        assertEquals(spoken.id, repo.get(item.id)?.modelRecordingId)
        assertEquals(sung, repo.sungRecording(repo.get(item.id)!!))
        assertEquals(spoken, repo.modelRecording(repo.get(item.id)!!))
    }
```
Update `FakeRecordingDao.latestFor` to the new signature `(itemId, who, style)` filtering on all three.

- [ ] **Step 2: Implement**

`Entities.kt`: add `enum class RecordingStyle { SPOKEN, SUNG }` next to the other enums, and to `Recording` after `who`: `@ColumnInfo(defaultValue = "SPOKEN") val style: RecordingStyle = RecordingStyle.SPOKEN,`.
`Daos.kt`, `RecordingDao.latestFor`: `@Query("SELECT * FROM recordings WHERE itemId = :itemId AND who = :who AND style = :style AND deleted = 0 ORDER BY recordedAt DESC LIMIT 1") suspend fun latestFor(itemId: String, who: Who, style: RecordingStyle): Recording?`.
`AppDatabase.kt`: `version = 4`, add `AutoMigration(from = 3, to = 4)`.
`ItemRepository.kt`:
```kotlin
    suspend fun addRecording(itemId: String, file: File, durationMs: Long, who: Who, style: RecordingStyle = RecordingStyle.SPOKEN): Recording {
        val recording = Recording(itemId = itemId, path = file.absolutePath, who = who, style = style, durationMs = durationMs, recordedAt = clock())
        recordings.upsert(recording)
        if (who == Who.CAREGIVER && style == RecordingStyle.SPOKEN) {
            items.get(itemId)?.let { items.upsert(it.copy(modelRecordingId = recording.id, updatedAt = clock())) }
        }
        return recording
    }

    suspend fun modelRecording(item: Item): Recording? =
        item.modelRecordingId?.let { recordings.get(it) } ?: recordings.latestFor(item.id, Who.CAREGIVER, RecordingStyle.SPOKEN)

    suspend fun sungRecording(item: Item): Recording? = recordings.latestFor(item.id, Who.CAREGIVER, RecordingStyle.SUNG)
```
Any other caller of `latestFor` (phase 2 word coach, phase 1 speaker) uses `modelRecording`, so only the repository changes.

`ItemEditViewModel.kt`: mirror the spoken-recording fields for sung ones: state gets `savedSungPath: String? = null`, `newSungRecording: Recorded? = null`, `isRecordingSung: Boolean = false` and `val sungPath get() = newSungRecording?.file?.absolutePath ?: savedSungPath`; `init` loads `savedSungPath = graph.items.sungRecording(item)?.path`; `toggleSungRecording()` is the same start/stop logic as `toggleRecording()` writing the sung fields (extract the shared start/stop into a private helper taking two lambdas so the logic is not duplicated); `playSung()` plays `sungPath`; `save` also writes `newSungRecording` with `RecordingStyle.SUNG`; `onCleared` deletes an unsaved sung file too.

`ItemEditScreen.kt`: under the existing voice row, only when `s.kind == ItemKind.PHRASE`, add:
```kotlin
            Spacer(Modifier.height(Sizes.gapSmall))
            Text("Τραγουδισμένο (για το «Τραγούδα και πες το»)", style = MaterialTheme.typography.bodyLarge)
            Row {
                BigButton(
                    if (s.isRecordingSung) "Στοπ" else "Τραγούδησέ το",
                    onClick = { askSungMic.launch(Manifest.permission.RECORD_AUDIO) },
                    icon = if (s.isRecordingSung) Icons.Rounded.Stop else Icons.Rounded.MusicNote,
                    tone = if (s.isRecordingSung) ButtonTone.Secondary else ButtonTone.Primary,
                    modifier = Modifier.weight(1f),
                )
                if (s.sungPath != null && !s.isRecordingSung) {
                    Spacer(Modifier.width(8.dp))
                    QuietButton("Άκου", onClick = vm::playSung, icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f))
                }
            }
```
with `val askSungMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleSungRecording() }` and import `androidx.compose.material.icons.rounded.MusicNote`.

- [ ] **Step 3: Tests, schema, commit**

Run: `./gradlew -q testDebugUnitTest && ANDROID_SERIAL=emulator-5554 ./gradlew -q connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.package=gr.dimitris.app.core.data` — pass; `app/schemas/.../4.json` exists.
```bash
git add app/schemas app/src/main app/src/test app/src/androidTest
git commit -m "feat(phase4): sung model recordings (db v4) with caregiver button

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Stage machine, module, ViewModel, screen

**Files:**
- Create: `modules/singsay/SingStage.kt`, `SingSayModule.kt`, `SingSayViewModel.kt`, `SingSayScreen.kt`
- Modify: `AppGraph.kt` (`modules += SingSayModule`)
- Test: `modules/singsay/SingStageTest.kt`

**Interfaces:**
- Produces: `object SingStage { const val LISTEN = 1; const val TOGETHER = 2; const val FADING = 3; const val TAPS_ONLY = 4; const val SPEAK = 5; fun gainFor(stage, repetition): Float; fun cueLevelFor(stage): Int; fun label(stage): String }`; `SingSayModule`; `SingSayScreen(items, sessionId, onDone)`; `SingSayViewModel(graph, items, sessionId)`.

- [ ] **Step 1: Failing stage tests**

`modules/singsay/SingStageTest.kt`:
```kotlin
package gr.dimitris.app.modules.singsay

import org.junit.Assert.assertEquals
import org.junit.Test

class SingStageTest {
    @Test fun `gain is full for together and fades over three repetitions`() {
        assertEquals(1f, SingStage.gainFor(SingStage.TOGETHER, 0))
        assertEquals(0.6f, SingStage.gainFor(SingStage.FADING, 0)); assertEquals(0.3f, SingStage.gainFor(SingStage.FADING, 1)); assertEquals(0f, SingStage.gainFor(SingStage.FADING, 2))
        assertEquals(0f, SingStage.gainFor(SingStage.TAPS_ONLY, 0)); assertEquals(0f, SingStage.gainFor(SingStage.SPEAK, 5))
    }
    @Test fun `cue level is five minus stage`() { assertEquals(0, SingStage.cueLevelFor(5)); assertEquals(3, SingStage.cueLevelFor(2)); assertEquals(4, SingStage.cueLevelFor(1)) }
    @Test fun `labels are Greek`() = assertEquals("Άκου", SingStage.label(SingStage.LISTEN))
}
```

- [ ] **Step 2: Stage object**

`modules/singsay/SingStage.kt`:
```kotlin
package gr.dimitris.app.modules.singsay

object SingStage {
    const val LISTEN = 1
    const val TOGETHER = 2
    const val FADING = 3
    const val TAPS_ONLY = 4
    const val SPEAK = 5
    const val FADING_REPS = 3

    fun gainFor(stage: Int, repetition: Int): Float = when (stage) {
        LISTEN, TOGETHER -> 1f
        FADING -> listOf(0.6f, 0.3f, 0f).getOrElse(repetition) { 0f }
        else -> 0f
    }

    /** Stage 5 = said it alone = cue 0; stage 1 = only listened = cue 4. */
    fun cueLevelFor(stage: Int): Int = (SPEAK - stage).coerceIn(0, 4)

    fun label(stage: Int): String = when (stage) {
        LISTEN -> "Άκου"
        TOGETHER -> "Τραγούδα μαζί"
        FADING -> "Τραγούδα, η μουσική σβήνει"
        TAPS_ONLY -> "Πες το με χτύπους"
        else -> "Πες το"
    }
}
```

- [ ] **Step 3: Module and ViewModel**

`modules/singsay/SingSayModule.kt`:
```kotlin
package gr.dimitris.app.modules.singsay

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.scheduler.SessionBuilder
import gr.dimitris.app.modules.Module

object SingSayModule : Module {
    override val id = ModuleId.SINGSAY
    override val titleGreek = "Τραγούδα και πες το"
    override val icon: ImageVector = Icons.Rounded.MusicNote
    private val kinds = listOf(ItemKind.PHRASE)

    override suspend fun planFor(graph: AppGraph): List<Item> =
        SessionBuilder(graph.db.items(), graph.db.schedules(), newPerDay = 3, maxItems = 5).plan(id, kinds)

    override suspend fun practiceFor(graph: AppGraph): List<Item> =
        planFor(graph).ifEmpty { graph.db.items().activeOfKinds(kinds).shuffled().take(4) }

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) = SingSayScreen(items, sessionId, onDone, onLeave)
}
```

`modules/singsay/SingSayViewModel.kt`:
```kotlin
package gr.dimitris.app.modules.singsay

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.data.now
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class SingSayState(
    val index: Int = 0,
    val total: Int,
    val item: Item,
    val notes: List<Note>,
    val stage: Int = SingStage.LISTEN,
    val repetition: Int = 0,
    /** Index of the syllable currently lit, or -1. */
    val lit: Int = -1,
    val playing: Boolean = false,
    val hasSungModel: Boolean = false,
    val isRecording: Boolean = false,
    val selfRecordingPath: String? = null,
    val done: Boolean = false,
    val error: String? = null,
)

class SingSayViewModel(private val graph: AppGraph, private val items: List<Item>, private val sessionId: String?) : ViewModel() {
    private val _state = MutableStateFlow(SingSayState(total = items.size, item = items.first(), notes = Melody.forPhrase(items.first().text)))
    val state: StateFlow<SingSayState> = _state.asStateFlow()
    private var startedAt = now()
    private var selfRecordingId: String? = null
    private var playJob: Job? = null

    init { load(0) }

    private fun load(i: Int) {
        val item = items[i]
        startedAt = now(); selfRecordingId = null
        _state.value = SingSayState(index = i, total = items.size, item = item, notes = Melody.forPhrase(item.text))
        viewModelScope.launch {
            _state.update { it.copy(hasSungModel = graph.items.sungRecording(item) != null) }
            playModel()
        }
    }

    /** Stage 1 and "Άκου": the caregiver's sung model if any, else TTS then the melody. */
    fun playModel() {
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _state.update { it.copy(playing = true, lit = -1) }
            val s = _state.value
            val sung = graph.items.sungRecording(s.item)
            val sungFile = sung?.let { graph.files.resolve(it.path) }
            if (sungFile != null && sungFile.exists()) {
                graph.voice.play(sungFile)
            } else {
                graph.speaker.speak(s.item)
            }
            graph.synth.play(s.notes.map { it.pitch }, gain = 1f) { i -> _state.update { it.copy(lit = i) } }
            _state.update { it.copy(playing = false, lit = -1) }
        }
    }

    /** Left-hand tap pad: one tap = next syllable lights and, if the stage still has backing, sounds. */
    fun tap() {
        val s = _state.value
        if (s.playing || s.notes.isEmpty()) return
        val next = (s.lit + 1) % s.notes.size
        _state.update { it.copy(lit = next) }
        graph.feedback.tap()
        val gain = SingStage.gainFor(s.stage, s.repetition)
        if (gain > 0f) viewModelScope.launch { graph.synth.play(listOf(s.notes[next].pitch), noteMs = 350, gapMs = 0, gain = gain) }
    }

    /** "Το έκανα": this repetition is done. Fading stage needs three; others advance immediately. */
    fun completeRepetition() {
        val s = _state.value
        graph.feedback.success()
        if (s.stage == SingStage.FADING && s.repetition + 1 < SingStage.FADING_REPS) {
            _state.update { it.copy(repetition = it.repetition + 1, lit = -1) }
        } else if (s.stage < SingStage.SPEAK) {
            _state.update { it.copy(stage = it.stage + 1, repetition = 0, lit = -1) }
        } else {
            finish(skipped = false)
        }
    }

    fun toggleRecording() {
        if (_state.value.isRecording) {
            runCatching { graph.voice.stopRecording() }
                .onSuccess { rec ->
                    _state.update { it.copy(isRecording = false, selfRecordingPath = graph.files.relativize(rec.file)) }
                    viewModelScope.launch { selfRecordingId = graph.items.addRecording(_state.value.item.id, rec.file, rec.durationMs, Who.DIMITRIS).id }
                }
                .onFailure { e -> graph.errors.record("singsay record stop", e); _state.update { it.copy(isRecording = false, error = "Πολύ σύντομη ηχογράφηση") } }
        } else {
            runCatching { graph.voice.startRecording() }
                .onSuccess { _state.update { it.copy(isRecording = true, error = null) } }
                .onFailure { e -> graph.errors.record("singsay record start", e); _state.update { it.copy(error = "Δεν ξεκίνησε η ηχογράφηση") } }
        }
    }

    fun playComparison() {
        val path = _state.value.selfRecordingPath ?: return
        playJob?.cancel()
        playJob = viewModelScope.launch {
            _state.update { it.copy(playing = true) }
            playModel().also { playJob?.join() }
            graph.voice.play(graph.files.resolve(path))
            _state.update { it.copy(playing = false) }
        }
    }

    fun skip() { graph.feedback.nudge(); finish(skipped = true) }

    private fun finish(skipped: Boolean) {
        if (_state.value.isRecording) toggleRecording()
        val s = _state.value
        val stageReached = s.stage
        val cue = SingStage.cueLevelFor(stageReached)
        val outcome = when { skipped -> Outcome.SKIPPED; cue <= 2 -> Outcome.CORRECT; else -> Outcome.ASSISTED }
        viewModelScope.launch {
            runCatching {
                graph.db.attempts().insert(Attempt(itemId = s.item.id, module = ModuleId.SINGSAY, sessionId = sessionId, startedAt = startedAt,
                    durationMs = now() - startedAt, outcome = outcome, cueLevel = cue, selfRecordingId = selfRecordingId, detail = """{"stage":$stageReached}"""))
                graph.scheduler.record(s.item.id, ModuleId.SINGSAY, outcome, cue)
            }.onFailure { graph.errors.record("singsay finish", it) }
        }
        val i = s.index + 1
        if (i >= items.size) _state.update { it.copy(done = true) } else load(i)
    }

    override fun onCleared() { playJob?.cancel(); graph.synth.stop(); if (graph.voice.isRecording) graph.voice.cancelRecording() }
}
```
(`playComparison` must not call `playModel()` recursively in a way that cancels itself: implement it by inlining the model playback — sung file or TTS + melody — then the self recording, inside one job.)

- [ ] **Step 4: Screen**

`modules/singsay/SingSayScreen.kt`:
```kotlin
package gr.dimitris.app.modules.singsay

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.theme.Sizes

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SingSayScreen(items: List<Item>, sessionId: String?, onDone: () -> Unit, onLeave: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: SingSayViewModel = viewModel(key = "singsay-${sessionId ?: "practice"}-${items.size}") { SingSayViewModel(graph, items, sessionId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { if (it) vm.toggleRecording() }

    LaunchedEffect(s.done) { if (s.done) onDone() }

    DimitrisScreen(
        title = "Τραγούδα ${s.index + 1}/${s.total}",
        onBack = { vm.leave(); onLeave() },
        bottom = {
            // The tap pad: huge, at the bottom-left where his left thumb lives.
            BigButton(if (s.stage == SingStage.SPEAK) "Το είπα!" else "Χτύπα", onClick = { if (s.stage == SingStage.SPEAK) vm.completeRepetition() else vm.tap() },
                tone = if (s.stage == SingStage.SPEAK) ButtonTone.Success else ButtonTone.Secondary, modifier = Modifier.height(110.dp), enabled = !s.playing)
            Spacer(Modifier.height(Sizes.gapSmall))
            if (s.stage != SingStage.SPEAK) {
                BigButton("Το έκανα", onClick = vm::completeRepetition, tone = ButtonTone.Success, enabled = !s.playing)
                Spacer(Modifier.height(Sizes.gapSmall))
            }
            QuietButton("Παράλειψη", onClick = vm::skip)
        },
    ) {
        Text(SingStage.label(s.stage) + if (s.stage == SingStage.FADING) " (${s.repetition + 1}/3)" else "", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(Sizes.gap))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            s.notes.forEachIndexed { i, n ->
                val lit = i == s.lit
                Box(
                    Modifier.background(if (lit) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.surface, RoundedCornerShape(12.dp))
                        .padding(horizontal = 14.dp, vertical = if (n.pitch == Pitch.HIGH) 4.dp else 18.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(n.syllable, style = MaterialTheme.typography.headlineMedium, color = if (lit) MaterialTheme.colorScheme.onSecondary else MaterialTheme.colorScheme.onSurface)
                }
            }
        }
        Spacer(Modifier.height(Sizes.gap))
        Row {
            QuietButton("Άκου", onClick = vm::playModel, icon = Icons.Rounded.VolumeUp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Sizes.gapSmall))
            QuietButton(if (s.isRecording) "Στοπ" else "Ηχογράφηση", onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                icon = if (s.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic, modifier = Modifier.weight(1f))
        }
        if (s.selfRecordingPath != null && !s.isRecording) {
            Spacer(Modifier.height(Sizes.gapSmall))
            QuietButton("Σύγκριση", onClick = vm::playComparison, icon = Icons.Rounded.Compare)
        }
        if (!s.hasSungModel) {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text("Δεν υπάρχει τραγουδισμένη φωνή για αυτή τη φράση, ακούς τη μελωδία.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (s.error != null) { Spacer(Modifier.height(Sizes.gapSmall)); Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge) }
    }
}
```
(HIGH syllables sit higher than LOW ones through the padding trick, so the melody is visible as well as audible.)

In `AppGraph.kt`: `val modules: List<Module> = listOf(WordCoachModule, NumbersModule, SingSayModule)`.

- [ ] **Step 5: Build, tests, play it**

Run: `./gradlew -q testDebugUnitTest && ANDROID_SERIAL=emulator-5554 ./gradlew -q installDebug connectedDebugAndroidTest`
Expected: green. Free practice → "Τραγούδα και πες το": the phrase plays (TTS then melody, syllables light in turn); "Χτύπα" advances and sounds the note; "Το έκανα" moves through the stages; in stage 3 the tone gets quieter each repetition; stage 5 ends with "Το είπα!". Record a sung model in the editor for "Θέλω καφέ" and it plays instead.

- [ ] **Step 6: Commit**

```bash
git add app/src/main app/src/test
git commit -m "feat(phase4): sing-then-say module with staged melodic intonation

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Phase 4 verification

- [ ] Full suites green; install on `R5CWC2C1KSJ`; sing through one phrase across all five stages with the phone's speaker on; record Chris's sung model for two phrases; Σφάλματα stays empty.
- [ ] Append "Phase 4 verified on <date>, <device>" here; commit `docs(phase4): verification notes`.
