# Dimitris' App — handover (2026-09-10)

**Phases 0–12** are implemented on branch `worktree-phase0`: phases 0–10 of
`docs/superpowers/specs/2026-09-05-dimitris-app-design.md`, then **phase 11** (polish after the first
field test) and **phase 12** (§13 of the spec — the re-scope after Dimitris tried it himself and said
it was too easy). Nothing has been merged into `master` and nothing has been pushed anywhere: the
merge is your call.

This file no longer names a commit, because the last one it named was two phases stale by the time
anybody read it: `git log --oneline master..worktree-phase0` is the branch's real state, and
`git log -1` is the real head. Phases 11 and 12 have their own section below, each with its own
"what only you can verify" list.

## What is in the APK

| Module | Greek title | Notes |
|---|---|---|
| Talk board (AAC) | Μίλα | quick phrases, categories, sentence strip, favourites, caregiver photos and voices |
| Word coach | Λέξεις | Leitner boxes 1–5, cue ladder 0–4, record and compare, speech recognition when enabled |
| Number sense | Αριθμοί | fifteen levels: dots, number line, counting, number words, coins, prices, paying, sums and carries, the tables, division, change, the clock and days, word problems |
| Sing-then-say | Τραγούδα | two-note melody from Greek stress sung in breath groups of six syllables, five MIT stages, caregiver sung model, 52 seed phrases — 20 of them whole everyday sentences since phase 13; **off by default since phase 12** |
| Dialogues | Διάλογοι | 14 seed scripts with tiers and intents, open answers judged for sense, caregiver editor with the other side's voice, cue ladder on his lines |
| Sentence builder | Προτάσεις | eight levels, accusative objects, distractor from level 4, articles and prepositions and clauses, gap-fill and typed boards |
| Trace and write | Γράψε | five levels incl. recall, finger-sized tolerances, filled-glyph scoring |
| Right-hand arcade | Δεξί χέρι | off by default; tap, trace, drag, pinch with adaptive size per game |
| Caregiver mode | — | biometric gate, words/photos/voices, dialogues, settings, backup, errors, progress, Claude |
| Progress | Πρόοδος | minutes, streak, per-module accuracy, cue trend, mastered words, insights, level steppers |
| Claude advisor | Ρώτα τον Claude | optional; needs an API key in settings; text-only summary; two-sentence encouragement read aloud |
| Sync | Συγχρονισμός | self-hosted server in `server/`; role screen on first run; caregiver phones pull his progress |

Tests on the final tree: 1092 JVM (2 skipped — the two live-key Claude calls), ~190 instrumented with a local sync server, 29 server.

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

## Phase 12 (after Dimitris tried it himself)

Dimitris used v0.2.0 and said it was too easy. Spec §13 is the re-scope that came out of that: he
says most everyday words, reads Greek slowly but understands abstract words, and reasons well; his
speech is telegraphic («φάρμακα πρέπει πάρω»). What he lacks is **full sentences and multi-step
tasks**, and that is the new centre of the app.

- **Transcription is free and on the phone.** Android's on-device recogniser (Android 13+) is used
  when it can be; Ρυθμίσεις → «Αναγνώριση ομιλίας» now carries a row that says which of the four
  states this phone is in and offers **«Λήψη ελληνικών»** — the Greek pack download — when the pack
  is missing. The network recogniser is only a fallback, with honest Greek lines for "no connection"
  and "Greek pack missing".
- **One speech control.** «Μίλα» opens the microphone once: the same audio is recorded as a **WAV
  take** (16 kHz mono PCM16, written straight from `AudioRecord` and fanned into the recogniser
  through a pipe) *and* transcribed. «Άκου» afterwards plays the model and then his own take back to
  back — the old «Ηχογράφηση» and «Σύγκριση» are gone from the on-device path. **Retention** is
  unchanged in shape and now covers the WAV takes: three of his own takes per word survive, older
  ones are soft-deleted and their bytes are kept only until the deletion has actually been pushed to
  the family's server (`PendingRemovals`), so a second phone frees the same disk.
- **The turn judge.** Ρυθμίσεις → Claude gains **«Έλεγχος με Claude»** — opt-in, **its own** consent
  separate from the API key, and **text only**: the question, the target and what the recogniser
  heard, and nothing else. It runs on `claude-haiku-4-5-20251001`; the weekly advice keeps
  `claude-opus-5`. It judges four things: a word, a sentence, an open dialogue answer, and an
  expansion — and it answers with *accept*, the full grammatical sentence to say back, and one warm
  Greek line. Without the toggle, without a key, or when the call fails, the app falls back to local
  matching and the attempt row says which decided it.
- **The talk board says the whole sentence.** With two or more words on the strip and the judge
  available, «Μίλα» offers **«Ολόκληρη»**: the words become a grammatical Greek sentence, spoken,
  with «Πες το» under it so he can say it back.
- **Difficulty is his.** Every module's first screen carries one row of **five dots**, 1–5, under
  the title. He taps them himself. The caregiver sets the bounds in Ρυθμίσεις → «Όρια δυσκολίας»
  (a floor and a ceiling per module); dots outside them are dimmed and refuse the tap with one line,
  «Ο φροντιστής έβαλε όριο.» A dot picks a *band* of the module's own levels and the automatic
  progression keeps moving inside it.
- **Αριθμοί to level 15.** Eight new levels: two-digit sums, carries and borrows to 1000, the tables,
  division and a missing factor, change from a note, the clock and the days of the week, number words
  to 9999 both ways, and two-step word problems. Four options, one right, the whole prompt spoken,
  and nothing anywhere asks him to write a digit.
- **Διάλογοι are open.** His answers are judged for relevance and form rather than matched to a
  scripted line: any sensible reply counts, a telegraphic one earns the expanded sentence to repeat.
  Every line now carries a **tier** (1–5, what the dots select on) and an **intent** (what a good
  answer has to convey). **14 seed dialogues** ship, up from six.
- **Προτάσεις to level 8.** Levels 5–8 are the small words: articles, prepositions, a «να» clause, a
  question. Every third board is asked differently — **gap-fill** at 5–6 (the sentence with one small
  word missing and three to choose from) and **typed** at 7–8 (a picture, «Γράψε την πρόταση», the
  keyboard, and the judge).
- **The database moved to v8** (the dialogue lines' `tier` and `intent`, additive migration). His
  phone and every caregiver phone must move to the phase 12 build together, as with v7.
- **The interface stayed simple.** Spec §13 added the rule and `docs/UX.md` is the audit of it, screen
  by screen: one primary action, at most three bottom actions. Two screens were over the line and
  were fixed; «Τραγούδα και πες το» is now off unless a caregiver switches it on, for **new installs
  only** — a phone that already had it keeps it.

### What only you can verify (phase 12)

1. **The on-device Greek pack, and the pipe, on your Samsung.** The emulator has no on-device
   recogniser at all, so every state below `ON_DEVICE` is what has actually been exercised. On the
   phone: turn «Αναγνώριση ομιλίας» on, use «Λήψη ελληνικών» if the row offers it, and check that
   «Μίλα» both transcribes *and* leaves a playable take («Άκου» should play the model and then him).
2. **One real judge call.** With a key in the environment:
   `ANTHROPIC_API_KEY=… ./gradlew testDebugUnitTest --tests '*TurnJudgeLiveTest*'`. It is skipped
   everywhere the variable is absent, which is why the JVM suite reports two skipped tests. It asks
   the §13 turn — «Τι θα πάρεις από το φαρμακείο;» / «φάρμακα πρέπει πάρω» — and requires the answer
   to come back accepted **and** with the whole sentence. The same for the advisor:
   `--tests '*ClaudeAdvisorLiveTest*'`.
3. **The new prompts, by ear.** Numbers 8–15 are spoken as well as written, and nobody has heard
   them: the change questions, the clock («Τι ώρα είναι;»), the day-after questions and the two-step
   word problems. Listen to a few and fix the wording in `ExerciseGenerator` if any of them reads
   like a textbook rather than like a person.
4. **Difficulty 4 and 5, with Dimitris.** The bands were designed against what he told us, not
   measured on him. Sit with him, let him tap the dots himself, and watch whether four in five still
   land — especially «Αριθμοί» 4–5 (two-step problems) and «Προτάσεις» 4–5 (the clause and the typed
   sentence).
5. **After any backup restore, check «Τραγούδα και πες το».** The grandfathering that keeps the
   module on a phone that already had it reads the *preference store*, and a backup import replaces
   the database and the media but never the preferences (`Backup.kt`). So a replacement phone that
   installs the app and imports his backup counts as a new install and comes up without the module.
   One switch in Ρυθμίσεις → «Ασκήσεις» puts it back; it is the only setting a restore does not
   carry.

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
