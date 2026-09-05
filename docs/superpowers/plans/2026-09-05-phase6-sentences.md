# Dimitris' App — Phase 6 (Sentence Builder) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Word order practice for the agrammatism that comes with Broca's aphasia: two to four big picture tiles, tapped in order into a strip, spoken back as a sentence, with a distractor at the top level.

**Architecture:** Pure Kotlin `Greek.accusative` (rule-based object form), a `SentenceTemplates` generator over his own items (verb + object, subject + verb + object, + time word, + distractor), a shared `LevelProgression` (same rule as the numbers module), and a `SentencesModule` with strip + tile grid built on `PictureCard`. Attempts use the synthetic item id `sentences:level:N`.

**Tech Stack:** as before.

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§7 phase 6)

## Global Constraints

- Greek-only UI; 72dp; bottom actions; no timers; success = icon + sound + haptic; a wrong order gets a nudge, the correct sentence shown and spoken, then he retries (ASSISTED); first-try correct = CORRECT; skip = SKIPPED.
- Levels: 1 = verb + object (2 tiles); 2 = subject + verb + object OR "πάμε" + place (3 tiles); 3 = level-2 shapes + a time word (4 tiles); 4 = level 3 with one distractor tile. Level moves with `LevelProgression` (window 10, up at ≥ 80 %, down below 50 %).
- Objects are shown and spoken in the accusative produced by `Greek.accusative` (drop a final ς only for accented -ές/-ός/-ής/-άς and unaccented -ος/-ης/-ας singular endings; everything else unchanged).
- Attempts: `itemId = "sentences:level:N"`, `module = SENTENCES`, `cueLevel = null`, `detail` JSON `{tiles, chosen, firstTry}`.
- Commits `feat(phase6): ...` with the Co-Authored-By trailer; build/test conventions as previous phases.

- **Module contract (after the phase-2 fix wave):** `Module.Screen(items, sessionId, onDone, onLeave)` — `onDone` = all exercises finished, `onLeave` = the user pressed back (the screen's `onBack` calls `vm.leave(onLeave)`: the ViewModel cancels a running take, quiets the voice, joins its last app-scope write, then invokes `onLeave` — both callbacks are invoked only after the module's Attempt rows have landed). Attempt/schedule writes go on `graph.scope` (they must survive the screen); the last write is joined before `done` is published. Every module screen has `DisposableEffect(Unit) { onDispose { graph.voice.quiet() } }` semantics through the session/practice hosts. Sessions cap at 15 items across modules (`SessionBudget.allowance(n)`), so `planFor` lists may be truncated.

---

### Task 1: Accusative rule, templates, generic level progression (pure)

**Files:**
- Create: `core/greek/Accusative.kt` (extends `Greek` via a top-level `fun Greek.accusative(noun: String): String` in the same package), `core/scheduler/LevelProgression.kt`, `modules/sentences/SentenceTemplates.kt`
- Test: `core/greek/AccusativeTest.kt`, `core/scheduler/LevelProgressionTest.kt`, `modules/sentences/SentenceTemplatesTest.kt`

**Interfaces:**
- `Greek.accusative("καφές") == "καφέ"`, `("χυμός") == "χυμό"`, `("άντρας") == "άντρα"`, `("νερό") == "νερό"`, `("πατάτες") == "πατάτες"`, `("μπύρα") == "μπύρα"`.
- `object LevelProgression { fun next(level: Int, results: List<Boolean>, min: Int, max: Int, window: Int = 10, up: Double = 0.8, down: Double = 0.5): Int }` (same semantics as `NumberProgression.next`; leave `NumberProgression` as is).
- `data class Tile(val item: Item, val label: String)`; `data class Sentence(val level: Int, val tiles: List<Tile>, val distractor: Tile?) { val text: String = tiles.joinToString(" ") { it.label } }`; `class SentenceTemplates(random) { fun generate(level: Int, pool: List<Item>): Sentence?; fun session(level, pool, count = 8): List<Sentence> }`. Pool roles by category: subjects = PEOPLE items whose text is "εγώ" (the seed has it; if missing, level 2 uses only the πάμε shape); verbs = VERBS items with text "θέλω", "τρώω", "πίνω" (objects from FOOD/THINGS; "πίνω" only with FOOD items whose text is one of νερό, καφές, τσάι, γάλα, μπύρα, κρασί, χυμός); places = PLACES; times = TIME items in {τώρα, σήμερα, αύριο, μετά}; distractor = a random other item from FOOD/THINGS/PLACES not in the sentence. Returns null when the pool cannot fill the shape (the module then falls back to a lower level).

- [ ] Write the three failing test files (accusative cases above; progression cases mirroring `NumberProgressionTest` with min 1 / max 4; templates: level 1 has 2 tiles verb+object with the object label in the accusative, level 2 has 3 tiles starting with "εγώ" or "πάμε", level 3 ends with a time word, level 4 has a non-null distractor not among the tiles, "πίνω" never pairs with "ψωμί", `generate` returns null when the pool has no verbs, `session` returns the requested count skipping nulls).
- [ ] Implement, run `./gradlew -q testDebugUnitTest`, commit `feat(phase6): accusative rule, sentence templates, level progression`.

---

### Task 2: Module, ViewModel, screen

**Files:**
- Create: `modules/sentences/SentencesModule.kt`, `SentencesViewModel.kt`, `SentencesScreen.kt`
- Modify: `core/settings/Settings.kt` (+ `sentencesLevel` 1..4, default 1), `AppGraph.kt` (`modules += SentencesModule`)
- Test: `SettingsTest.kt` (+ case)

- [ ] `SentencesModule`: id SENTENCES, title "Προτάσεις", icon `Icons.Rounded.ShortText`; `planFor` returns up to 8 WORD items (only to size the session; sentences are generated); `Screen(items, sessionId, onDone, onLeave)` → `SentencesScreen(sessionId, onDone, onLeave)`.
- [ ] `SentencesViewModel(graph, sessionId)`: loads level and the pool (`activeOfKinds(WORD)`), generates 8 sentences (falling back to level−1 when `generate` returns null), state `{level, index, total, sentence, shuffledTiles, chosen: List<Tile>, correct: Boolean?, wrongTries, done, levelChanged}`; `tap(tile)`: appends and speaks the tile label via `graph.speaker.speakText`; when `chosen.size == tiles.size` compare labels in order: correct → success feedback, speak `sentence.text`, record CORRECT (first try) / ASSISTED; wrong → nudge, speak "Όχι έτσι. " + sentence.text, show the correct order for him to copy, clear `chosen`, `wrongTries++`; `undo()`, `skip()`, `next()`; end → `LevelProgression.next(level, results, 1, 4)` saved to `Settings.sentencesLevel`; attempts as in Global Constraints.
- [ ] `SentencesScreen`: title "Προτάσεις i/n"; strip (same look as the talk board strip) showing chosen labels; when wrong, a second line "Σωστά: <sentence>"; grid of `PictureCard`s (`imageFile = graph.files.resolve(imagePath)`, `label`) for `shuffledTiles` (+ distractor), tapped tiles disabled/dimmed; bottom: `BigButton("Επόμενο")` after success else `Row { QuietButton(backspace icon, undo), QuietButton("Παράλειψη") }`; end screen like the numbers module with the level message.
- [ ] Build, install, play; commit `feat(phase6): sentence builder module`.

---

### Task 3: Phase 6 verification

- [ ] Full suites green; on the phone build "θέλω καφέ", "εγώ θέλω νερό τώρα", handle a distractor; Σφάλματα empty. Append notes; commit `docs(phase6): verification notes`.


---

## Verification notes

Verified on 2026-09-06 against `emulator-5554` (the phone attached to this machine was never
addressed). Base `af3c3a0`; phase 6 is `00854c9`, `a23bd70`, `db6098f`, `56b227d`, and the fix wave
`a22080a`, `d1c8db8`.

### Suites

- `./gradlew -q testDebugUnitTest` — **245 tests, 0 failures** (212 before the phase).
- `adb shell pm clear gr.dimitris.app` then `./gradlew connectedDebugAndroidTest` —
  **51 tests, 0 failures** (47 before the phase). The four new ones are `SentencesFlowTest`:
  the right order recorded as CORRECT, a wrong order shown as «Σωστά: …» and recorded as ASSISTED
  with nothing written for the miss, the level-4 board carrying a card the sentence cannot use, and
  a device stripped of every word saying so and letting him out.
- `./gradlew installDebug` afterwards, so the emulator is left with the build these notes describe.

### On the device

| Check | What happened |
|---|---|
| «Προτάσεις» on Today | fifth tile, reachable without scrolling |
| Level 1 | «τρώω ζάχαρη», «θέλω νερό» — verb then object, object in the accusative |
| A wrong order | «Όχι έτσι.» spoken and written, «Σωστά: τρώω ζάχαρη» left on screen, cards handed back, unlimited tries |
| Level 3 | «εγώ τρώω σουβλάκι τώρα» — four cards, ending on the time word |
| Level 4 | «εγώ τρώω σαλάτα τώρα» with «τράπεζα» on the board: a bank is not something you eat, so the board has one answer and he found it first try. Five cards go three to a row, all above the fold |
| A finished board | every card dimmed, the odd one out included; only «Επόμενο» is live |
| «πάμε» sentences | γήπεδο, νοσοκομείο, φαρμακείο, καφετέρια, εκκλησία, σούπερ μάρκετ, μπάνιο, σπίτι, δουλειά, πάρκο — no room, no vehicle, no street |
| Levels | played up 1 → 2 → 3 → 4 in three sittings; each end screen announced the move («Ανεβαίνεις στο επίπεδο 2. Μπράβο!») |
| A session with Προτάσεις | two sentences done, back pressed mid-third: «Έκανες 2 ασκήσεις σήμερα: Προτάσεις.» — the count comes from the rows he left, and the last row was in the database before it was counted |
| Σφάλματα | «Κανένα σφάλμα. Ωραία.» — `error_logs` empty after 27 sentences |

Database after the run: attempts at `sentences:level:1..4` — the level actually played — with
`module = SENTENCES`, `cueLevel = null`, `detail {tiles, chosen, firstTry}`, the session rows
carrying their session id, and no `schedules` row for this module.

Screenshots: `.superpowers/sdd/2026-09-05-phase6-sentences/shots/`.

### Left as they are

- `planFor` returns eight placeholders on every device, so the module joins a session even where the
  vocabulary cannot fill a single sentence; the screen then hands straight back and the session
  counts the nothing he did. That is ruling I-F (sizing lists are transient and constant).
- `Greek.accusative` reads an unaccented «καφες» as a plural and would take the sigma off an oxytone
  feminine plural («φορές»). Neither form is in his vocabulary; the rule was swept over all 153 seed
  words and every form it produces is right.
- The level-1 board is in the answer's order about half the time. Any rule against that would be the
  giveaway in the other direction — "never the left one first" is a pattern he would learn instead
  of the sentence.

## Execution record (controller rulings, 2026-09-06)

Copied from the SDD ledger at phase close. Combined task + final review: 1 Critical / 2 Important / 6 Minor, all fixed or ruled; fix-wave re-review clean. Also carried into this phase: the cue ladder skips a rung that repeats the previous one (all modules), listen buttons disabled while recording.

## Pre-flight conflict scan (2026-09-05)

| Tasks | Shared surface | Produces vs consumes | Finding |
|---|---|---|---|
| 1 / phase 3 | LevelProgression "same semantics as NumberProgression" — NumberProgression was revised in the phase-3 fix wave (current-session results only, hold under 5 results, up ≥ 0.8, down ≤ 0.4) | plan text says window 10 / down < 0.5 | Ruling: LevelProgression keeps the plan's parameters (window, up = 0.8, down = 0.5) but the revised semantics: hold when fewer than 5 results, promote at ≥ up, demote at < down; results = this session only — cost if wrong: none |
| 1 / 2 | Tile(item, label), Sentence(level, tiles, distractor, text), SentenceTemplates.generate/session | ViewModel consumes exact names | consistent |
| 1 / seed | pool roles need seed items "εγώ" (PEOPLE), "θέλω/τρώω/πίνω" (VERBS), TIME words τώρα/σήμερα/αύριο/μετά, PLACES | seed has 175 words + 20 MIT phrases (phase 4) | implementer must verify the seed texts exist (case/accents) and add any missing ones to tools/seed/words.json in the same commit |
| 2 / phase 2+3 | Screen(items, …) — plan drops items (`SentencesScreen(sessionId, …)`) | SessionBudget truncates | Ruling: run exactly items.size sentences in a session (cap 8; free practice 8) — phase-3 ruling I1 |
| 2 / phase 3 | planFor "up to 8 WORD items" | phase-3 ruling I-F: sizing lists are transient and constant | Ruling: planFor returns 8 transient items always |
| 2 / phase 3 | end screen "like the numbers module" | numbers now auto-onDone in sessions unless the level changed | Ruling: same rule here |
| 2 / phase 3 | speech Results | must be surfaced in the error slot; graph.errors | Ruling carried (phase-3 rule) |
| 2 / phase 3 | Settings.sentencesLevel | same clamp pattern as numbersLevel | consistent |
| all | Greek-only, 72dp (PictureCard tiles), no timers, wrong = nudge + show + copy (no fail state) | consistent |

Scan result: four rulings carried into dispatches.

## Task log
Tasks 1+2: dispatched as one batch — BASE af3c3a0, model opus (with two carried fixes first: dead cue rung in CueLadder; listen button disabled while recording in scripts + word coach)
Tasks 1+2: implementer DONE (00854c9 cue rung, a23bd70 listen button, db6098f, 56b227d; JVM 243, connected 49). Deviations accepted pending review: plural -ες keeps sigma, window as takeLast, four vehicle PLACES excluded from the πάμε shape, verdict spoken instead of the last tile, PictureCard(enabled), repeatCue no-op while recording, 3-per-row level-4 board. Review dispatched (opus) — this review doubles as the task review; the final whole-phase review follows.
Review DONE (1 Critical / 2 Important / 6 Minor; all ✅). Fix wave — BASE 56b227d, resuming implementer a407ff1856db6ddcd (C1 distractor from the other filler set, I1 interior places excluded from πάμε, I2 progression on the level actually played + total honest, minors, Task 3 verification notes).
Fix wave DONE (a22080a, d1c8db8, 3e90466; JVM 245, connected 51; Task 3 verification notes committed). Scoped re-review dispatched (sonnet).
Fix-wave re-review: clean. Phase 6 closed — execution record commit deferred until the phase-7 implementer reports (no concurrent git writes in the worktree).
