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

### Task 7: Record everything that could adapt later

Chris's principle: keep the data now, wherever adaptation could help him later, so tempo, difficulty and timing can be tuned from his own behaviour instead of guessed.

**Files:**
- Create: `core/data/Telemetry.kt` (one `Adapt` helper that every module uses to fill `Attempt.detail`), `docs/ADAPTATION.md` (per module: what is recorded, which knob it could drive, the rule we would try first)
- Modify: every module ViewModel's attempt writer (`modules/*/…ViewModel.kt`), `today/SessionViewModel.kt` (session detail), `core/data/Entities.kt` (no schema change: `detail` is JSON; `Session` gains nothing — its own timings go into an `attempts` row with `itemId = "session:<id>"`? No: keep `Session` as is and add the timing to `detail` of a final synthetic attempt `itemId = "session:summary"`, `module` = the last module, `sessionId` set)
- Test: `TelemetryTest` (every module's detail parses back and carries the keys listed below), `ProgressStats` unchanged

**What each module records in `detail` (all keys optional, all numbers plain, no text beyond what is already there):**
- Every module: `ms` (time from the item being shown to its outcome), `retries`, the parameters in force at the time (`strictness`, `tempo`, `key`, `sizeDp`, `level`, `sttOn`, `sttWaitMs`).
- Word coach / dialogues: `cueLevel` (already), `listened` (count of «Άκου»), `hintMsFirst` (time to the first hint), `sttHeard` (the recogniser's best text, only when on), `sttMatched`, `takeMs` (recording length), `peak` (amplitude).
- Sing-then-say: `stage` reached (already), `repsPerStage` [n1..n5], `msPerStage`, `tempo`, `key`, `sung` (sung model present).
- Numbers: `type`, `answer`, `chosen` (the wrong option when wrong: the distance tells how far off he was), `optionCount`, `ms`.
- Sentences: `tiles`, `chosen`, `firstTry` (already), `undo` count, `ms`.
- Trace: `coverage`, `precision`, `strokes`, `ms`, `strictness`, `hand` (already), `templateHeightPx`.
- Arcade: `hits`, `misses`, `sizeDp` (already), `msPerTarget` (median), `missDistanceDp` (median distance of misses from the target centre).
- Session summary row: `plannedModules`, `plannedCount`, `completed`, `leftEarly`, `ms`, `msPerModule`.

- [ ] Write `docs/ADAPTATION.md` first (the table above plus, per knob, the first adaptation rule to try: e.g. melody tempo → slow down when `repsPerStage[2] > 3` on two consecutive sittings; STT wait → grow by 2 s when `sttHeard` is empty on ≥ 3 of the last 5 takes; trace strictness → suggest Χαλαρό when precision < 0.6 across a sitting, Αυστηρό when > 0.95 for a week; numbers/sentences/trace levels → already adaptive; arcade size → already adaptive but per-game `missDistanceDp` should set the growth factor).
- [ ] Implement `Adapt` and the keys; keep every existing `detail` key; tests; commit `feat(phase11): every exercise records what could adapt it`.

---

### Task 8: Housekeeping

- [ ] `.github/workflows/*.yml`: `actions/setup-java` to the v5 major (pinned SHA); `docs/HANDOVER.md` updated for phase 11 (settings, advisor v2, adaptation data, what to test on the phone); `README.md` gains the phase 11 row and a link to `docs/ADAPTATION.md`.
- [ ] Commit `chore(phase11): setup-java v5, handover and README`.

---

### Task 9: Verification

- [ ] Full suites green (JVM, connected after `pm clear`, server); tag `v0.2.0` and confirm the Release; append verification notes; commit `docs(phase11): verification notes`.
- [ ] On Chris's phone (Chris): Γράψε with the wrong letter at Κανονικό → «Ξανά»; the right letter → pass; «Άκου» on a dialogue line before any hint; recognition waiting ≥ 8 s; a silent take → «Δεν σε άκουσα»; melody at Αργό/Χαμηλό; «Δοκίμασέ το» after adding a word; a real Claude consultation with the notes filled in, then a session showing the focus words first.

## Verification notes (2026-09-07)

Final tree `9060333`: 709 JVM tests (1 skipped: the live Claude call, no key on the dev machine), 139 instrumented on the emulator with the sync server running on 18787 (0 skipped), 29 server tests. Every device check ran on `emulator-5554`; the phone was never touched by a dispatch. Still only a human can verify: the recogniser wait on Google's service, the three strictness levels with a real hand, the melody settings by ear, a real Claude consultation with notes, and a note syncing between two phones.

## Execution record (controller rulings, 2026-09-06/07)

Copied from the SDD ledger at phase close.

## Pre-flight conflict scan (2026-09-06)

| Tasks | Shared surface | Produces vs consumes | Finding |
|---|---|---|---|
| 1 / phase 7 | TraceScorer signature changes (segments, Strictness) | TraceViewModel, TraceFlowTest, arcade's trace game (phase 8) reuse `scoreStrokes` | Ruling: the arcade path keeps a thin compatibility overload (tolerance-only path, no mask segments) so Task 1 does not touch modules/arcade — cost: none |
| 2 / 3 | both edit the four speech ViewModels/Screens | Task 2 adds «Άκου» + ladder.listened(); Task 3 adds the recogniser flow, silence, gentle check | Ruling: serial order 2 then 3; Task 3 builds on Task 2's screens |
| 3 / phase 2 | SpeechToText.listen() contract (Result<Transcript>, 15 s bound) | word coach's existing STT match path at WordCoachViewModel ~line 75/168 | Task 3 generalises it; keep the Result contract; bound to 20 s |
| 3 / phase 1 | Recorder/Voice: Recorded gains peakAmplitude | ItemEditViewModel, ScriptEditViewModel, SingSay editor takes | additive field with a default; editors ignore it |
| 5 / phase 5 | PracticeViewModel single-item / single-script plans | ScriptsModule resolves the script from items.first() via lineOfItem — a script plan must pass its DIMITRIS-line items | consistent |
| 5 / phase 8 | SessionBuilder ordering (rotation, focus in Task 6, caregiver-first in Task 5) | three orderings on one list | Ruling: order = focus items → new caregiver items → the rest as today; the module rotation is untouched |
| 6 / phase 10 | DB v7 + two synced tables; TableSpec sample entities; the validator rejects rows missing non-null fields | both phones must update together (documented) | consistent |
| 6 / phase 9 | AdviceSummary replaced by JourneyReport; AdviceSession/Screen reshaped; the share button shares the report | AdviceSummaryTest replaced | consistent |
| 6 alone | report size up to 400 000 chars ≈ 100k+ tokens with claude-opus-5 | cost is no concern (Chris); 120 s timeout may be tight for a long thinking answer | Ruling: timeout 240 s for the advisor call — cost: none |
| all | Greek-only, 72dp, no timers, errorless speech, shape-judged writing | consistent |

Scan result: four rulings carried into dispatches.

## Task log
Task 1: dispatched — BASE f3b9688, model opus
Plan amended (Chris, 17:41): new Task 7 'record everything that could adapt later' (Adapt helper, per-module detail keys, docs/ADAPTATION.md); housekeeping and verification renumbered 8 and 9; briefs regenerated.
Task 1: implementer DONE_WITH_CONCERNS (55fb5af; JVM 480, connected 91; K over H refused at Κανονικό 0.69/0.71, hand-like letters 1.0/1.0). Accepted: coverage reach measured from the nearest outline for inside points. Ruling: Χαλαρό minimums raised to 0.75/0.75 so a wrong letter never passes at any strictness — into Task 1's fix round (after Task 2, to keep one implementer in git). Review dispatched (opus); Task 2 dispatched in parallel — BASE = the docs commit after 55fb5af, model opus.
Task 1: review DONE (2 Critical / 3 Important / 6 Minor). Rulings: C1 both distances capped by min(dp·density, glyphHeight·0.12) so word-scale letters are judged at their own scale; C2 only strokes drawn since the last judgement are scored (a miss dims the old ink; «Ξανά» clears it); I1 ink-length guard: resampled ink ≤ 2.5× the template skeleton length (fail = 'Πολύ μελάνι' nudge, never a row); I2 scoring on Dispatchers.Default; I3 a word-scale wrong-shape test; plus Χαλαρό 0.75/0.75. Fix round 1 queued for the Task 1 implementer — dispatched after Task 2 reports (one implementer in git at a time).
Task 2: implementer DONE (9498379; JVM 484, connected 95). Accepted: sentences outcome follows a listen; «Σχεδόν.» wording; Numbers «Άκου ξανά» renamed «Άκου» in Task 8 housekeeping. Review dispatched (opus). Task 1 fix round 1 dispatched now — BASE 9498379, resuming implementer a567defc8eab01197.
Task 2: review DONE (0 Critical / 3 Important / 7 Minor). Rulings: I1 record buttons disabled while the model plays (and toggleRecording quiets first); I2 «Άκου» disabled while the recogniser listens; I3 modelPlaying reset on advance; Leitner policy untouched (ASSISTED holds, only SKIPPED demotes — listening is never punished); SessionBuilder reserves 2 new-word slots per session so a listening habit cannot starve new vocabulary (unit test); minors at judgement. Fix round 1 queued for the Task 2 implementer adb05bb6821b60ea9 — after the Task 1 fix round lands.
Task 1: fix round 1 DONE (a7b92b5; JVM 488, connected 100). Rulings for round 2 (after Task 2's fix): per-letter marking for words — Glyphs groups contours per character (getTextPath per char with measureText advances), the worst letter must clear the bar, the failed letter is highlighted in the reveal; tolerance cap tightened to height·0.09 (Χαλαρό then refuses K-over-H at every size); the 2.5× ink budget stays until real finger traces calibrate it (adaptation data). Task 2 fix round 1 dispatched now — BASE a7b92b5, resuming implementer adb05bb6821b60ea9.
Task 1: re-review of fix round 1 — 2 open (leave() must set ending and cancel/join the in-flight judgement; the settings help line becomes true once the 0.09 cap makes Χαλαρό refuse the wrong letter at every size). Both fold into Task 1 round 2 (per-letter marking) — dispatched after the Task 2 fix lands.
Task 2: fix round 1 DONE (cc2b370; JVM 491, connected 102). Accepted: sing-then-say opens with its model playing; M6 (sentences level-4 board below the fold on a 360x640 phone) parked — Chris's phone is 412x915dp; will look at it on the phone. Scoped re-review dispatched (sonnet). Task 1 round 2 dispatched — BASE cc2b370, resuming implementer a567defc8eab01197.
Task 2: complete (9498379..cc2b370, re-review clean).
Task 1: fix round 2 DONE (169bd0a; JVM 493, connected 104; K over one η of Δημήτρης refused 0.63/0.78 at every strictness; hand-like word 1.0/1.0; canvas slop fix). Rulings for the final fix wave: per-letter ink budget and per-letter precision mask; leave() race accepted by inspection. Scoped re-review dispatched (opus). Task 3 dispatched — BASE 169bd0a, model opus.
Task 1: re-review of round 2 — 2 Minor open (canvas: a bare tap becomes a one-point stroke and a cancelled drag is kept; tryAgain wording untested on the JVM). Ruling: both into the final fix wave with the per-letter ink budget/mask. Task 1 complete (55fb5af..169bd0a).
Task 3: implementer DONE (f28b884; JVM 529, connected 115). Ruling for the fix round: the silence extras are advisory on Android, so the recogniser auto-restarts on ERROR_SPEECH_TIMEOUT / ERROR_NO_MATCH inside the 20 s bound with the indicator unchanged (an effective wait regardless of the vendor); cloud STT stays the fallback plan. Review dispatched (opus); Task 4 dispatched in parallel — BASE f28b884, model sonnet (the Task 3 fix round waits for Task 4 to land).
Task 3: review DONE (0 Critical / 3 Important / 9 Minor; ✅). Rulings: I1 comparison/bubble playback guarded while listening; I2 an sttResolved flag gates the green primary; I3 onError classified (hard failure vs heard-nothing) with the auto-restart on timeout/no-match inside the 20 s bound and a stopped flag; minors at judgement. Fix round 1 queued for the Task 3 implementer aaa5e6dbe254339c1 — after Task 4 lands.
Task 4: implementer DONE (abb58c4; JVM 533; SingSayFlowTest 6/6; Voice/ToneSynth read key.hz — accepted). Review dispatched (sonnet). Task 3 fix round 1 dispatched — BASE abb58c4, resuming implementer aaa5e6dbe254339c1.
Task 4: review DONE (approved; 2 Minor: settings read racing the first phrase — into the final fix wave (read settings before load(0)); no end-to-end wiring test — accepted). Task 4 complete (abb58c4).
Task 3: fix round 1 DONE (620872b; JVM 556, connected 119). Ruling: m9 (silence check on caregiver takes in the editors) goes into Task 5 — a silent caregiver model would break «Άκου» for him. Scoped re-review dispatched (opus); Task 5 dispatched in parallel — BASE 620872b, model opus.
Task 3: re-review of fix round 1 — 6 open (all small). Rulings for fix round 2 (after Task 5 lands): sttResolved applied in sing-then-say; a heard-nothing window never spends a try (tries count real mismatches only); the recogniser failure is logged once per run; hint()/speakCue guarded while listening; the restart loop has a 1 s minimum session and a cap of 3 restarts; an error after «Στοπ» (ERROR_CLIENT) is treated as stopped with an empty transcript.
Task 5: implementer DONE (18ccf0e; JVM 563, connected 123). Accepted: save aborts on a silent/short open take. Ruling for its fix round: «Δοκίμασέ το» on a new draft saves WITHOUT closing the editor and then opens the practice run; back returns to the editor. Review dispatched (sonnet). Task 3 fix round 2 dispatched — BASE 18ccf0e, resuming implementer aaa5e6dbe254339c1.
Task 5: review DONE (0 Critical / 1 Important / 0 Minor). Fix round 1 queued for the Task 5 implementer a005a87f75a20f29c (canTry guarded while recording; new-draft save-without-closing) — after the Task 3 round 2 lands.
Task 3: fix round 2 DONE (c7c2721; JVM 571, connected 124). Rulings for the final fix wave: a window counter opens «Το είπα!» after 2 windows without a match (sttTries keeps meaning real mismatches) so heard-nothing can never wall him; MAX_RESTARTS 6 (the 20 s bound governs). Scoped re-review dispatched (opus). Task 5 fix round 1 dispatched — BASE c7c2721, resuming implementer a005a87f75a20f29c.
Task 3: complete (f28b884..c7c2721, re-review clean; two items ruled into the final wave: window counter, MAX_RESTARTS 6).
Task 5: fix round 1 DONE (f9480c5; JVM 585, connected 124). Accepted: a tried draft is saved; header unchanged. Scoped re-review dispatched (sonnet). Task 6 dispatched — BASE f9480c5, model opus.
Task 5: complete (18ccf0e..f9480c5, re-review clean).
Task 6: implementer DONE (56f6edb, 1d56327; JVM 643, connected 128; sample journey 14.7k chars). Rulings for its fix round: the tiers guarantee slots, the easy–hard–easy sandwich still orders the sitting (focus/new items are not forced first); focus.modules gets priority in ModuleRotation for the focus window. Review dispatched (opus); Task 7 dispatched in parallel — BASE 1d56327, model opus (the Task 6 fix round waits for Task 7).
Task 6: review DONE (1 Critical / 8 Important / 9 Minor; clinician verdict positive with three reservations). Rulings for its fix round (after Task 7 lands): C1 server accepts advice+notes (LWW) with a store test, SyncModelTest restored to 'every table the server knows'; I1 «φωνή ναι» = caregiver voice only; I2 the preview is always the report that will be sent, the history keeps what was sent; I3 the share button previews and confirms («Περιλαμβάνει τις σημειώσεις σας»); I4 the prompt explains the cue scale, that listening is encouraged and forces cue ≥ 3, the Leitner boxes and what ASSISTED means; I5 a per-module section (numbers/sentences/trace/arcade: attempts, accuracy, level per week, mean ms) built from the synthetic-id attempts; I6 the per-day header names the word-less exercises separately; I7 levelsApplied reset per advice; I8 TRACE excluded from difficulty/skip lists and the cue trend (handwriting is not vocabulary recall); plus tiers guarantee slots under the sandwich, and focus.modules → ModuleRotation. 240 s timeout kept.
Task 7: implementer DONE (3568d35; JVM 667, connected 128). Its two concerns (the session:summary row counted by ProgressStats per-module/day and by ModuleRotation.lastUsedAt) go into the Task 6 fix round (same files). Review dispatched (sonnet). Task 6 fix round 1 dispatched — BASE 3568d35, resuming implementer a4ae3ebd58567619b.
Task 7: review DONE (0 Critical / 1 Important / 3 Minor; ✅). Rulings: the session summary row is written only when the sitting had at least one attempt; strokes absent on a skip; Detail.kept goes through the finite guard; the monotonic-clock point is accepted as pre-existing. All into the final fix wave (Task 8 housekeeping goes first when git frees).
Task 6: fix round 1 DONE (ff000a3 server, 0586f34 pull path for the two tables — a second half of C1 found by the round-trip test, fde85ff, 59ca227; JVM 690, server 29, connected 135 with the server up). My 'twelve' was a miscount: ten tables is right. Accepted: focus.modules prefers only planned modules; weekly level mean documented. Scoped re-review dispatched (opus). Task 8 dispatched in parallel — BASE 59ca227, model sonnet.
Chris (00:54): a private, gitignored report for the father and carers was written at docs/private/2026-09-07-erotimatologio-frontiston.md (Greek questionnaire + hypotheses + mapping to app knobs); add 'docs/private/' to .gitignore in the final fix wave commit (no git writes while Task 8 runs).
Task 8: DONE (0c05163; setup-java v5.7.0 pinned; HANDOVER + README updated). Final fix wave dispatched — BASE 0c05163, model opus (all queued rulings from Tasks 1, 2, 3, 4, 7 + docs/private gitignore); Task 6 re-review still running, its residuals fold in when it reports.
Task 6: re-review — 5 open (1 regression: reservations unbounded by maxItems; 4 minor). Rulings sent to the fix-wave implementer: reserved slots (focus + new) ≤ maxItems/2 so due items always keep at least half; a focused word that is due-but-capped gets its guaranteed place (focus drawn from due ∪ fresh ∪ settled); KDoc corrected; the dead State.sent removed with its KDoc; word-less = synthetic ids only (deleted words stay 'words').
Final fix wave DONE (8b7ab26..5cda187, 8 commits; JVM 706, connected 139 with the server). Scoped re-review dispatched (opus); controller runs the JVM + server suites on the final tree in parallel.
Final fix wave DONE (8b7ab26..5cda187, 8 commits; JVM 706, connected 139 with the server). Scoped re-review dispatched (opus); controller runs the JVM + server suites on the final tree in parallel.
Fix-wave re-review: 4 open (a focus word already inside the due cut wastes a reserved slot; MAX_RESTARTS 6 gives 10–14 s not 20; per-letter ink never reaches a row; Detail.kept does not walk nested objects). Fix round 2 dispatched — BASE 5cda187, resuming the fix-wave implementer a97f16c2b830a0f99.
Fix round 2 DONE (12278f3, bd71853, 99a9be8, 9060333; JVM 709, connected 139 with the server). Final scoped re-review (sonnet) then close: execution record, tag v0.2.0.
Final re-review clean. Phase 11 closed at 02:15 on 2026-09-07.
