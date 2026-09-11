# Dimitris' App — Phase 13 (New tiles and content) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give Dimitris what phase 12 could not fit: a «SQL» tile that runs real beginner queries (he was a programmer), a «Βήματα» tile for the step-by-step tasks he calls «είμαι καμένος», vocabulary tiers with abstract and multi-syllable words (and a gender column so the sentence builder can use caregiver nouns), dictation and typed writing in Γράψε, and sing-then-say moved to long sentences.

**Architecture:** two new modules on the same `Module` contract and the difficulty ladder; one additive schema change (DB v9: `Item.tier`, `Item.gender`); an in-memory SQLite runner (`android.database.sqlite`) for the SQL tile; seed content extensions through the existing importers (dedup by text/title, deterministic ids and stamps).

**Tech Stack:** as before. No new dependencies.

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§13, "Two new tiles" and the content paragraph)

## Global Constraints

- Greek-only UI (SQL keywords and textbook table names stay in English, as SQL is); ≥ 72dp; no timers; errorless speech; adult tone; the UX rule (one primary, ≤ 3 bottom actions, every activity a tile, the `DifficultyRow` on the first screen only).
- Privacy: nothing about his health beyond §1/§13 anywhere; the SQL tile's "his life" tables carry only word texts and counts, never paths or ids.
- Every Room change additive with schema, migration test, sync registry; DB is v8 now.
- Conventions of phases 0–12 (module contract, writes on `graph.scope`, `leave(then)`, Greek error slot, `judge.newRun()` where the judge is used, telemetry keys via `Adapt`, per-test seeded `Random`, `ANDROID_SERIAL=emulator-5554`, fresh install before connected runs). Commits `feat(phase13): ...` with the trailer `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

### Task 1: Vocabulary tiers and a gender column (DB v9)

**Files:**
- Modify: `core/data/{Entities,Daos,AppDatabase,ItemRepository}.kt` (`Item.tier: Int` default 1, `Item.gender: String?` M/F/N), `core/sync/SyncModel.kt`, `core/seed/{SeedManifest,SeedImporter}.kt`, `tools/seed/words.json` (+ `tier`, `gender` on every entry; + ~120 tier 3–5 words: abstract nouns «ελπίδα», «ιδέα», «πρόβλημα»; verbs «σκέφτομαι», «αποφασίζω», «εξηγώ»; adjectives «δύσκολος», «σημαντικός»; multi-syllable everyday words «φυσικοθεραπεία», «ραντεβού», «παραγγελία»), `tools/seed/fetch-arasaac.mjs` (incremental; text-led cards when no pictogram), `caregiver/content/{ItemEditViewModel,ItemEditScreen}.kt` (a gender chip row Α/Θ/Ο for nouns, a tier chip row), `core/greek/Articles.kt` (use `Item.gender` when present, else the current inference), `modules/wordcoach/WordCoachModule.kt` + `core/difficulty/Difficulty.kt` (word-coach dots → tier ceilings, cumulative)
- Test: `MigrationTest` 8→9, `SyncModelTest`, `SeedImporterTest` (tier/gender re-graded on unchanged seed rows, deterministic ids), `ArticlesTest` (gender column wins), `DifficultyTest`

- [ ] Additive migration + schema + registry; the seed extension with pictograms fetched; the importer re-grades `tier`/`gender` on unchanged seed rows (same rule as scripts); the editor chips; the sentence builder's level 5–8 pool grows to caregiver nouns with a gender.
- [ ] Commit `feat(phase13): vocabulary tiers and a gender for every noun (db v9)`.

---

### Task 2: The «SQL» tile

**Files:**
- Create: `modules/sql/{SqlModule,SqlViewModel,SqlScreen,SqlPuzzles,SqlRunner,SqlTables}.kt`
- Modify: `core/data/Entities.kt` (`ModuleId.SQL`), `AppGraph.kt` (`modules += SqlModule`), `core/settings/Settings.kt` (module toggle; ON by default for existing and new installs — he asked for it), `core/difficulty/Difficulty.kt` (SQL dots → puzzle levels), `today/ModuleGrid.kt` (tile «SQL», icon `Icons.Rounded.TableChart`), `caregiver/insights/{JourneyReport,ClaudeAdvisor}.kt` (module name), `docs/UX.md`
- Test: `SqlRunnerTest` (in-memory SQLite, both table sets, result-set equality), `SqlPuzzlesTest` (every puzzle at every level has exactly one correct answer, runs without error, 500 draws), `SqlViewModelTest`, instrumented `SqlFlowTest`

**Interfaces:**
- `SqlTables`: `HIS_LIFE` — `λέξεις(λέξη TEXT, κατηγορία TEXT, φορές INTEGER)` from his items and attempt counts (text only), `μέρα(ώρα TEXT, δραστηριότητα TEXT, λεπτά INTEGER)` from his sessions' modules; `TEXTBOOK` — `users(id, name, age, city)`, `orders(id, user_id, item, price)` (12 rows each, Greek names in `name`). Both built into a fresh in-memory `SQLiteDatabase.create(null)` per run.
- `SqlPuzzles.generate(level: Int, tables: SqlTables, random): Puzzle` with kinds by level: 1 «Βάλε τις λέξεις στη σειρά» (order 3–4 tiles: `SELECT name FROM users`), 2 «Ποια ερώτηση δίνει αυτό;» (a result table shown, 3 query options), 3 «Συμπλήρωσε τη λέξη που λείπει» (`SELECT … ___ users WHERE age > 30` with 3 keyword options), 4 «Γράψε την ερώτηση» typed (a target result shown; his query is RUN and its result set compared, order-insensitive; a syntax error is shown in Greek, never a crash), 5 two-table questions (`JOIN` or `WHERE user_id IN (…)`, order tiles or typed). Tiles ≥ 72dp; the result table rendered as a simple grid.
- Attempts: `itemId = "sql:level:N"`, `module = SQL`, CORRECT first try / ASSISTED after a nudge or a revealed answer / SKIPPED; `detail {kind, level, query, ok, ms, tries}`; `LevelProgression(1..5)` within the dot's band.
- The runner is read-only: the connection opens with `SELECT`-only statements allowed (reject anything that is not a single `SELECT`), a row limit of 50, a 2 s statement timeout via a background thread + `interrupt`.

- [ ] Tests first; implement; `installDebug`; screenshots of levels 1–5; commit `feat(phase13): the SQL tile runs his first queries`.

---

### Task 3: The «Βήματα» tile

**Files:**
- Create: `modules/steps/{StepsModule,StepsViewModel,StepsScreen,StepTasks}.kt`, `assets/seed/steps.json` (20 tasks × 3–6 steps, each step a short Greek phrase with a pictogram keyword: «Φτιάχνω καφέ», «Πάω στο φαρμακείο», «Κλείνω ραντεβού», «Πληρώνω τον λογαριασμό», «Ετοιμάζομαι για το γυμναστήριο»…)
- Modify: `ModuleId.STEPS`, `AppGraph`, `Settings` (toggle on by default), `Difficulty` (dots → steps count 3/4/5/6/6+distractor), `ModuleGrid` (tile «Βήματα», icon `Icons.Rounded.FormatListNumbered`), `JourneyReport`/`ClaudeAdvisor` names, `docs/UX.md`
- Test: `StepTasksTest` (seed parses; every task's steps distinct; distractor never a real step), `StepsViewModelTest` (order check, nudge on a wrong order with the first wrong step highlighted, ASSISTED, then the telling stage with the judge/fake), instrumented `StepsFlowTest`

**Flow:** the task's steps are shown shuffled as picture+text tiles (three per row); he taps them in order into a strip (like the sentence builder); wrong order = nudge + the first wrong step highlighted, retry (ASSISTED); once ordered, stage 2 «Πες τα βήματα»: «Μίλα» → the judge (`Kind.SENTENCE`, target = «Πρώτα …, μετά …, τέλος …») accepts a telling that names the steps in order (with the judge off: `phraseMatches` over the joined steps); accepted → CORRECT for the telling (a second attempt row `itemId = "steps:tell:<task>"`); else the full telling is spoken for him to repeat once, then he may confirm. Bottom area: «Έτοιμο»/«Άκου»/«Παράλειψη», then «Μίλα»/«Άκου»/«Παράλειψη».

- [ ] Tests first; implement; commit `feat(phase13): the steps tile — first, then, last`.

---

### Task 4: Γράψε — dictation and typed sentences

**Files:**
- Modify: `modules/trace/{TraceModule,TraceViewModel,TraceScreen,Glyphs}.kt`, `core/difficulty/Difficulty.kt` (trace dots: 1 letters, 2 lowercase, 3 words by finger, 4 dictation, 5 typed sentences)
- Test: `TraceViewModelTest`/`TraceScorerTest` extensions, instrumented `TraceFlowTest` (+ dictation, + typed)

- [ ] Level 4 «Υπαγόρευση»: the word is SPOKEN (not shown); he writes it with the finger letter by letter on one paper (each letter judged with the phase-11 scorer against the letter he was expected to write; the paper advances per letter with a small «Επόμενο γράμμα»); a miss reveals the letter (ASSISTED). Level 5 «Γράψε την πρόταση»: a picture + the keyboard, judged by `Kind.SENTENCE` (accept spelling variants the judge accepts), CORRECT/ASSISTED; skipped when the judge is unavailable (typed boards need it).
- [ ] Commit `feat(phase13): writing from hearing, and typing a sentence`.

---

### Task 5: Sing-then-say on long sentences

**Files:**
- Modify: `modules/singsay/{SingSayModule,SingSayViewModel}.kt`, `tools/seed/words.json` (+ 20 PHRASE items of 7–12 syllables: «Θα ήθελα να κλείσω ένα ραντεβού για αύριο το πρωί», «Μπορείτε να μου πείτε πού είναι το φαρμακείο;»…), `core/difficulty/Difficulty.kt` (dots → syllable bands 4–5/6–7/8–9/10–11/12+)
- Test: `MelodyTest` (long phrases split into breath groups of ≤ 6 syllables with a rest between), `SingSayModuleTest`

- [ ] `Melody.forPhrase` splits long phrases into breath groups (at punctuation/«και»/«να», max 6 syllables) with a rest (`GAP_MS × 3`); the stage prompts unchanged; the module stays hidden unless enabled (phase 12).
- [ ] Commit `feat(phase13): singing the long sentences`.

---

### Task 6: Docs, verification, release

- [ ] `docs/HANDOVER.md` phase 13 section; `docs/UX.md` (+ two tiles); `docs/ADAPTATION.md` (+ `sql`, `steps` keys); README row; the advisor prompt names the two tiles.
- [ ] Full suites green (JVM, connected from a fresh install with the sync server, server); tag `v0.4.0`; verification notes; commit `docs(phase13): verification notes`.
- [ ] Chris: SQL levels 1–5 with Dimitris (which tables he prefers), «Βήματα» with a task from his real day, dictation by ear, a long sung sentence, difficulty 5 in Λέξεις with the new tier-5 words.

---

## Verification notes (2026-09-11)

Code tree `87f5ce8` (the docs commits after it change no behaviour): **1275 JVM tests**, 0 failures,
2 skipped — the two live-key Claude calls, and there is no key on this machine; **221 instrumented**
tests on `emulator-5554`, 0 failures, from a fresh install (`adb -s emulator-5554 uninstall
gr.dimitris.app` first) with the sync server **from this worktree** on 18787; **29 server tests**,
0 failures (`cd server && npm test`). Then `installDebug`, so what is on the emulator is what was
committed.

By hand on the emulator afterwards, with the app's data cleared so the first launch imported the seed
from nothing: the role screen, then the Today grid with **seven tiles** — «SQL» and «Βήματα» among
them on a phone nobody has ever configured, which is the whole of the "on for everybody" claim, and
all seven fit the screen. «SQL» opened on level 1 («Βάλε τις λέξεις στη σειρά. Από τον πίνακα λέξεις,
δείξε τη στήλη λέξη.»), with the «Δυσκολία» row above and «Άκου» / «Έτοιμο» / «Παράλειψη» below —
asking about his own `λέξεις` table on a phone with no practice on it at all. «Βήματα» opened on a
three-step task at dot 1 («Ανοίγω την τηλεόραση») with the empty strip reading «Εδώ μπαίνουν τα
βήματα.»; three taps filled it, «Έτοιμο» accepted the order, and the screen became the telling stage
(«Πες τα βήματα», the strip still in place, «Το είπα!» as the primary because recognition is off by
default). «Γράψε» at dot 4 showed the dictation board — one empty slot `_`, no word anywhere on the
screen, «Έτοιμο» really disabled until there is ink — and at dot 5, with «Έλεγχος με Claude» off
(the default), fell back to the word level with «Χρειάζεται τον έλεγχο με Claude.» on the screen,
which is the designed behaviour and not a failure. The talk board's category row ends at «Χρόνος»:
no «Τραγούδι» tab, so the sung sentences really are the singing tile's alone. The device database is
at `user_version = 9` with tiers 1–5 = 275/32/85/57/47, 213 rows carrying a gender and 20 filed under
`SINGING`; the bundled manifest is v5, 384 entries, 340 with a pictogram.

What no machine in this phase could check: **nothing was heard.** The emulator has no speech engine,
so the dictated word, every new Greek prompt and every sung sentence are verified by tests and
decision tables, never by ear. And **no live judge call was made** — «Γράψε» level 5 and «Βήματα»'s
telling have been judged only by the instrumented fake judge and by the local fallback.

Chris:

1. **«SQL», levels 1–5, with Dimitris — and which tables he prefers.** Watch level 4 hardest: typing
   SQL on a phone with one hand is the most demanding thing in the app. The row's `tables` column is
   what will answer "his own words or the textbook?" later; your eye is what answers it now.
2. **«Βήματα» with a task from his real day.** The twenty tasks are plausible, not observed, and
   thirteen of them carry my judgement about which steps may swap. Tell me where the groups are wrong.
   Also watch whether the telling («πρώτα… μετά… τέλος») is an exercise or a wall — the first telling
   of his life in this module is very likely to be an assisted one, by design.
3. **Dictation by ear** (dot 4 of «Γράψε»), on a phone with a voice. The word must be *only* a sound,
   «Άκου» must repeat it as often as he wants, and a letter he gets wrong must appear for him to trace
   over rather than stop him. Check too whether he writes the accents — nothing marks him on them, and
   `letters[].accent` is the only place the answer is kept.
4. **A long sung sentence, heard.** «Θα ήθελα να κλείσω ένα ραντεβού για αύριο το πρωί» is twenty
   syllables in five breath groups. Whether the breaths fall where a person would take them is a thing
   only an ear can say; listen at «Ρυθμός: Αργός» as well.
5. **Difficulty 5 in «Λέξεις», with the new tier-5 words.** «ελπίδα», «ευθύνη», «εμπιστοσύνη» —
   abstract nouns, some with a loosely related ARASAAC drawing and sixteen with none. If a picture is
   getting in the way rather than helping, text-led is one flag per word.
6. **A six-step «Βήματα» board on your own screen.** With four lines in the strip the tiles go below
   the fold. Both cheap fixes are worse (a shorter strip line stops being a 72 dp target; hiding the
   board while he reads the strip is the reshuffle this app avoids everywhere else), so it was left
   alone — but it wants a real look before it reaches him.
7. **Leaving a long sung sentence halfway.** Anything over about six seconds of melody now streams to
   the audio track instead of going out in one buffer. A stop *during* a streamed sentence — back, or
   «Στοπ» — has never been observed on a device. If the tile hangs or keeps singing after you leave,
   that is where to look.
8. **The «Τραγούδι» shelf from a caregiver's side.** The chip is offered in the word editor like any
   other, and a phrase filed there is silently excluded from «Λέξεις» and «Μίλα». Nothing on her
   screen says so; if that confuses anyone, a one-line hint under the chips is the fix.
