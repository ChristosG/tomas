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
- [ ] `TraceModule`: id TRACE, title "Γράψε", icon `Icons.Rounded.Draw`; `planFor` = up to 6 short WORD items (sizing only); `Screen` → `TraceScreen(sessionId, onDone)`.
- [ ] `TraceViewModel(graph, sessionId)`: loads level + hand; builds 6 targets per session: L1 random capitals, L2 random lowercase, L3 "Δημήτρης" ×2 + "ΔΗΜΗΤΡΗΣ", L4 six shortest WORD items, L5 same words for recall; state `{level, hand, index, total, text, itemId?, templateVisible, strokes, score?, tries, done, levelChanged}`; `setCanvasSize(w, h)` builds the template through `Glyphs`; `addStroke`, `clear`, `hide()` (level 5: hides the template after "Το είδα"), `check()` → `TraceScorer.score(...)` with level-5 thresholds when applicable → passed: success feedback + speak the text + record CORRECT/ASSISTED; failed: nudge, `tries++`, keep strokes visible and reveal the template; `retry()` clears; `skip()`; `next()`; end → `LevelProgression.next(level, results, 1, 5)` → `Settings.traceLevel`.
- [ ] `TraceScreen`: title "Γράψε i/n" + hand hint; the text in `displayLarge` above the canvas (hidden in level 5 after "Το είδα"); the canvas; bottom: after pass `BigButton("Επόμενο")`; otherwise `Row { QuietButton("Καθάρισε"), BigButton("Έτοιμο", Success) }` (+ `BigButton("Το είδα")` first in level 5) and `QuietButton("Παράλειψη")`.
- [ ] Settings: `traceHand` toggle row "Χέρι για γράψιμο: Αριστερό / Δεξί" (two `FilterChip`s at 72dp).
- [ ] Build, unit + connected (Glyphs) tests, install, trace "Α" on the emulator with `adb shell input swipe` along the canvas to see a score; commit `feat(phase7): trace-and-write module`.

---

### Task 3: Phase 7 verification

- [ ] Full suites green; on the phone trace with a finger, pass a capital, fail one on purpose and see "Ξανά", reach level 5 by setting `traceLevel` via a temporary caregiver control if needed (the phase 9 dashboard adds level controls); Σφάλματα empty. Append notes; commit `docs(phase7): verification notes`.
