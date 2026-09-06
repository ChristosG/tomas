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

- **Module contract (after the phase-2 fix wave):** `Module.Screen(items, sessionId, onDone, onLeave)` — `onDone` = all exercises finished, `onLeave` = the user pressed back (the screen's `onBack` calls `vm.leave(onLeave)`: the ViewModel cancels a running take, quiets the voice, joins its last app-scope write, then invokes `onLeave` — both callbacks are invoked only after the module's Attempt rows have landed). Attempt/schedule writes go on `graph.scope` (they must survive the screen); the last write is joined before `done` is published. Every module screen has `DisposableEffect(Unit) { onDispose { graph.voice.quiet() } }` semantics through the session/practice hosts. Sessions cap at 15 items across modules (`SessionBudget.allowance(n)`), so `planFor` lists may be truncated.

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
- [ ] `ArcadeModule`: id ARCADE, title "Δεξί χέρι", icon `Icons.Rounded.BackHand`; `planFor` returns 4 placeholder items (sizing) only when ARCADE is enabled (it is filtered by `enabledModules` anyway); `Screen(items, sessionId, onDone, onLeave)` → `ArcadeScreen(sessionId, onDone, onLeave)`.
- [ ] `ArcadeViewModel(graph, sessionId)`: loads `arcadeTargetDp`; `games = listOf("tap","trace","drag","pinch")`; on each result: persist the new size, insert the attempt per Global Constraints, success/nudge feedback, advance; end screen "Τέλος για σήμερα. Μπράβο το δεξί!".
- [ ] `ArcadeScreen`: title "Δεξί χέρι", subtitle per game ("Πάτα τον κύκλο", "Ακολούθησε τη γραμμή", "Σύρε τη μπάλα στο σπίτι της", "Άνοιξε με δύο δάχτυλα"), the game fills the middle, bottom `QuietButton("Παράλειψη")`.
- [ ] Settings: under the ARCADE switch add the physio note text.
- [ ] Build, unit tests, install; play each game once with `adb shell input tap/swipe`; commit `feat(phase8): right-hand arcade`.

---

### Task 3: Phase 8 verification

- [ ] Full suites green; on the phone enable "Δεξί χέρι" in settings, play a full round with the right hand, confirm the target visibly shrinks after hits; Σφάλματα empty. Append notes; commit `docs(phase8): verification notes`.

---

## Verification notes

Phase 8 ran as tasks 1–2 (`3ff7d7f`, `47804e6`, on the carried session-cap fix `e57829c`), a review,
and a fix wave (`3709f67`, `22cfb6b`, `85b63f6`, plus the two phase-7 residuals `58fbd8d`, `8cd577f`).
Emulator `Medium_Phone_API_36`, 1080×2400 @ 420 dpi (density 2.625), `emulator-5554` throughout.

**Suites.** 298 unit tests green (`./gradlew -q testDebugUnitTest`); 67 instrumented tests green
after `pm clear` (`connectedDebugAndroidTest`), of which 3 are `ArcadeFlowTest` and 6 `TraceFlowTest`.
`error_logs` empty after every run.

**The module.** Four games, always in the same order, no timers anywhere: press the circle twelve
times, follow four lines, push five balls into their ring, open three photos with two fingers and
close them again. One attempt row per game (`arcade:tap` and its three siblings) carrying hits,
misses and the size he ended at; CORRECT at seven tries in ten, ASSISTED below it, SKIPPED when he
passes. Off until a caregiver switches it on, with the physio's note under the switch.

**Adaptive size, measured off the screen** (tap game, 12 targets caught in a row after three
deliberate misses):

| | Size |
|---|---|
| Start | 96 dp (252 px) |
| After 3 misses | 130 dp — the ceiling — and the target had not moved |
| Hits 1…12 | 336, 308, 284, 260, 240, 224, 204, 188, 172, 156, 144, 132 px (×0.92 each) |
| Kept for tomorrow | the game's own key in the settings, read back at 47.8 dp |

**What the review changed.** Two Important findings, both in the drag game, and both about the app
telling him one thing and marking another:

1. The ring was drawn at the live size but accepted at the size captured when the gesture block was
   installed. After two misses the ring on screen was 208 dp wide and the accepted drop radius was
   still 154 dp: a ball put plainly inside the circle got a buzz. Measured after the fix — ring
   radius grown to 272 px, ball released 240 px from its centre, i.e. outside the old 201 px radius
   — the drop counts (`shots/09`).
2. At large sizes the ball could be placed inside its own ring, five rounds won by touching the
   glass and a CORRECT row that overstated his arm. The ball is now placed clear of the ring by both
   radii; `TargetPlacerTest` proves it at every size from 40 to 130 dp on a 360 × 480 dp board, and
   every placement measured on the device was 519–751 px apart against a required 314–406.

Nine Minors: a failed grab now buzzes instead of being silent, a press off the paper is no longer an
aimed miss (measured: three taps outside the board left the target at 248 px, one tap inside it grew
it to 288 px), a target that grew past the edge comes back on to the board, the pinch game no longer
counts fingers spreading as they lift off a photo it has just accepted, every caught target shows a
tick beside the counter (`shots/10`), the rotation ignores modules he skipped his way through, the
`planToday` join is unit-tested with fake modules, and the Today grid's comment says seven.

**Per-game difficulty.** The four games shared one size, so a good round of tapping walked the pinch
game down with it. One key each now: after a sitting where only the drag game was finished, the
store held `arcade_target_dp_drag = 85.68` and nothing else, and the tap game still opened at 96 dp
(`shots/10`). A device that stored the old single size hands it to every game that has not been
played since.

**Also checked by hand.** The tile appears on Today only once the switch is on (`shots/01`, `02`);
free practice ends on «Τέλος για σήμερα. Μπράβο το δεξί!» (`shots/07`); a session containing the
arcade goes straight to the summary with the honest count — «Έκανες 1 άσκηση σήμερα: Δεξί χέρι.»
after one game played and three skipped (`shots/08`); the rows read back
`arcade:tap SKIPPED`, `arcade:trace SKIPPED`, `arcade:drag ASSISTED {"hits":5,"misses":3}`,
`arcade:pinch SKIPPED` — 5 of 8 is below the seven-in-ten line, and the app said so without saying
it to him.

**The session cap** (carried phase-2 fix): with all seven modules enabled the session row read
`plannedModules = WORDCOACH,NUMBERS,SINGSAY,SCRIPTS`, `plannedItemCount = 13`. The cap is per
session, and the Today flow is one session a day; a second «Ξεκίνα» the same afternoon gives him the
modules he has not done yet rather than a locked door.

**Phase-7 residuals fixed here.** «Το είδα» now takes the ink with the letter, so the cheap route
through level 5 — trace the visible word, then hide it — is closed (`shots/11`, `12`, and
`TraceFlowTest` writes after the hide); the writing paper keeps a real 200 dp floor with
`requiredSize`, the slot scrolling only in that extreme; a single letter's canvas is square, so a
slot change scales the box by one factor and his ink no longer drifts sideways off the glyph.

**Left for Chris.** The rounds — twelve taps, four lines, five balls, three photos — and the 70 %
line are a programmer's guesses. So is the pinch game's "open to twice and back": it is the hardest
thing in the app for that hand, and the physio may want it out of the rotation entirely. The module
stays off until she says otherwise.

## Execution record (controller rulings, 2026-09-06)

Copied from the SDD ledger at phase close. Combined task + final review: 0 Critical / 2 Important / 9 Minor, all fixed or ruled; fix-wave re-review clean. Also carried in this phase: a daily session takes at most four modules (word coach always, three others rotating by least-recent use, skips not counted as use); per-game adaptive size keys; the three phase-7 residuals.

## Pre-flight conflict scan (2026-09-05)

| Tasks | Shared surface | Produces vs consumes | Finding |
|---|---|---|---|
| 1 / 2 | Adaptive (96 → ×0.92 min 40 / ×1.15 max 130), TargetPlacer | games consume | consistent |
| 2 / phase 7 | TraceScorer reuse for the trace-path game | produced by phase 7 Task 1 | consistent |
| 2 / phase 0 | Settings.enabledModules DEFAULT_OFF = setOf(ARCADE) already exists; settings row text | the row exists; Task 2 adds the physio note | consistent |
| 2 / phase 2+3 | Screen(items, …): four games in sequence; planFor sizing | Ruling: planFor returns 4 transient items (one per game); in a session the screen runs min(items.size, 4) games in the fixed order tap → trace → drag → pinch; free practice all four |
| 2 / phase 3 | end screen | auto-onDone in sessions (no level here) | Ruling: same rule; free practice shows «Εντάξει» |
| 2 / phase 4 | Voice.quiet on leave; feedback sounds | consistent |
| 2 alone | pinch game needs a photo: use his own item photos (caregiver photos in MediaFiles.photosDir) else seed pictograms | Ruling: prefer CAREGIVER-source items with a photo, fall back to any item image |
| 2 alone | drag/pinch gestures inside a scrolling host | Ruling: the game area is a fixed-size Box that consumes the gesture (as phase 7's canvas) |
| all | Greek-only, "Δεξί χέρι" always visible, no timers, 72dp for buttons (targets may be smaller: they are the exercise, not navigation) | Ruling: targets below 72dp are allowed by the spec; navigation buttons stay 72dp |

Scan result: four rulings carried into dispatches.

## Task log
Tasks 1+2: dispatched as one batch — BASE 0d2ecb3, model opus (first commit: the session-module cap fix(phase2); in parallel with the phase-7 scoped re-review, read-only; residuals fold into this task's fix round)
Tasks 1+2: implementer DONE (e57829c session cap, 3ff7d7f, 47804e6; JVM 286, connected 67). Deviations accepted pending review (no onSkip in games, awaitEachGesture pinch, prompts spoken, no second enabled key). Ruling: the adaptive size is per game (arcadeTargetDp keyed by game) so a flawless tap round does not shrink the drag/pinch targets — into the fix wave. Combined task + final review dispatched (opus).
Review DONE (0 Critical / 2 Important / 9 Minor; all ✅). Fix wave — BASE 47804e6, resuming implementer a88a79b101098d1f6: I1 live home radius, I2 puck placement never inside the ring, per-game adaptive keys, minors (stray touch = miss only inside the board, clipped target, pinch miss after success, SKIPPED not counted as practised in the rotation, rotation wiring test, KDoc, icon on per-target success), plus the three phase-7 residuals (hide clears strokes, paper floor, uniform rescale) and Task 3 verification notes.
Fix wave DONE (3709f67, 22cfb6b, 85b63f6, 58fbd8d + 8cd577f phase-7 residuals, 3ae2561; JVM 298, connected 67). Scoped re-review dispatched (sonnet) covering the phase-7 residuals too.
Fix-wave re-review: clean. Phase 8 closed — execution record commit deferred until the phase-9 implementer reports (no concurrent git writes).
