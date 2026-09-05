# Dimitris' App — Phase 8 (Right-Hand Arcade) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fine-motor practice for the weak right hand, framed as four short games: tap the target, trace the path, drag the puck home, pinch a photo. Targets shrink as he improves and grow back on misses. Off by default until his physio agrees.

**Architecture:** Pure `Adaptive` (size rule) + `TargetPlacer` (positions) with tests; four game composables over Compose gestures; an `ArcadeModule` that runs the four games in sequence and logs one attempt per game. Difficulty persists in `Settings.arcadeTargetDp`.

**Tech Stack:** as before; `detectTapGestures`, `detectDragGestures`, `detectTransformGestures`; reuses `TraceScorer` from phase 7.

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§7 phase 8)

## Global Constraints

- Greek-only UI; no timers (targets stay until tapped; nothing disappears on a clock); success = icon + sound + haptic; misses get a soft nudge only.
- The screen says "Δεξί χέρι" at the top, always.
- Adaptive size: start 96 dp; hit → ×0.92 (min 40 dp); miss → ×1.15 (max 130 dp). Persisted between sessions.
- Rounds: tap 12 targets; trace 4 paths; drag 5 pucks; pinch 3 photos. Attempt per game: `itemId = "arcade:<tap|trace|drag|pinch>"`, `module = ARCADE`, `cueLevel = null`, `detail` `{hits, misses, sizeDp}`; CORRECT when hits ≥ 70 % of tries, else ASSISTED; SKIPPED on skip.
- ARCADE is excluded from the default `enabledModules` (caregiver switches it on after the physio's word) — a controller ruling; the settings row says "Ενεργοποίησέ το αφού μιλήσεις με τον φυσιοθεραπευτή."
- Commits `feat(phase8): ...` with the Co-Authored-By trailer; conventions as previous phases.

---

### Task 1: Adaptive size and target placement (pure)

**Files:** create `modules/arcade/Adaptive.kt`, `TargetPlacer.kt`; tests `AdaptiveTest.kt`, `TargetPlacerTest.kt`; modify `core/settings/Settings.kt` (`arcadeTargetDp` Float default 96f clamped 40..130; `enabledModules` default now `ModuleId.entries - ARCADE` unless the caregiver explicitly enabled it — implement as a separate `ENABLED_ARCADE` boolean key defaulting false, folded into `enabledModules`; `setModuleEnabled(ARCADE, on)` writes that key) + `SettingsTest` cases.

- [ ] `object Adaptive { const val START = 96f; const val MIN = 40f; const val MAX = 130f; fun afterHit(size: Float) = (size * 0.92f).coerceAtLeast(MIN); fun afterMiss(size: Float) = (size * 1.15f).coerceAtMost(MAX) }` with tests for both directions and both clamps.
- [ ] `class TargetPlacer(random) { fun next(width: Float, height: Float, sizePx: Float, previous: Pt?): Pt }` — inside bounds with a margin of `sizePx`, and at least `2 * sizePx` away from `previous` when possible (test: 200 placements all inside bounds; consecutive distance ≥ 2·size in ≥ 95 % of cases).
- [ ] Commit `feat(phase8): adaptive sizing and target placement`.

---

### Task 2: Games, module, screen, settings row

**Files:** create `modules/arcade/TapGame.kt`, `TracePathGame.kt`, `DragGame.kt`, `PinchGame.kt`, `ArcadeModule.kt`, `ArcadeViewModel.kt`, `ArcadeScreen.kt`; modify `AppGraph.kt` (`modules += ArcadeModule`), `caregiver/SettingsScreen.kt` (physio note under the ARCADE switch).

- [ ] Each game is a composable `XGame(sizeDp: Float, onResult: (hits: Int, misses: Int, newSizeDp: Float) -> Unit, onSkip: () -> Unit)` that owns its round and calls `onResult` once at the end:
  - **TapGame**: draws one filled circle (secondary colour) at a placed position; `detectTapGestures` on the full canvas: inside radius → hit (success feedback, `Adaptive.afterHit`), outside → miss (nudge, `Adaptive.afterMiss`, the target stays); 12 targets.
  - **TracePathGame**: 4 generated polylines (horizontal line, diagonal, zigzag, arc sampled) drawn with a dotted stroke; a drag is scored with `TraceScorer.score(..., maxMeanFraction = 0.12f, minCoverage = 0.5f)`; pass = hit.
  - **DragGame**: a puck (circle, `sizeDp`) and a home zone (ring, `1.6 × sizeDp`) placed apart; `detectDragGestures` moves the puck; release inside the ring = hit; 5 rounds.
  - **PinchGame**: one of his item photos (random `imagePath != null`, resolved through `graph.files.resolve`, else the app's ARASAAC pictogram of "μπάλα") with `detectTransformGestures` scaling 1×–3×; reaching ≥ 2× then returning ≤ 1.2× = hit; 3 rounds; instruction "Άνοιξε με δύο δάχτυλα, μετά κλείσε."
- [ ] `ArcadeModule`: id ARCADE, title "Δεξί χέρι", icon `Icons.Rounded.BackHand`; `planFor` returns 4 placeholder items (sizing) only when ARCADE is enabled (it is filtered by `enabledModules` anyway); `Screen` → `ArcadeScreen(sessionId, onDone)`.
- [ ] `ArcadeViewModel(graph, sessionId)`: loads `arcadeTargetDp`; `games = listOf("tap","trace","drag","pinch")`; on each result: persist the new size, insert the attempt per Global Constraints, success/nudge feedback, advance; end screen "Τέλος για σήμερα. Μπράβο το δεξί!".
- [ ] `ArcadeScreen`: title "Δεξί χέρι", subtitle per game ("Πάτα τον κύκλο", "Ακολούθησε τη γραμμή", "Σύρε τη μπάλα στο σπίτι της", "Άνοιξε με δύο δάχτυλα"), the game fills the middle, bottom `QuietButton("Παράλειψη")`.
- [ ] Settings: under the ARCADE switch add the physio note text.
- [ ] Build, unit tests, install; play each game once with `adb shell input tap/swipe`; commit `feat(phase8): right-hand arcade`.

---

### Task 3: Phase 8 verification

- [ ] Full suites green; on the phone enable "Δεξί χέρι" in settings, play a full round with the right hand, confirm the target visibly shrinks after hits; Σφάλματα empty. Append notes; commit `docs(phase8): verification notes`.
