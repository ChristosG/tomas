# Dimitris' App — handover (2026-09-06)

All ten phases of `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` are implemented on branch `worktree-phase0` (HEAD `61129cf`, 176 commits after master `4532747`). Nothing has been merged into `master` and nothing has been pushed anywhere: the merge is your call.

## What is in the APK

| Module | Greek title | Notes |
|---|---|---|
| Talk board (AAC) | Μίλα | quick phrases, categories, sentence strip, favourites, caregiver photos and voices |
| Word coach | Λέξεις | Leitner boxes 1–5, cue ladder 0–4, record and compare, speech recognition when enabled |
| Number sense | Αριθμοί | seven levels: dots, real number line, counting, number words, coins, prices, paying |
| Sing-then-say | Τραγούδα | two-note melody from Greek stress, five MIT stages, caregiver sung model, 20 seed phrases |
| Dialogues | Διάλογοι | six seed scripts, caregiver editor with the other side's voice, cue ladder on his lines |
| Sentence builder | Προτάσεις | four levels, accusative objects, distractor at level 4 |
| Trace and write | Γράψε | five levels incl. recall, finger-sized tolerances, filled-glyph scoring |
| Right-hand arcade | Δεξί χέρι | off by default; tap, trace, drag, pinch with adaptive size per game |
| Caregiver mode | — | biometric gate, words/photos/voices, dialogues, settings, backup, errors, progress, Claude |
| Progress | Πρόοδος | minutes, streak, per-module accuracy, cue trend, mastered words, insights, level steppers |
| Claude advisor | Ρώτα τον Claude | optional; needs an API key in settings; text-only summary; two-sentence encouragement read aloud |
| Sync | Συγχρονισμός | self-hosted server in `server/`; role screen on first run; caregiver phones pull his progress |

Tests on the final tree: 690 JVM, 135 instrumented with a local sync server, 29 server.

## Phase 11 (polish after the first field test)

Chris's notes from the first week of real use, turned into changes on both sides of the app:

- **Writing is judged by shape, not by area.** Γράψε now scores against the true glyph outline
  instead of a loose height-based tolerance, so a wrong-shaped letter can no longer pass by
  accident. A caregiver can loosen or tighten the mark from Ρυθμίσεις → «Αυστηρότητα γραψίματος»
  (Χαλαρό / Κανονικό / Αυστηρό); a hand-like trace of the right letter still always passes at
  Κανονικό, and a wrong letter never passes at Κανονικό.
- **«Άκου» is never withheld.** Every one of his turns in Λέξεις, Διάλογοι, Τραγούδα and Προτάσεις
  now carries an always-enabled «Άκου» from the first second, before any hint is offered. Listening
  raises the *recorded* cue level to at least 3 (an assisted answer) so his numbers on Πρόοδος stay
  honest, but nothing on screen ever calls a listened-to or assisted answer wrong.
- **The recogniser waits for him.** Speech recognition (Ρυθμίσεις → «Αναγνώριση ομιλίας», off by
  default) now gives him a real window — at least 8 seconds of silence before it gives up — shows
  «Σε ακούω…» with a level bar while it listens, and a 72dp «Στοπ» to end it early. A silent take
  shows «Δεν σε άκουσα. Πες το πιο δυνατά.» and writes no row; a recognised mismatch offers one
  gentle «Δοκίμασε ξανά» before «Το είπα!» still confirms regardless, exactly as before.
- **Sing-then-say is tunable by ear.** Ρυθμίσεις → «Τραγούδα» gains «Ρυθμός» (Κανονικός / Αργός)
  and «Τόνος» (Κανονικός / Χαμηλός), for a caregiver to slow the melody down or drop its key on a
  bad day.
- **Caregivers can try what they just added.** A saved word in the word/photo/voice editor now
  shows «Δοκίμασέ το», a saved dialogue shows «Παίξ' το» — both jump straight into practice with
  that item instead of waiting for it to come up on its own; a word she just wrote now queues ahead
  of the seed vocabulary instead of behind it.
- **The Claude advisor reads his whole journey.** Caregivers can leave dated notes for Claude and
  for each other from the advice screen. A consultation now sends the full per-word history, the
  last four weeks day by day, the caregivers' notes, and Claude's own previous advices — one long
  request rather than a short summary — and gets back a caregivers' section, two simple sentences
  for Dimitris to hear, and a «Εστίαση» (focus: words, first sounds, modules, level suggestions)
  that `SessionBuilder` applies to his next session by itself.
- **Every exercise now records what could adapt it later.** Timing, hint counts, recognition
  results, melody settings and more are written into each attempt's existing `detail` JSON column.
  Nothing adapts from it yet — it is only being kept. `docs/ADAPTATION.md` explains, module by
  module, what is recorded, which knob it could drive, and the first rule to try.
- **The database moved to v7** (the new `advice` and `notes` tables, additive migration). Both his
  phone and every caregiver phone must update to the phase 11 build together: an older phone
  syncing against a newer one's data does not know about the two new tables.

## What only you can verify

1. **Install on your phone** (`R5CWC2C1KSJ`): `./gradlew installDebug` with the phone as the only device, or `ANDROID_SERIAL=R5CWC2C1KSJ`. Every device check so far ran on the emulator `Medium_Phone_API_36`, which has no audio output.
2. **The recogniser's wait, on the phone.** Turn on «Αναγνώριση ομιλίας» (Google's Greek recogniser must be installed) and check by hand: the window should hold through a good pause before giving up, «Σε ακούω…» should show the whole time, «Στοπ» should end it early, and a deliberately silent take should answer «Δεν σε άκουσα. Πες το πιο δυνατά.» instead of recording nothing.
3. **Writing strictness at all three levels, with a real hand.** Try Χαλαρό, Κανονικό and Αυστηρό in Γράψε with Dimitris' own hand, not a synthetic trace — every tolerance was tuned on paper, not on him.
4. **Melody settings, by ear.** Nobody has heard «Ρυθμός: Αργός» or «Τόνος: Χαμηλός» in the sing-then-say melody. Adjust `Melody`'s tempo/key constants if either sounds wrong.
5. **A real Claude consultation, with notes.** Put a key in Ρυθμίσεις → Claude, write a caregiver note or two, then run a real consultation. No live call has been made yet; the request shape and the journey report's size have only been checked against a 401 and against the character caps, never against a real answer.
6. **Sync of a note between two phones.** With both phones on the phase 11 build (DB v7), write a note on one and confirm it reaches the other through `server/README.md`'s server, under real TLS, not just the JVM/server test suite.

## Functions reserved for you (`// CHRIS: rewrite me.`)

All are implemented and fully tested; rewrite freely, the tests tell you what the app relies on:

- `core/greek/Syllabifier.kt`, `core/greek/Accusative.kt`
- `core/scheduler/LeitnerPolicy.kt`, `LevelProgression.kt`, `modules/numbers/NumberProgression.kt`
- `modules/sentences/SentenceTemplates.kt`, `modules/trace/TraceScorer.kt`
- `caregiver/insights/InsightRules.kt` (its thresholds also shape what Claude is told)

## Decisions made on your behalf

Every controller ruling is recorded in the "Execution record" section at the end of each plan under `docs/superpowers/plans/`. The ones most worth a look:

- Sessions take at most four modules a day (word coach always, three others rotating), 15 items, dialogues run whole.
- Progression rules (numbers, sentences, trace) judge one sitting only and hold under five results.
- Seeded words and dialogues get deterministic ids and a fixed timestamp so phones never duplicate them and family edits always win.
- Caregiver-role phones do not push attempts, sessions or schedules (their own practice never pollutes his progress); levels and secrets never sync.
- The talk board is not scored; it is excluded from accuracy, insights and the Claude summary's difficulty lists.
- Attempts and error logs are append-only everywhere (app, backup, sync).

## Known limitations

- Debug APK is 33 MB (Anthropic SDK); release has minify off. Enable R8 with rules if size matters.
- A first sync on mobile data downloads all pictograms without asking.
- A future NOT NULL column must ship to all phones together (the sync validator rejects incomplete rows).
- Screen readers are not supported (the number line names the answer in its content description).
- The tremor tolerance in Γράψε was tuned with synthetic hand-like traces, not a real hand.

## Merge

From the main checkout: `git merge worktree-phase0` on `master`, then `./gradlew -q testDebugUnitTest`. The worktree is `.claude/worktrees/phase0` (locked by the session that created it).
