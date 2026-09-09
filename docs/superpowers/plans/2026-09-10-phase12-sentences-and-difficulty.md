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
