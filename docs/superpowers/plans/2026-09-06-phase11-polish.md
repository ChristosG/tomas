# Dimitris' App — Phase 11 (Polish after the first field test) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn Chris's first field-test notes and the "helpfulness for Dimitris only" principle into code: shape-aware writing scores with a strictness setting, errorless listening everywhere, a recogniser that waits for him and checks gently, tunable melody, a caregiver verification loop, and a Claude advisor with a memory and the whole journey.

**Architecture:** Pure scorer changes with tests; a shared `ModelListen` convention in the four speech modules; recogniser timing in `AndroidSpeechToText` and a `Recorder` amplitude probe; two new synced tables (`advice`, `notes`, DB v7) and a `JourneyReport` builder that replaces `AdviceSummary`; a `Focus` applied by `SessionBuilder`.

**Tech Stack:** as before. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§12 amendments)

## Global Constraints

- Greek-only UI; touch targets ≥ 72dp; primary actions at the bottom; no timers (a listening window that waits for him is not a timer against him); success = icon + sound + haptic; adult tone; never a fail state on speech.
- Errorless speech: «Άκου» is always enabled where a model exists; listening sets `cueLevel = max(cueLevel, 3)`; no visible text ever calls an assisted answer wrong.
- Writing: a wrong letter never passes at Κανονικό; a hand-like centre-line trace of the right letter always passes at Κανονικό.
- Every Room change: sync-ready columns, additive auto-migration, schema committed, MigrationTest case, sync registry updated (`TableSpec` with a sample entity).
- Nothing leaves the device except through the Claude button; the caregiver sees exactly the text that is sent; keys/tokens never in logs, `error_logs`, or the report.
- Conventions of phases 0–10 apply (module contract, writes on `graph.scope`, `leave(then)`, Greek error slot, `ANDROID_SERIAL=emulator-5554`, `pm clear` before connected runs). Commits `feat(phase11): ...`/`fix(phase11): ...` with the trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

### Task 1: Shape-aware trace scorer and the strictness setting

**Files:**
- Modify: `modules/trace/TraceScorer.kt`, `modules/trace/TraceViewModel.kt`, `modules/trace/Glyphs.kt` (segment ids per sampled point), `core/settings/Settings.kt` (+ `traceStrictness`: LOOSE/NORMAL/STRICT, default NORMAL), `caregiver/SettingsScreen.kt` (row «Αυστηρότητα γραψίματος» with three 72dp `FilterChip`s under «Ασκήσεις»)
- Test: `TraceScorerTest.kt` (rewrite), `SettingsTest.kt` (+1), instrumented `TraceFlowTest.kt` (+ wrong-letter case)

**Interfaces:**
- `data class TemplatePoint(val pt: Pt, val segment: Int)`; `Glyphs.template(...)` returns the points with a segment id: every contour is split by arc length into `ceil(length / (templateHeight * 0.12))` segments (≥ 8 per contour).
- `data class TraceScore(val coverage: Float, val precision: Float, val meanDistance: Float, val passed: Boolean)`.
- `data class Strictness(val tolerancePx: Float, val coverRadiusPx: Float, val minCoverage: Float, val minPrecision: Float)`; `object Strictness { fun of(level: TraceStrictness, density: Float, recall: Boolean): Strictness }` — Κανονικό: tolerance 12dp, cover radius 14dp, coverage ≥ 0.80, precision ≥ 0.80; Χαλαρό: 16dp / 18dp / 0.70 / 0.70; Αυστηρό: 8dp / 10dp / 0.90 / 0.90; recall (level 5) subtracts 0.15 from both minimums.
- `TraceScorer.score(strokes: List<List<Pt>>, template: List<TemplatePoint>, inside: (Pt) -> Boolean, s: Strictness): TraceScore` — coverage = fraction of segments with at least one resampled ink point within `coverRadiusPx` of one of its points; precision = fraction of resampled ink points that are `inside` the glyph or within `tolerancePx` of a template point; `meanDistance` kept for the detail JSON; `passed = coverage ≥ minCoverage && precision ≥ minPrecision`.

- [ ] Failing tests (JVM, rectangle-mask glyphs built in the test): a centre-line trace of a thick «H» (three strokes, ±3 dp wobble) passes at every strictness; a «K» drawn over the «H» template fails at Κανονικό and Αυστηρό (precision: the diagonals leave the mask; coverage: the crossbar segments untouched) and may pass only at Χαλαρό if at all — assert it fails at Κανονικό; an «O» over a «K» fails at all three; one stroke covering half the letter fails coverage; a perfect trace drawn 20 % larger fails precision at Κανονικό; a single tap never passes; a recall trace (level 5) with 0.7 coverage and 0.75 precision passes at Κανονικό.
- [ ] Implement `Glyphs` segments, `Strictness`, the scorer; `TraceViewModel` reads `Settings.traceStrictness` on load and passes `recall = level == 5 && hidden`; the detail JSON adds `coverage`, `precision`, `strictness`.
- [ ] Settings row + persistence + test.
- [ ] Instrumented: `TraceFlowTest` gains "the wrong letter shows «Ξανά» and writes no row" using a «K»-shaped stroke set over the «H» target built from the H template's mask (choose points inside the K but outside the H).
- [ ] `./gradlew -q testDebugUnitTest`; connected `TraceFlowTest`; commit `feat(phase11): writing is judged by shape, with a caregiver strictness setting`.

---

### Task 2: Errorless listening in the four speech modules

**Files:**
- Modify: `modules/wordcoach/{WordCoachViewModel,WordCoachScreen}.kt`, `modules/scripts/{ScriptsViewModel,ScriptsScreen}.kt`, `modules/singsay/{SingSayViewModel,SingSayScreen}.kt`, `modules/sentences/{SentencesViewModel,SentencesScreen}.kt`, `modules/wordcoach/CueLadder.kt` (+ `fun listened()` = level max(current, 3))
- Test: `CueLadderTest` (+2), instrumented flow tests of the four modules (+1 each: «Άκου» enabled before any hint and the attempt lands with `cueLevel ≥ 3`)

- [ ] Rule: a `BigButton("Άκου", Secondary)` (icon `Icons.Rounded.VolumeUp`) is present and enabled on every DIMITRIS turn from the first second, plays the model (caregiver recording, else TTS, through `graph.speaker.speak(item)`; sentences: the whole sentence), disabled only while the model is actually playing or he is recording; pressing it calls `ladder.listened()`; «Βοήθεια» keeps walking the ladder as today; «Άκου ξανά» in dialogues is replaced by this button. Sing-then-say: «Άκου» plays the sung model or the melody + TTS fallback at any stage.
- [ ] Audit the visible strings of the four screens and the session summary: no wording that labels an assisted or listened answer as wrong (e.g. «Σχεδόν», «Με βοήθεια» are fine; anything like «Λάθος» on a speech turn is not).
- [ ] Tests; commit `feat(phase11): hearing the model is always allowed`.

---

### Task 3: A recogniser that waits, catches silence, and checks gently everywhere he records

**Files:**
- Modify: `core/speech/{SpeechToText,AndroidSpeechToText,SpeechMatch}.kt`, `core/audio/{Recorder,Voice}.kt` (+ `Recorded.peakAmplitude`), `modules/wordcoach/*`, `modules/scripts/*`, `modules/singsay/*` (stage 5 «Πες το κανονικά»), `caregiver/SettingsScreen.kt` (the recognition row's help text)
- Test: `SpeechMatchTest` (+ phrase cases), `RecorderTest` instrumented (+ silence), flow tests (+ silence path with a fake recorder)

**Interfaces:**
- `AndroidSpeechToText`: `EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS = 8_000`, `EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS = 3_000`, `EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS = 3_000`, internal bound 20 s; `listen()` exposes a `StateFlow<Float>` of the RMS level (`onRmsChanged`) for the UI and a `stop()` that ends the window early and returns what was heard.
- UI while listening: a pulsing mic icon with the level bar and the line «Σε ακούω…», a 72dp «Στοπ» at the bottom; no countdown shown.
- `Recorded.peakAmplitude: Int` sampled every 100 ms via `MediaRecorder.maxAmplitude` during the take; `Voice.stopRecording()` returns it; a take with `peakAmplitude < 1_500` (calibrate on the emulator with the test tone and note the value) is treated as silence: the file is deleted, the line «Δεν σε άκουσα. Πες το πιο δυνατά.» shows, nothing is recorded, the turn stays open.
- `SpeechMatch.phraseMatches(heard: String, target: String): Boolean` — normalised word overlap ≥ 0.6 or `matches` on the whole string; used for dialogue lines and sing-then-say phrases; `matches` stays for single words.
- Gentle check (only when `sttEnabled`): after his take (word coach, dialogues, sing-then-say stage 5) the recogniser result is compared; match → success feedback and auto-confirm with the current cue level; mismatch → «Δοκίμασε ξανά» once (haptic nudge, cue unchanged, «Άκου» offered); after the second mismatch «Το είπα!» still confirms as today. The recogniser listens INSTEAD of a separate take when it is on (one flow, one button «Μίλα»), and the audio it captured is not saved (the platform recogniser gives no file): the record-and-compare take stays available through a separate «Ηχογράφηση» as today.

- [ ] Tests first (SpeechMatch phrases; silence path; the gentle check state machine in a VM unit test with fakes).
- [ ] Implement; verify on the emulator that the window waits (speak after 4 s of silence with `adb emu` audio injection is not possible — verify the intent extras by a unit test and the timing by hand on Chris's phone); commit `feat(phase11): the recogniser waits for him, silence is caught, and a mismatch is a nudge`.

---

### Task 4: Melody tempo and key settings

**Files:**
- Modify: `modules/singsay/Melody.kt` (`Tempo { NORMAL(550, 80), SLOW(750, 110) }`, `Key { NORMAL(196.0, 246.94), LOW(146.83, 185.0) }`), `core/settings/Settings.kt` (+ `melodyTempo`, `melodyKey`), `modules/singsay/SingSayViewModel.kt` (reads both on load), `caregiver/SettingsScreen.kt` (two chip rows under «Τραγούδα»)
- Test: `MelodyTest` (+2), `SettingsTest` (+2)

- [ ] Implement, commit `feat(phase11): melody tempo and key are caregiver settings`.

---

### Task 5: Caregivers can verify what they add

**Files:**
- Modify: `caregiver/content/ItemEditScreen.kt` (+ `QuietButton("Δοκίμασέ το")` visible for a saved item), `caregiver/scripts/ScriptEditScreen.kt` (+ «Παίξ' το»), `Nav.kt` (`practice/{moduleId}?item={id}` and `?script={id}`), `today/PracticeViewModel.kt` (a single-item plan when `item` is given; the given script when `script` is given), `core/scheduler/SessionBuilder.kt` (CAREGIVER-source items created since the last session go first among the new items, up to `newPerDay`)
- Test: `SessionBuilderTest` (+1: a caregiver word added yesterday is introduced before seed words), instrumented `ItemEditFlowTest` (+1: «Δοκίμασέ το» opens the word coach with that word)

- [ ] Implement, commit `feat(phase11): try a word or a dialogue right after saving it`.

---

### Task 6: Claude advisor v2 — journey, notes, memory, focus

**Files:**
- Modify: `core/data/{Entities,Daos,AppDatabase}.kt` (DB v7: `advice(id, at, model, report, caregivers, dimitris, focusJson, createdAt, updatedAt, deleted)`, `notes(id, at, text, author, createdAt, updatedAt, deleted)`; indices on `updatedAt`), `core/sync/SyncModel.kt` (+ both tables, LWW), `caregiver/insights/{AdviceSummary→JourneyReport,ClaudeAdvisor,AdviceScreen,AdviceSession}.kt`, `core/scheduler/SessionBuilder.kt` (focus first), `caregiver/progress/ProgressScreen.kt` (button → advice; notes entry), `Nav.kt`
- Create: `caregiver/insights/{JourneyReport,Focus,NotesScreen}.kt`
- Test: `JourneyReportTest`, `FocusTest`, `ClaudeAdvisorParseTest` (+ the third section), `SessionBuilderTest` (+ focus), `MigrationTest` (6→7), `SyncModelTest` (+2 tables), `MergeTest`

**Interfaces:**
- `JourneyReport.build(profile: String, notes: List<Note>, lifetime: List<ItemHistory>, recent: List<DayItemStat>, days: List<DayStat>, previous: List<Advice>, levels: Map<String, Int>, insights: List<String>): String` — Greek plain text with these headings in this order: «Προφίλ», «Σημειώσεις φροντιστών» (newest first, at most 20), «Όλη η πορεία ανά λέξη» (one line per item ever practised: `λέξη · είδος · κατηγορία · πρώτος ήχος · ασκήσεις N · σωστά/με βοήθεια/παράλειψη · μέση βοήθεια x.x · κουτί b · πρώτη/τελευταία φορά · φωτογραφία ναι/όχι · φωνή ναι/όχι`, sorted by attempts desc, capped at 400 lines), «Τελευταίες 4 εβδομάδες ανά ημέρα» (per day: minutes, attempts, then per item `λέξη: N (σ/β/π), βοήθεια x.x`), «Προηγούμενες συμβουλές» (last 5: date, the caregivers' section, the focus), «Επίπεδα», «Τι βλέπει η εφαρμογή». Hard cap 400 000 characters; when over, drop the oldest lifetime lines first, then per-day detail beyond 14 days. Never paths, ids, audio.
- `ItemHistory`, `DayItemStat` computed in `ProgressStats` (+ `lifetime(attempts, schedules, items)` and `recentByDay(...)`), DAOs: `AttemptDao.all()` (lifetime, bounded by a LIMIT of 100 000 newest), `AdviceDao`, `NoteDao`.
- System prompt v2 (Greek): the profile; "you are advising as a speech-and-language-therapy-informed coach, not a doctor"; compare with the previous advices and say what changed; be specific (which words, which first sounds, which module, what the caregivers should record or photograph, whether a level should move); three sections with exact headings: `## Για τους φροντιστές` (5–10 sentences), `## Για τον Δημήτρη` (two short sentences, simple Greek, read aloud), `## Εστίαση` containing one JSON object on one line: `{"items":["…"],"sounds":["π"],"modules":["WORDCOACH"],"levels":{"numbers":3,"sentences":2,"trace":2},"why":"…"}` — items must be words from the report, levels optional.
- `Focus(items, sounds, modules, levels, why, at)` parsed from the JSON (tolerant: missing keys → empty; unknown words dropped); stored in `advice.focusJson`; `Focus.active(now)` = the newest advice within 7 days.
- `SessionBuilder`: when a focus is active, its items (by normalised text) and any item whose `firstSound` is in `focus.sounds` are planned first (still respecting `SessionBudget`); the focus never removes due items, it orders them.
- `AdviceScreen`: notes entry at the top (multi-line field + «Αποθήκευση σημείωσης», list of notes newest first, 72dp rows), then «Τι θα σταλεί» (collapsed preview with the character count), «Ρώτα τον Claude», the answer in its three parts (Εστίαση rendered as chips + «Εφάρμοσε τα επίπεδα» applying the level suggestions through the Settings setters after a confirm dialog; item/sound focus applies automatically), «Πες το στον Δημήτρη», and a history list of previous advices (date + first line, tap to expand).
- `MAX_TOKENS = 16_000`; model unchanged; text-only; the token/key handling unchanged.

- [ ] Tests first (report headings and caps; JSON parse tolerance; focus ordering; migration; sync registry).
- [ ] Implement; on the emulator with a dummy key the flow reaches the network and fails in Greek; `pm clear` + full connected suite; commit `feat(phase11): the advisor reads the whole journey and remembers its advice`.

---

### Task 7: Housekeeping

- [ ] `.github/workflows/*.yml`: `actions/setup-java` to the v5 major (pinned SHA); `docs/HANDOVER.md` updated for phase 11 (settings, advisor v2, what to test on the phone).
- [ ] Commit `chore(phase11): setup-java v5, handover notes`.

---

### Task 8: Verification

- [ ] Full suites green (JVM, connected after `pm clear`, server); tag `v0.2.0` and confirm the Release; append verification notes; commit `docs(phase11): verification notes`.
- [ ] On Chris's phone (Chris): Γράψε with the wrong letter at Κανονικό → «Ξανά»; the right letter → pass; «Άκου» on a dialogue line before any hint; recognition waiting ≥ 8 s; a silent take → «Δεν σε άκουσα»; melody at Αργό/Χαμηλό; «Δοκίμασέ το» after adding a word; a real Claude consultation with the notes filled in, then a session showing the focus words first.
