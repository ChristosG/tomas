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

Tests on the final tree: 467 JVM (1 skipped: the live Claude call), 87 instrumented on the emulator (the 5 sync round trips pass with a local server running), 27 server tests.

## What only you can verify

1. **Install on your phone** (`R5CWC2C1KSJ`): `./gradlew installDebug` with the phone as the only device, or `ANDROID_SERIAL=R5CWC2C1KSJ`. Every device check so far ran on the emulator `Medium_Phone_API_36`, which has no audio output.
2. **Listen.** Nobody has heard the sing-then-say melody, its tempo, the stage prompts, or the number prompts. Adjust `Melody.NOTE_MS`/`GAP_MS` and the tone gain if it sounds wrong.
3. **Claude.** Put a key in Ρυθμίσεις → Claude, then run once on the dev machine: `ANTHROPIC_API_KEY=... ./gradlew -q testDebugUnitTest --tests '*ClaudeAdvisorLiveTest*'`. No live call has been made yet; the request shape was verified only against a 401.
4. **Server.** `server/README.md` (English + Περίληψη στα ελληνικά): run it behind TLS on your or the father's web server, set the URL and token on every phone in caregiver mode. Release builds refuse plain HTTP by design.
5. **Speech recognition** is a setting (off by default); Google's Greek recogniser must be installed on the phone.

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
