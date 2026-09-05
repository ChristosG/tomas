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

- **Module contract (after the phase-2 fix wave):** `Module.Screen(items, sessionId, onDone, onLeave)` — `onDone` = all exercises finished, `onLeave` = the user pressed back (the screen's `onBack` calls the module's own cleanup then `onLeave`). Attempt/schedule writes go on `graph.scope` (they must survive the screen); the last write is joined before `done` is published. Every module screen has `DisposableEffect(Unit) { onDispose { graph.voice.quiet() } }` semantics through the session/practice hosts. Sessions cap at 15 items across modules (`SessionBudget.allowance(n)`), so `planFor` lists may be truncated.

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
