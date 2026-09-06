# Dimitris' App — Phase 7 (Trace and Write) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** He already writes in the air. Give him a huge letter or word to trace with a finger, score how close he stayed, then have him write it from memory (copy-and-recall). Either hand is allowed; a caregiver setting records which hand he is training.

**Architecture:** Pure `TraceScorer` (mean distance + coverage against a template point cloud), Android `Glyphs` (template points sampled from the system font outline via `Paint.getTextPath` + `PathMeasure`, so no hand-drawn stroke data is needed), a `TraceCanvas` composable that records finger strokes, and a `TraceModule` with five levels driven by `LevelProgression`.

**Tech Stack:** as before; Compose `Canvas` + `pointerInput` drag detection.

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§7 phase 7)

## Global Constraints

- Greek-only UI; 72dp; bottom actions; no timers; success = icon + sound + haptic; a poor trace gets a nudge and "Ξανά" (retry), never a fail state.
- Levels: 1 capitals (Α–Ω, one letter); 2 lowercase (one letter); 3 his name "Δημήτρης" (whole word); 4 words from his WORD items, shortest first; 5 recall: the word is shown, he taps "Το είδα" to hide it, writes it on a blank canvas, then the template is revealed over his strokes and scored. Level moves with `LevelProgression(min 1, max 5)`.
- Scoring: `TraceScore(meanDistance, coverage, passed)`; passed when `meanDistance <= 0.08 * templateHeight` and `coverage >= 0.6` for levels 1–4; recall (level 5) passes at `coverage >= 0.4` and `meanDistance <= 0.14 * templateHeight` (tolerant by design).
- Attempts: `itemId = "trace:level:N"` (levels 1–3, 5 with generated text) or the item id (level 4), `module = TRACE`, `cueLevel = null`, `detail` `{text, level, meanDistance, coverage, hand}`; CORRECT on first pass, ASSISTED after a retry, SKIPPED on skip.
- Hand: `Settings.traceHand` (LEFT default, RIGHT) shown as a hint "Με το δεξί χέρι" / "Με το αριστερό χέρι".
- Commits `feat(phase7): ...` with the Co-Authored-By trailer; conventions as previous phases.

- **Module contract (after the phase-2 fix wave):** `Module.Screen(items, sessionId, onDone, onLeave)` — `onDone` = all exercises finished, `onLeave` = the user pressed back (the screen's `onBack` calls `vm.leave(onLeave)`: the ViewModel cancels a running take, quiets the voice, joins its last app-scope write, then invokes `onLeave` — both callbacks are invoked only after the module's Attempt rows have landed). Attempt/schedule writes go on `graph.scope` (they must survive the screen); the last write is joined before `done` is published. Every module screen has `DisposableEffect(Unit) { onDispose { graph.voice.quiet() } }` semantics through the session/practice hosts. Sessions cap at 15 items across modules (`SessionBudget.allowance(n)`), so `planFor` lists may be truncated.

---

### Task 1: Trace scorer (pure)

**Files:**
- Create: `modules/trace/TraceScorer.kt`
- Test: `modules/trace/TraceScorerTest.kt`

**Interfaces:**
- `data class Pt(val x: Float, val y: Float)`; `data class TraceScore(val meanDistance: Float, val coverage: Float, val passed: Boolean)`; `object TraceScorer { fun score(user: List<Pt>, template: List<Pt>, templateHeight: Float, maxMeanFraction: Float = 0.08f, minCoverage: Float = 0.6f): TraceScore; fun resample(points: List<Pt>, step: Float): List<Pt> }`.
- `meanDistance` = mean over resampled user points of the distance to the nearest template point; `coverage` = fraction of template points that have a user point within `maxMeanFraction * templateHeight * 1.5`. Empty user → `TraceScore(Float.MAX_VALUE, 0f, false)`.

- [ ] Failing tests: tracing exactly along a square template (perimeter points) scores `meanDistance ≈ 0`, `coverage == 1`, passed; tracing only one side scores coverage ≈ 0.25 and not passed; an offset copy of the template by 20 % of height fails on distance; `resample` of a 100-unit segment with step 10 yields 11 points; empty user returns not passed.
- [ ] Implement (`resample` walks the polyline inserting points every `step`; nearest-point search is O(n·m), fine for ≤ 2 000 points).
- [ ] `./gradlew -q testDebugUnitTest`; commit `feat(phase7): trace scorer`.

---

### Task 2: Glyph templates, canvas, module, screen

**Files:**
- Create: `modules/trace/Glyphs.kt`, `TraceCanvas.kt`, `TraceModule.kt`, `TraceViewModel.kt`, `TraceScreen.kt`
- Modify: `core/settings/Settings.kt` (+ `traceLevel` 1..5, `traceHand` "LEFT"/"RIGHT"), `AppGraph.kt` (`modules += TraceModule`), `caregiver/SettingsScreen.kt` (hand toggle under "Ασκήσεις")
- Test: `SettingsTest.kt` (+2), instrumented `GlyphsTest` (template for "Α" has > 50 points and a positive height)

- [ ] `Glyphs.template(text: String, boxWidth: Float, boxHeight: Float): Pair<List<Pt>, Float>`: `Paint` (sans-serif, bold), text size chosen so the text fits 80 % of the box, `getTextPath` → `PathMeasure` sampling every 6 px on every contour → points translated to centre the glyph; returns points + glyph height.
- [ ] `TraceCanvas(template: List<Pt>, showTemplate: Boolean, strokes: List<List<Pt>>, onStroke: (List<Pt>) -> Unit, modifier)`: draws the template as light grey dots (when `showTemplate`), the strokes as 14 dp navy lines with round caps; `pointerInput` `detectDragGestures` collects a stroke and emits it on drag end. Canvas is `fillMaxWidth().aspectRatio(1.2f)` for single letters and `aspectRatio(0.9f)` for words.
- [ ] `TraceModule`: id TRACE, title "Γράψε", icon `Icons.Rounded.Draw`; `planFor` = up to 6 short WORD items (sizing only); `Screen(items, sessionId, onDone, onLeave)` → `TraceScreen(sessionId, onDone, onLeave)`.
- [ ] `TraceViewModel(graph, sessionId)`: loads level + hand; builds 6 targets per session: L1 random capitals, L2 random lowercase, L3 "Δημήτρης" ×2 + "ΔΗΜΗΤΡΗΣ", L4 six shortest WORD items, L5 same words for recall; state `{level, hand, index, total, text, itemId?, templateVisible, strokes, score?, tries, done, levelChanged}`; `setCanvasSize(w, h)` builds the template through `Glyphs`; `addStroke`, `clear`, `hide()` (level 5: hides the template after "Το είδα"), `check()` → `TraceScorer.score(...)` with level-5 thresholds when applicable → passed: success feedback + speak the text + record CORRECT/ASSISTED; failed: nudge, `tries++`, keep strokes visible and reveal the template; `retry()` clears; `skip()`; `next()`; end → `LevelProgression.next(level, results, 1, 5)` → `Settings.traceLevel`.
- [ ] `TraceScreen`: title "Γράψε i/n" + hand hint; the text in `displayLarge` above the canvas (hidden in level 5 after "Το είδα"); the canvas; bottom: after pass `BigButton("Επόμενο")`; otherwise `Row { QuietButton("Καθάρισε"), BigButton("Έτοιμο", Success) }` (+ `BigButton("Το είδα")` first in level 5) and `QuietButton("Παράλειψη")`.
- [ ] Settings: `traceHand` toggle row "Χέρι για γράψιμο: Αριστερό / Δεξί" (two `FilterChip`s at 72dp).
- [ ] Build, unit + connected (Glyphs) tests, install, trace "Α" on the emulator with `adb shell input swipe` along the canvas to see a score; commit `feat(phase7): trace-and-write module`.

---

### Task 3: Phase 7 verification

- [ ] Full suites green; on the phone trace with a finger, pass a capital, fail one on purpose and see "Ξανά", reach level 5 by setting `traceLevel` via a temporary caregiver control if needed (the phase 9 dashboard adds level controls); Σφάλματα empty. Append notes; commit `docs(phase7): verification notes`.

---

## Verification notes

Phase 7 ran as tasks 1–2 (`27e948c`, `c1aa3f8`), a review, and a fix wave
(`c14f669`, `186eac9`). Emulator `Medium_Phone_API_36`, 1080×2400 @ 420 dpi.

**Suites.** 259 unit tests green (`./gradlew -q testDebugUnitTest`); 64 instrumented tests green
after `pm clear` (`connectedDebugAndroidTest`), of which 7 are `GlyphsTest` and 6 `TraceFlowTest`.
`error_logs` empty after every run.

**What the review found, and what it cost.** The first build drew the pass line against the outline
of a filled bold glyph. A line down the middle of a stroke — what a person draws — is half a stem
away from that outline everywhere, so writing the letter correctly scored ~25 % over the threshold,
and at level 3 the whole of «Δημήτρης» was judged to 0.85 mm of mean error. Both device traces that
passed had been replayed from the template's own points: they measured the scorer against itself.

The fix is in three parts, and the third is the one worth remembering:

1. `Glyphs` samples an ordinary-weight face and returns the *ink* as well as the outline — a
   `Region` filled from the glyph path, so the hole in an «Ο» is not the letter. A user point on the
   ink costs nothing; the outline is left measuring coverage.
2. Thresholds are pixels worked out by the screen from the letter's height, with a floor of 10 dp of
   mean error and 14 dp of reach. A fraction alone cannot work: a tenth of the height is half a stem
   on a capital and a hair's breadth on a word of eight letters, and his hand does not shrink.
3. Verification traces the *centre line*, derived from the mask, delivered as `adb` swipes. A test
   that replays the answer proves nothing.

**Measured, level by level** (hand-like centre-line traces, `adb shell input swipe`):

| Level | Target | Glyph height | Pass line | Mean | Coverage | Result |
|---|---|---|---|---|---|---|
| 1 | «Τ» | 1015 px | 101.5 px | 0.0 | 1.00 | CORRECT |
| 2 | «χ» | ~1015 px | ~101 px | 0.0 | 1.00 | CORRECT |
| 3 | «Δημήτρης» | 182 px | 26.2 px (the dp floor) | 0.0 | 0.97 | CORRECT |
| 3 | «Δημήτρης» with a ±40 px wobble | 182 px | 26.2 px | 7.2 | 0.98 | CORRECT |
| 4 | «μάτι» | ~430 px | ~43 px | 0.0 | 1.00 | CORRECT, row on the item |
| 5 | «εγώ» after «Το είδα» | 427 px | 59.8 px | 0.0 | 1.00 | CORRECT |

A scribble in the middle of the letter and a beautifully drawn *wrong* letter both answer «Ξανά»,
leave his strokes on the paper with the template over them, and write no attempt row.

**Also checked by hand:** the hand hint follows `traceHand` («Με το δεξί χέρι» after switching the
chip); back after a pass lands the row; a session of «Γράψε» alone reported the honest count
(«Έκανες 1 άσκηση σήμερα: Γράψε.» after five skips and one traced word, session row
`planned 6, completed 1`); the level dropped to 4 on that sitting and said so.

**Left for Chris.** `TraceScorer` is still marked *rewrite me*: it is order- and direction-blind — the
letter drawn bottom-up or mirrored-where-symmetric scores the same — and the thresholds above are a
programmer's guess, not a therapist's.
