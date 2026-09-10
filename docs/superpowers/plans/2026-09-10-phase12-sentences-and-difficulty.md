# Dimitris' App — Phase 12 (Harder, and about sentences) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Turn Dimitris' own verdict ("too easy; I need sentences and bigger numbers") into the app's centre: on-device transcription with one speech control, a Claude turn judge, sentence expansion on the talk board and in dialogues, a difficulty ladder he sets himself, numbers to 1000+, harder sentences, open dialogues.

**Architecture:** `core/speech` gains an on-device recogniser with model download and a recorder-fed audio source; `core/judge/TurnJudge` (Claude Haiku) with a local fallback; `core/difficulty/Difficulty` read by every module's planner and shown by one shared `DifficultyRow`; module content grows behind the ladder. No new navigation depth.

**Tech Stack:** as before. `SpeechRecognizer` on-device APIs (API 33+), `AudioRecord`, Anthropic Java SDK (already present).

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§13)

## Global Constraints

- Greek-only; ≥ 72dp; no timers; errorless speech; shape-judged writing; adult tone.
- **UX simplicity (Chris, binding):** one primary action per screen; at most three actions in the bottom area (primary + secondary + quiet); no new navigation depth; every activity is a tile; difficulty is one shared `DifficultyRow` (five dots) at the top of a module's first screen, nothing else.
- **Privacy:** per-turn judging is text-only and only when `Settings.claudeJudging` is on AND a key exists; nothing about his health beyond the spec's §1 profile in any prompt; the sensitive detail Chris shared is never written anywhere.
- Claude models: judging `claude-haiku-4-5-20251001` (maxTokens 300, timeout 8 s, `maxRetries(1)`), advice `claude-opus-5` as today. Every judge call and its outcome recorded in `detail.judge` (accept, latency ms, fallback used).
- Transcription: on-device recogniser first (API 33+), network recogniser as fallback, honest Greek lines per error class; the restart loop stays (free on-device).
- Every Room change additive with schema, migration test, sync registry; DB is v7 now.
- Conventions of phases 0–11 (module contract, writes on `graph.scope`, `leave(then)`, Greek error slot, `ANDROID_SERIAL=emulator-5554`, `pm clear` before connected runs). Commits `feat(phase12): ...` with the trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

### Task 1: On-device recognition, honest errors, one speech control

**Files:**
- Modify: `core/speech/{AndroidSpeechToText,Recognition,RecognizerIntents,SpeechToText}.kt`, `core/audio/{Recorder,Voice}.kt`, `caregiver/SettingsScreen.kt`, `core/settings/Settings.kt`, the three speech modules' screens/ViewModels (word coach, dialogues, sing-then-say)
- Create: `core/speech/OnDeviceSupport.kt` (pure decision table), `core/speech/PcmTake.kt` (AudioRecord → WAV file + pipe)
- Test: `OnDeviceSupportTest`, `RecognitionTest` (+ error classes), instrumented `SpeechSettingsTest` (the row states on an engine-less emulator), flow tests updated

**Interfaces:**
- `OnDeviceSupport.decide(sdk: Int, onDeviceAvailable: Boolean, installedLanguages: List<String>, supportedLanguages: List<String>, online: Boolean): Engine` where `Engine = ON_DEVICE | NEEDS_DOWNLOAD | NETWORK | NONE`.
- `AndroidSpeechToText`: on API ≥ 33 `SpeechRecognizer.isOnDeviceRecognitionAvailable(ctx)` → `createOnDeviceSpeechRecognizer(ctx)`; `checkRecognitionSupport(intent, executor, callback)` → installed/supported lists; `triggerModelDownload(intent[, executor, listener])` from the settings row; else the network recogniser. Error classes: 2/1 → «Χρειάζεται σύνδεση για την αναγνώριση.»; 12/13 → «Λείπουν τα ελληνικά. Κατέβασέ τα από τις ρυθμίσεις.»; 8/10 → «Η αναγνώριση είναι απασχολημένη. Δοκίμασε σε λίγο.»; others → the existing line. Each class recorded once per run.
- Settings → «Αναγνώριση ομιλίας»: state line «Ελληνικά χωρίς σύνδεση: εγκατεστημένα / δεν υπάρχουν / λήψη… / δεν υποστηρίζεται» + `BigButton("Λήψη ελληνικών")` when NEEDS_DOWNLOAD; the toggle as today.
- One control (API ≥ 33 with ON_DEVICE): «Μίλα» starts `PcmTake` (AudioRecord 16 kHz mono PCM16 → WAV in `recordingsDir` AND a `ParcelFileDescriptor` pipe passed as `RecognizerIntent.EXTRA_AUDIO_SOURCE` with `EXTRA_AUDIO_SOURCE_CHANNEL_COUNT = 1`, `EXTRA_AUDIO_SOURCE_ENCODING = ENCODING_PCM_16BIT`, `EXTRA_AUDIO_SOURCE_SAMPLING_RATE = 16000`); the same audio becomes the transcript and the saved take (`Recording` with `who = DIMITRIS`); «Άκου» after a take plays the model then his take back to back; the separate «Ηχογράφηση»/«Σύγκριση» controls disappear. Below API 33 or without the engine: «Μίλα» = recogniser only, the take button stays as today. Bottom area everywhere: «Μίλα» (primary), «Άκου» (secondary), «Παράλειψη» (quiet).

- [ ] Tests first (decision table incl. Chris's codes 12 and 2; error classes; intents with the audio-source extras).
- [ ] Implement; on the emulator (no engine): the settings row says «δεν υποστηρίζεται», «Μίλα» falls back to the network path and shows the honest line; the WAV take is playable; commit `feat(phase12): on-device recognition and one speech control`.

---

### Task 2: The turn judge (Claude Haiku) with a local fallback

**Files:**
- Create: `core/judge/{TurnJudge,JudgeContract,LocalJudge}.kt`
- Modify: `core/settings/Settings.kt` (+ `claudeJudging` Boolean, default false), `caregiver/SettingsScreen.kt` (Claude section: toggle «Έλεγχος με Claude» with the line «Στέλνει μόνο κείμενο: την ερώτηση, τον στόχο και ό,τι είπε.»), `AppGraph.kt` (+ `judge`)
- Test: `JudgeContractTest` (prompt/JSON parse), `LocalJudgeTest`, `TurnJudgeTest` (fake client; fallback on failure/timeout/no key/off)

**Interfaces:**
- `enum class Kind { WORD, SENTENCE, DIALOGUE, EXPAND }`; `data class Ask(kind, prompt: String?, target: String?, heard: String, difficulty: Int)`; `data class Verdict(accept: Boolean, expanded: String?, feedback: String?, score: Float, source: JUDGE|LOCAL)`.
- `TurnJudge.judge(ask): Verdict` — never throws; with `claudeJudging` off or no key → `LocalJudge` (`SpeechMatch`/`phraseMatches`; EXPAND returns `heard` unchanged); with the key: one Haiku call, system prompt (Greek, the §1 profile only, the rules: accept any relevant sensible answer in a dialogue; for WORD/SENTENCE accept the target or a close form; `expanded` = the full grammatical sentence when `heard` is telegraphic, else null; `feedback` ≤ 12 words, kind, never «λάθος»), user message = the ask as JSON, answer = one JSON object; timeout 8 s → LOCAL.
- `detail.judge = {source, accept, ms, expanded?}` written by the callers (Task 3, 6, 7).

- [ ] Tests first; implement; `./gradlew -q testDebugUnitTest`; commit `feat(phase12): a turn judge with a local fallback`.

---

### Task 3: Sentence expansion on the talk board

**Files:**
- Modify: `modules/talkboard/{TalkBoardViewModel,TalkBoardScreen,SentenceStrip}.kt`
- Test: `TalkBoardViewModelTest` (with a fake judge), instrumented `TalkBoardScreenTest` (+ the flow with the fake)

- [ ] The strip gains ONE button «Ολόκληρη» (secondary, enabled with ≥ 2 words): `judge(EXPAND, heard = the strip words joined)` → the expanded sentence replaces the strip text (the words stay as chips underneath), is spoken, and a «Πες το» primary appears that opens «Μίλα» (Task 1) to repeat it; the attempt is recorded (`module = TALKBOARD`, `detail {kind: "expand", words, expanded, judge}`; `cueLevel = 3` since he heard it). Without the judge (off/offline) the button is hidden. No other change to the board.
- [ ] Commit `feat(phase12): the talk board says the whole sentence`.

---

### Task 4: The difficulty ladder

**Files:**
- Create: `core/difficulty/Difficulty.kt`, `ui/components/DifficultyRow.kt`
- Modify: `core/settings/Settings.kt` (+ `difficulty(module)` 1..5 default 2, `difficultyFloor/Ceiling(module)` default 1/5), `modules/Module.kt` (+ `planFor(graph, difficulty)` default delegating), every module's first screen (the row at the top, below the title), `today/PracticeViewModel.kt`, `today/SessionViewModel.kt` (sessions use his current difficulty), `caregiver/SettingsScreen.kt` («Όρια δυσκολίας» per module: two steppers)
- Test: `DifficultyTest` (clamp to bounds; setting persists), instrumented `DifficultyRowTest` (72dp dots, tap sets within bounds)

- [ ] `DifficultyRow(value, floor, ceiling, onChange)`: five 72dp dots in a row with the label «Δυσκολία» left; dots outside the bounds are dimmed and inert; tapping sets `Settings.setDifficulty(module, n)` immediately; a short Greek line under it only when at a bound («Ο φροντιστής έβαλε όριο.»).
- [ ] Mapping in THIS task (content arrives in Tasks 5–7; here the mapping only selects among what exists): word coach → seed tiers (1–2 today; 3–5 map to the same until phase 13), numbers → level bands (1: levels 1–2, 2: 3–4, 3: 5–7, 4: 8–11, 5: 12–15 once Task 5 lands), sentences → level bands, dialogues → script tiers (Task 6), trace → 1 letters, 2 lowercase, 3 name, 4 words, 5 recall, sing-then-say → phrase length. The existing automatic progressions keep moving WITHIN the band.
- [ ] Commit `feat(phase12): a difficulty he sets himself, within the caregiver's bounds`.

---

### Task 5: Numbers to 1000 and beyond (levels 8–15)

**Files:**
- Modify: `modules/numbers/{Exercises,ExerciseGenerator,ExerciseViews,NumbersViewModel,NumberProgression}.kt`, `core/greek/{GreekNumbers,Euro}.kt` (words to 9999; euro change)
- Test: `ExerciseGeneratorTest` (+ per level: always one correct option, plausible distractors, ranges), `GreekNumbersTest` (+ to 9999), `EuroTest` (+ change)

- [ ] Levels: 8 add/sub without carry to 100; 9 add/sub with carry to 1000; 10 multiplication tables 2–10; 11 division (exact) and missing operand (`__ × 4 = 32`); 12 change from a note («Πληρώνεις 20 €, κοστίζει 13,40 €. Ρέστα;» with coin/note options); 13 clock (analogue face drawn, «Τι ώρα είναι;» options) and dates («Τι μέρα είναι μετά από 3 μέρες;»); 14 number words ↔ digits to 9999 (both directions); 15 two-step word problems (spoken and shown: «Έχεις 3 κουτιά με 6 αυγά. Σπάνε 4. Πόσα μένουν;» options). Every exercise keeps the option/tap interaction (no typing of digits — he cannot write digits; options and the number line), the prompt spoken, ~4 options, the nudge-then-reveal rule from phase 3.
- [ ] `NumberProgression` range 1–15; the difficulty bands from Task 4.
- [ ] Commit `feat(phase12): numbers to a thousand and beyond`.

---

### Task 6: Open dialogues and harder scripts

**Files:**
- Modify: `modules/scripts/{ScriptsViewModel,ScriptsScreen}.kt`, `core/data/Entities.kt` (`ScriptLine` + `tier` Int default 1, `intent` String? — additive, DB v8 + schema + migration test + sync registry), `core/seed/ScriptSeed.kt`, `assets/seed/scripts.json` (+ 8 harder dialogues, tier 3–5: two-step questions, a phone call, an appointment, a complaint at a shop, giving directions, telling what happened yesterday, planning tomorrow, ordering for two, asking for help with a form)
- Test: `ScriptsViewModelTest` with a fake judge (accept, nudge + expanded, second try, skip), `MigrationTest` 7→8, seed tests

- [ ] His line is a `target` (sample answer) plus an `intent` (what a good answer must convey, e.g. «λέει τι θέλει να πιει και πόσο»). With the judge on: «Μίλα» → transcript → `judge(DIALOGUE, prompt = the other side's line, target, heard)` → accept: success + advance; not accepted: the nudge with `feedback` and, when `expanded` exists, the full form shown and spoken («Πες το έτσι: …») for him to repeat once; then he may confirm as today. With the judge off: the phase-11 gentle check on the target. Tier by difficulty (Task 4).
- [ ] Commit `feat(phase12): dialogues take any good answer, and get harder`.

---

### Task 7: Harder sentences, gap-filling, typed sentences

**Files:**
- Modify: `modules/sentences/{SentenceTemplates,SentencesViewModel,SentencesScreen}.kt`, `core/greek/{Accusative,Greek}.kt` (articles, prepositions helpers)
- Test: `SentenceTemplatesTest` (+ levels 5–8 grammatical output over the seed pool, 500 boards), `SentencesViewModelTest` (typed path with a fake judge)

- [ ] Levels 5 (articles: «ο πατέρας πίνει τον καφέ»), 6 (prepositions + place: «πάω στο φαρμακείο με το λεωφορείο»), 7 (a clause: «θέλω να φάω γιατί πεινάω»), 8 (a question: «πού είναι το φαρμακείο;») — tiles include the function words; a gap-fill variant («__ φαρμακείο» with 3 function-word options) at levels 5–6; a typed variant at levels 7–8 (a picture + «Γράψε την πρόταση»: keyboard, judged by `judge(SENTENCE)`; accepted or nudged with the expanded form; CORRECT/ASSISTED as the builder). Keep the board layout (three per row, one primary).
- [ ] Commit `feat(phase12): sentences with the small words in them`.

---

### Task 8: Prompt, docs and the UX audit

- [ ] `ClaudeAdvisor` system prompt: the §13 profile (stronger; telegraphic speech; sentences are the goal) — nothing beyond §1/§13; `JourneyReport` adds the difficulty per module and the judge outcomes (accept rate, expansions) to «Ανά άσκηση».
- [ ] UX audit against the simplicity rule on every screen: list each screen's bottom actions in `docs/UX.md` (new) and fix any screen with more than three; the module grid at 2 columns; «Τραγούδα» hidden unless enabled (Settings toggle, default off from now on — an existing install keeps its current state).
- [ ] `docs/HANDOVER.md` phase 12 section; `docs/ADAPTATION.md` for the new keys (`judge`, `difficulty`); README row.
- [ ] Commit `chore(phase12): prompt, UX audit, docs`.

---

### Task 9: Verification and release

- [ ] Full suites green (JVM, connected after `pm clear` with the sync server, server); on the emulator: the settings row states, a talk-board expansion with the judge FAKED (no key on the dev machine — the real call is Chris's check), difficulty dots bounding a module, numbers level 12 change and level 15 word problem, an open dialogue with the fake judge, a typed sentence; tag `v0.3.0`; verification notes; commit `docs(phase12): verification notes`.
- [ ] Chris: the Greek pack download on his phone; «Μίλα» transcribing and saving the take; a real expansion on the talk board with a key; an open dialogue; difficulty 4–5 in numbers and sentences with Dimitris.

## Verification notes (2026-09-10)

Final tree `81d166b`: 1104 JVM tests (2 skipped: the live Claude calls, no key on the dev machine), 189 instrumented on the emulator from a fresh install with the sync server on 18787, 29 server tests. Every device check ran on `emulator-5554`, which has no speech engine: on-device recognition, the recorder-to-recogniser pipe, and every new spoken prompt are verified by decision tables and tests, not by ear. Only a human can verify: the Greek pack download and «Μίλα» transcribing + saving a take on the Samsung, a real Haiku judge call (`TurnJudgeLiveTest`), a real expansion on the talk board, an open dialogue, difficulty 4–5 in Αριθμοί and Προτάσεις with Dimitris, and the numbers 8–15 prompts by ear.

## Execution record (controller rulings, 2026-09-10)

Copied from the SDD ledger at phase close.

## Pre-flight conflict scan (2026-09-10)

| Tasks | Shared surface | Produces vs consumes | Finding |
|---|---|---|---|
| 1 / phase 11 | AndroidSpeechToText restart loop, Recognition classes, GentleCheck, the three speech screens' bottom rows | Task 1 replaces «Ηχογράφηση»/«Σύγκριση» with the one-control rule; GentleCheck unchanged | Ruling: the bottom area is exactly «Μίλα» / «Άκου» / «Παράλειψη» on API ≥ 33 with the engine; the take button survives only on the fallback path — cost: none |
| 1 alone | EXTRA_AUDIO_SOURCE pipe + WAV take share one AudioRecord | recognizer reads the pipe, the file gets the same bytes | Ruling: a single PcmTake writer thread fans out to both; if the recognizer closes the pipe early the file still completes — cost: none |
| 1 / phase 1 | Recording.path relative under recordingsDir; WAV instead of m4a | Voice.play must play WAV (MediaPlayer does) — the sync media extension map (phase 10) must accept .wav | Ruling: MediaPaths accepts wav; test |
| 2 / 3, 6, 7 | TurnJudge.judge(Ask): Verdict; Settings.claudeJudging | consumers use the exact names | consistent |
| 2 / phase 9 | SecretStore key shared; AdviceSession one-at-a-time guard is for advice only | judge calls are short and concurrent-safe (new client per call) | consistent |
| 3 / phase 1 | SentenceStrip layout; the board's bottom area | one new secondary button only | consistent with the UX rule |
| 4 / all | Module.planFor(graph, difficulty); each first screen gains DifficultyRow | SessionViewModel passes Settings.difficulty(module) | Ruling: the row appears only on the module's FIRST screen (not inside a session run: sessions use the stored value) — keeps the session screen unchanged |
| 4 / phases 3, 6, 7 | existing automatic progressions vs bands | progressions move within the band; the band comes from the dots | Ruling: when the dots change, the level jumps to the band's lowest level |
| 5 / phase 3 | NumberProgression MAX 7 → 15; ExerciseViews for clock/word problems | new views keep OptionButton; no digit typing | consistent |
| 6 / phase 5 | ScriptLine gains tier + intent (DB v8, additive); seed v3 scripts | ScriptSeed dedups by title; new titles only | consistent; MigrationTest 7→8; sync registry sample |
| 6 / 2 | judge off → the phase-11 gentle check | consistent |
| 7 / phase 6 | SentenceTemplates levels 5–8; LevelProgression max 4 → 8 | Settings.sentencesLevel clamp 1..8 | consistent |
| 8 / phase 9 | ClaudeAdvisor prompt update | §1 + §13 only; never the sensitive detail | Ruling: the reviewer of Task 8 greps the prompt for anything beyond the two spec sections |
| all | UX rule (≤ 3 bottom actions, one primary) | Task 8 audits every screen | consistent |

Scan result: five rulings carried into dispatches.

## Task log
Task 1: dispatched — BASE b487528, model opus
Chris (03:04): the bug was flight mode — codes 2 and 12 were both 'no network'; the recogniser works fine online and understands Greek. Task 1 keeps its scope (the honest 'no connection' line is the real fix; on-device + one control are still wanted). Directive: build everything autonomously (phases 12 and 13).
Chris (03:17, going to bed): when phase 12 is ready, push and tag v0.3.0 so he can download it in the morning; keep going with phase 13 afterwards.
Task 1: implementer DONE (c52a455, ae80d01; JVM 763, connected 158). Accepted: «Βοήθεια» moved beside the word (UX rule), sing-say tap stages keep four actions until Task 8, the pipe is unproven until Chris's phone. Ruling for its fix round: a synced .wav is named by sniffing the RIFF header on the receiver (no wire change). Review dispatched (opus); Task 2 dispatched in parallel — BASE ae80d01, model opus.
Task 1: review DONE (1 Critical / 3 Important / 6 Minor). Rulings for its fix round (after Task 2 lands): the pipe gets its own writer thread fed from a bounded queue that drops the oldest buffer when full — the mic thread only ever writes the file; «Στοπ» closes the pipe's write end; take start/cancel/join off the main thread; retention: only the newest 3 of his takes per item are kept (older WAVs + rows soft-deleted, synced); RIFF sniffing for synced .wav names; SILENCE_PEAK recalibrated for raw PCM (state the number); minors.
Task 2: implementer DONE (4269751; JVM 810 + 2 skipped). Rulings for its fix round: maxRetries(0) for the judge (one 8 s attempt, then local), maxTokens 400, the «Μοντέλο» helper line says it is the advisor's model. Review dispatched (opus); Task 1 fix round 1 dispatched — BASE 4269751, resuming implementer ae08234d2b70fe09a.
Task 2: review DONE (0 Critical / 4 Important / 11 Minor; privacy clean). Rulings for its fix round (after Task 1's fix lands): feedback enforced (≤ 12 words, the two banned words dropped → null); blank heard → local without a call; Verdict.detail(ms) helper documenting detail.judge; maxRetries(0), maxTokens 400, model helper line; minors at judgement. The live Haiku test runs once when Chris supplies a key (noted for the handover).
Task 1: fix round 1 DONE (06806e3, ebb128b, 5d6b1a5; JVM 825, connected 160). Accepted: SILENCE_PEAK_PCM 600 reasoned; dangling selfRecordingId after pruning degrades gracefully. Scoped re-review dispatched (opus); Task 2 fix round 1 dispatched — BASE 5d6b1a5, resuming implementer a1545b1ddbc08d459.
Task 1: re-review — 6 open (1 Important: a cancellation during startTake orphans the AudioRecord; 5 Minor). Rulings for round 2 (after Task 2's fix): take start under NonCancellable with the handle stored before any suspension; stopped cleared before engine(); pipe-close identity check; the end-of-stream marker is never dropped by drop-oldest (a flag, not a queue item); release after the join, with the timeout documented; the sync engine removes media files for recording rows pulled as deleted (receiver-side sweep) and the report corrected.
Task 2: fix round 1 DONE (e303147; JVM 840 + 2 skipped). Rulings carried: Tasks 3/6/7 call judge.newRun() in their ViewModel init and write detail.judge via a new sanctioned Adapt.Detail.put(key, Map) (Task 3 adds it with a test); the advisor's first-to-last-brace JSON scan is fixed in Task 8. Scoped re-review dispatched (sonnet); Task 1 fix round 2 dispatched — BASE e303147, resuming implementer ae08234d2b70fe09a.
Task 2: complete (4269751..e303147, re-review clean; one stale KDoc line noted for Task 3's implementer to fix in passing).
Task 1: fix round 2 DONE (f15c369, 08a7457, 4746760; JVM 847, connected 165). Scoped re-review dispatched (opus — the receiver-side file sweep gets a second pair of eyes). Task 3 dispatched in parallel — BASE 4746760, model opus.
Task 1: re-review 2 — 5 open (1 Important: the NonCancellable block still loses the handle on exit; the stop claim races engine(); writing flag under the lock; the sweep is unreachable because the sender deletes the file before the deletion is pushed; mediaStillUsed only checks recordings). Rulings for round 3 (after Task 3 lands): assign the handle INSIDE the NonCancellable block and test across dispatchers; the stop is claimed before engine() and a stop during engine() is kept; writing under pipeLock; retention keeps the sender's file until its deletion row has been pushed (pruned files are removed after the next successful push) and the receiver's sweep resolves by sha; mediaStillUsed checks every table's media columns; the NONE path honours stopped.
Task 3: implementer DONE (d9599ee; JVM 865, connected 168). Rulings for its fix round: the expansion primary is «Μίλα» (one word everywhere), TurnJudge.available() replaces the duplicated check, cueLevel KDoc, talkboard:expand excluded from mostUsed. Review dispatched (opus); Task 1 fix round 3 dispatched — BASE d9599ee, resuming implementer ae08234d2b70fe09a.
Task 3: review DONE (0 Critical / 4 Important / 9 Minor; ✅). Rulings for its fix round (after Task 1 round 3 lands): I1 the bottom «Πες το» re-speaks the open expansion instead of closing it; I2 the board's mean help counts only rows with a cue (mapNotNull); I3 an echo on EXPAND means 'already whole' (shown as is, no error row); I4 the sentence line gets its own weight/colour; plus the earlier four (expansion primary «Μίλα», TurnJudge.available(), cueLevel KDoc, talkboard:expand out of mostUsed); minors at judgement.
Task 1: fix round 3 DONE (564bed3, 758ae26, 614747a; JVM 883, connected 168; two extra real defects found by the new end-to-end tests). Accepted: up to four takes per word until the next push. Scoped re-review dispatched (opus); Task 3 fix round 1 dispatched — BASE 614747a, resuming implementer a5fd393aaf5e3c1a4.
Task 1: re-review 3 — 3 Minor open (writing flag set at the end of the block; PendingRemovals.clear() never called and release not re-checking live rows after a backup import; the 7-day fallback gated on configured()). Ruling: all three into the phase's final fix wave (round cap reached for this task). Task 1 complete (c52a455..614747a).
Task 3: fix round 1 DONE (f666d9f; JVM 888, connected 169). Accepted: M2/M5 left; the board title and the primary both read «Μίλα» (Chris's eye). Scoped re-review dispatched (sonnet); Task 4 dispatched — BASE f666d9f, model opus.
Task 3: complete (d9599ee..f666d9f, re-review clean).
Task 4: implementer DONE (fc58841; JVM 929, connected 175). Ruling for its fix round: the initial difficulty of each module is derived once from his stored level (the band that contains it), never a flat 2 that would drop a phone already at numbers level 7. Accepted: trace has no automatic progression within a one-level band; arcade size band; 72dp-tall dot cells. Review dispatched (opus); Task 5 dispatched in parallel — BASE fc58841, model opus.
Task 4: review DONE (1 Critical / 5 Important / 8 Minor). Rulings for its fix round (after Task 5 lands): C1 the stored level is clamped INTO the band once at load (honest jump), and a sitting's progression result is clamped to the band — a failed sitting can never raise the level; I1 a moved bound clamps, never resets; I2 sing-say and dialogue bands are ceilings (cumulative), never windows that hide easier items; I3 the arcade's initial difficulty derives from its stored sizes; I4 tests for the session pass-through, the re-plan and the index guard; I5 a tap on the current dot is a no-op; minors: nudge haptic on a refused tap, the row hidden on caregiver «Δοκίμασέ το» runs, selected/disabled semantics; the two tests pinning the temporary numbers clamp are replaced.
12:10: the Claude Code process restarted; Task 5's agent was interrupted mid-task with uncommitted numbers work in the tree (last write 08:58, incl. a scratch NumbersShotTemp.kt). Resuming the agent to finish and commit.
Task 5: implementer DONE after the restart (6b15da1; JVM 962, connected 177). Accepted: setNumbersLevel clamp lifted to MAX_LEVEL; OptionButton lines; level 13 five-minute face; level 12 amounts. Review dispatched (opus); Task 4 fix round 1 dispatched — BASE 6b15da1, resuming implementer ab16d03642a71d798.
Task 5: review DONE (1 Critical / 3 Important / 7 Minor; ✅ except level 15). Rulings for its fix round (after Task 4's fix lands): C1 level-15 answers are always positive (c < a + b) with a seed-swept test; I1 sayAnswer/reveal wrapped so no answer can crash the module; I2 feminine thousands (χίλιες, δύο χιλιάδες… διακόσιες) with the drift test to 9999; I3 per-test seeded Random; minors at judgement.
Task 4: fix round 1 DONE (b9ae7d3; JVM 994, connected 181). Ruling for the final wave: a phone with no stored progress starts at difficulty 1 (a fresh install must open at level 1 as before); existing phones derive from progress. Accepted: arcade derives from the biggest stored size; the migration racing the first screen by ms. Scoped re-review dispatched (opus); Task 5 fix round 1 dispatched — BASE b9ae7d3, resuming implementer a2be2db4a876f524e.
Task 4: re-review — 6 open (2 Important: a dot tap assigns arcade sizes instead of clamping; the one-shot init misses a restore/first sync; 4 Minor). Rulings for round 2 (after Task 5's fix lands): N1 the tap clamps sizes to the ceiling; N2 the derivation re-runs when dbGeneration changes (restore) and after the first successful pull, flags cleared then; N3 the flag is set only after a successful derivation; N4 setDifficulty sets the flag; N5 trace writes its load clamp back; N6 the sentences flow helper keeps a real assertion (a word not on the board fails at once).
Task 5: fix round 1 DONE (002eac2; JVM 1004, connected 181). Ruling for the final wave: audit every generator test in the repo for a Random shared across test methods (the class of defect that hid C1). Scoped re-review dispatched (sonnet); Task 4 fix round 2 dispatched — BASE 002eac2, resuming implementer ab16d03642a71d798.
Task 5: re-review — 2 informational open (buy-and-change range 1..40 unaudited; the clock stack scrolls on a 360x640 screen). Ruling: both into the final wave (audit the range with a test; the clock screen is fine to scroll on small phones — Chris's is 412x915). Task 5 complete (6b15da1..002eac2).
Task 4: fix round 2 DONE (5d00e08; JVM 1011, connected 181). Accepted: the difficulty_set key also set by followLevel; watch on dbGeneration as a process-long collector; fresh-install level 3 already ruled to difficulty 1 in the final wave. Scoped re-review dispatched (sonnet); Task 6 dispatched — BASE 5d00e08, model opus.
Task 4: complete (fc58841..5d00e08, re-review clean).
Task 6: implementer DONE (b8f93b5, 9b69631; JVM 1025, connected 184). Rulings for its fix round: an ACCEPTED telegraphic answer also gets the expanded form shown and spoken (spec §13); Ask gains an intent field and the judge is told it; the scripts seed importer updates tier/intent on seed lines whose text is unchanged (deterministic ids), never touching caregiver edits; accepted: detail.judge = the ending verdict; Difficulty.turns kept for the first-run guess. Review dispatched (opus); Task 7 dispatched in parallel — BASE 9b69631, model opus.
Task 6: review DONE (1 Critical / 3 Important / 5 Minor; Greek good). Rulings for its fix round (after Task 7 lands): C1 a LOCAL verdict never self-confirms a dialogue turn — it falls through to the phrase match (and LocalJudge's DIALOGUE rule becomes the phrase match, not accept-anything); I1 the prompt asks for expanded on a DIALOGUE refusal too; I2 the four over-demanding intents rewritten as answer spaces; I3 tests pin tier/intent through ScriptRepository.save and the editor; minors: the fifth tier chip at 72dp (weight), «Άκου» greyed while thinking, KDoc, editor re-open keeps the intent; plus the three earlier rulings (accepted answers get the expansion; Ask carries the intent; seed importer updates tier/intent on unchanged seed lines).
Task 7: implementer DONE (2d96fc9; JVM 1051, connected 186). Accepted: -α nouns without a known gender are skipped at 5–8 (phase 13: gender column); school «τον»; seven-card boards may scroll on small phones; duplicate «το» cards by design. Review dispatched (opus); Task 6 fix round 1 dispatched — BASE 2d96fc9, resuming implementer a14995c1b12d11925.
Task 7: review DONE (0 Critical / 4 Important / 9 Minor; zero wrong Greek forms over the seed). Rulings for its fix round (after Task 6's fix lands): I-1 «Το έγραψα» moves under the correction (three bottom actions); I-2 the field keeps focus and the keyboard through the judge call (re-request focus after a refusal); I-3 accented -ές plurals excluded (or given their plural form) — never an article guess; I-4 a level-8 typed board says «Γράψε μια ερώτηση: …» and the judge's target/prompt say so; minors at judgement.
Task 6: fix round 1 DONE (da84870; JVM 1064, connected 187; 17 intents rewritten). Scoped re-review dispatched (sonnet); Task 7 fix round 1 dispatched — BASE da84870, resuming implementer aac367141202109ba.
Task 6: complete (b8f93b5..da84870, re-review clean; one intent «απαντάει αν θέλει, και πότε» to soften in the final wave).
Task 7: fix round 1 DONE (1ec9c0e; JVM 1070, connected 188). Accepted: the refused typed board's field one flick up; MASCULINE_IN_ES closed list; M-9 «το» stays a wrong option. Scoped re-review dispatched (sonnet); Task 8 dispatched — BASE 1ec9c0e, model opus.
Task 7: complete (2d96fc9..1ec9c0e, re-review clean; waitForIme to skip when no soft keyboard is configured + the TypedCheck KDoc — into the final wave).
Task 8: implementer DONE (fb08ab1, 695bdc4; JVM 1092, connected 189; docs/UX.md; two of his screens brought to three actions). Accepted: no difficulty key in the focus JSON (his control); grandfathering by store emptiness (ordering documented). Review dispatched (opus, privacy grep of the prompt); the final fix wave follows with its findings.
Task 8: review DONE (0 Critical / 1 Important / 9 Minor; privacy clean). Task 8 complete pending the final wave. FINAL FIX WAVE dispatched — BASE 695bdc4, model opus: Task 8's items (typed-boards dot wording; grandfathering ignores device_role; the level-5 comment; UX.md omissions; jsonObject shared; HANDOVER header; opened-twice test; 'a module can be off' in the prompt) + Task 1's three minors (writing flag, PendingRemovals.clear/backup re-check, 7-day fallback without configured()) + fresh installs at difficulty 1 + shared-Random test audit + buy-and-change range test + the soft intent + waitForIme skip + TypedCheck KDoc.
Final fix wave DONE (5a718f4..81d166b, 9 commits; JVM 1104, connected 189, server 29). Accepted: per-phone fresh-install rule; lastSyncAt-keyed fallback; scripts seed bump 4 re-grades unedited seed lines. Scoped re-review dispatched (sonnet); controller runs the JVM + server suites on the final tree.
Final re-review clean. Phase 12 closed at 19:35 on 2026-09-10.
