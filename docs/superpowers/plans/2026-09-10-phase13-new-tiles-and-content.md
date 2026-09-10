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
