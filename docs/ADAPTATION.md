# What every exercise records, and what it could adapt

**Nothing in the app adapts from any of this yet.** Two things do adapt today and they did before this
document existed: the numbers, sentences and trace *levels* (`LevelProgression`, `NumberProgression`)
and the arcade *target size* (`Adaptive`). Everything below is data being kept so that the rest can
one day be set from Dimitris rather than guessed at — the melody's tempo, how long the recogniser
waits for him, how hard the writing is marked, how long a sitting is.

Chris' rule, and the reason this file exists: *keep the data now, wherever adaptation could help him
later.* A rule needs months of rows before it can be argued with, and rows that were never written
cannot be written retrospectively. Writing them costs a few dozen bytes per exercise.

Everything goes into one column that already existed — `attempts.detail`, which is JSON — so there is
no schema change here and there never needs to be one. A new question is a new key.

## Where it lives

| | |
|---|---|
| Table | `attempts`, one row per finished exercise, append-only |
| Column | `detail`, a JSON object; `"{}"` when there is nothing to say |
| Written by | `Adapt` in `core/data/Telemetry.kt`, used by every module's ViewModel |
| Rule | plain numbers, booleans and short words only — no file paths, no ids beyond the ones these rows already carried |
| Absent keys | a key with nothing to say is left out, never written as `null` |

The row's own columns carry the rest: `itemId`, `module`, `sessionId`, `startedAt`, `durationMs`,
`outcome`, `cueLevel`. `ms` in the detail is the same clock as `durationMs`; it is repeated inside
the JSON so that a query about timing does not have to join two shapes of data.

## Per module

### Λέξεις — word coach (`module = 'WORDCOACH'`, `itemId` = the word)

| Key | What it is |
|---|---|
| `listened` | how many times he pressed «Άκου» on this word |
| `hintMsFirst` | milliseconds from the word appearing to the first «Βοήθεια» |
| `ms` | milliseconds from the word appearing to «Το είπα!» or «Παράλειψη» |
| `takeMs` | how long his own recording ran |
| `peak` | the loudest sample of that recording, on `MediaRecorder`'s 0–32767 scale |
| `sttOn`, `sttWaitMs` | whether recognition was on, and the silence window it was given |
| `sttHeard`, `sttMatched`, `sttTries` | what the phone made of him (only with recognition on) |

**Knobs it could drive, and the first rule to try:**

- **The recognition wait** (`RecognizerIntents.COMPLETE_SILENCE_MS`, 3 s). Grow it by 2 s when
  `sttHeard` is absent on ≥ 3 of the last 5 takes with `sttOn` true. Shrink it back when 5 of 5 come
  back with words. This is Chris' own field report — "it stops listening too soon" — turned into a
  number the phone can check itself.
- **Where the cue ladder starts.** A median `hintMsFirst` under 3 s over a fortnight means he is
  asking for help immediately: start that word one rung down instead of at 0. Above 10 s, leave it —
  he is working, and taking the silence away would be the app interrupting him.
- **The silence threshold** (`Recorded.SILENCE_PEAK`, 8). It was measured on one emulator. Set it
  from the 5th percentile of `peak` over the takes he *confirmed*, on his own phone.

### Διάλογοι — dialogues (`module = 'SCRIPTS'`, `itemId` = the line)

Same keys as the word coach, plus `scriptId` and `position` (which dialogue, and which turn of it),
and three since phase 12:

| Key | What it is |
|---|---|
| `tier` | how hard this dialogue says it is, 1–5 — what the five dots select on (`ScriptLine.tier`) |
| `intent` | what a good answer to this turn has to convey, as the caregiver or the seed wrote it |
| `judge` | what the turn judge decided about his answer — see [The judge's verdict](#the-judges-verdict-judge) |

- Same two rules as above — the ladder and the recognition wait — because it is the same ladder.
- **Which intents he can already do.** An `intent` accepted first time across several dialogues is
  one he has; the dialogues worth writing next are the ones whose intents he never lands. That is a
  caregiver's writing job, and `intent` is what tells her which to write.
- **Which turns to re-record or re-word.** A `position` whose `hintMsFirst` is always short across
  several dialogues is a line that is too long or too unlike how he speaks, not a line he needs more
  practice at. That is a caregiver's edit, not a knob.

### Τραγούδα — sing-then-say (`module = 'SINGSAY'`, `itemId` = the phrase)

| Key | What it is |
|---|---|
| `stage` | the stage he reached, 1–5 (see `SingStage`) |
| `repsPerStage` | five numbers, stage 1 to stage 5 — so stage 2 is JSON index `[1]` |
| `msPerStage` | five numbers, stage 1 to stage 5: milliseconds spent in each |
| `tempo`, `key` | the melody settings in force (`SLOW`/`NORMAL`/`FAST`, `LOW`/`HIGH`) |
| `sung` | whether a caregiver's *sung* take existed for this phrase |
| `groups` | how many breath groups the phrase was sung in — 1 for everything the module had before phase 13 |
| `groupSyllables` | how long each group was, in syllables, in the order they are sung; absent when `groups` is 1 |
| `listened`, `takeMs`, `peak`, `ms`, `sttOn`, `sttWaitMs`, `sttHeard`, `sttMatched`, `sttTries` | as the word coach |

**Knobs, and the first rules to try:**

- **How long a sentence to ask for.** `groups` and `groupSyllables` are what make the rest of this
  row comparable since phase 13: "he stalls at stage 3" means one thing on a phrase of one breath
  and another on «Θα ήθελα να κλείσω ένα ραντεβού για αύριο το πρωί», which is five. Compare
  `msPerStage` and `repsPerStage` **within** a `groups` value, and move the difficulty dot down a
  step when a sitting's phrases of three groups or more cost twice what his two-group phrases cost,
  on two consecutive sittings. Up a step when four groups cost no more than two.

- **Melody tempo.** Slow down when the passes at stage 2, «Τραγούδα μαζί» — `repsPerStage` JSON
  index `[1]` — are more than 3 on two consecutive sittings. Speed back up one step when they are 1
  for a whole week.
- **Melody key.** Try the other key when stage 5, saying it alone — `msPerStage` JSON index `[4]` —
  costs more than double the sung stages put together for a fortnight: the phrase is sitting outside
  his comfortable range and the melody is not carrying him into speech.
- **Asking for a sung model.** When `sung` is false and the fading stage — `repsPerStage` JSON
  index `[2]` — costs 3 passes or more for a week, that phrase is worth a caregiver's own voice
  singing it. Say so on the caregiver screen; do not change the exercise.

### Αριθμοί — numbers (`module = 'NUMBERS'`, `itemId = 'numbers:level:N'`)

| Key | What it is |
|---|---|
| `type` | which exercise: `compare`, `line`, `count`, `word`, `coin`, `price`, `pay`, and since phase 12 `add`, `sub`, `mul`, `div`, `missing`, `change`, `clock`, `day`, `problem` |
| `exercise` | the question itself, whole, as it was generated |
| `answer` | the right option |
| `given` | the option he tapped; absent when he passed on it |
| `level`, `optionCount` | the level, and how many options were on the screen |
| `retries` | wrong taps before the row was written |
| `ms` | milliseconds from the question appearing to the answer |

**Knobs, and the first rules to try:**

- **Level** — already adaptive (`NumberProgression`). What these keys add is *why*: discount rows
  where `optionCount` is 2, because a wrong tap out of two is a coin toss and a right one is not
  evidence.
- **How far apart the options are** (`ExerciseGenerator.optionsAround`). When `abs(given - answer)`
  is 1 on most of his misses at a level, the distractors are too near — widen them and see whether
  the level becomes his.
- **Which exercise types come up.** A `type` whose accuracy is under half the level's average for a
  fortnight should come up less often at that level, not block promotion out of it.

### Προτάσεις — sentence builder (`module = 'SENTENCES'`, `itemId = 'sentences:level:N'`)

| Key | What it is |
|---|---|
| `tiles` | the sentence, in the order it should be built |
| `chosen` | the order he actually tapped; empty when he passed on it |
| `firstTry` | whether he got it without a wrong order |
| `listened` | how many times he pressed «Άκου» |
| `retries` | wrong orders before the row was written |
| `undo` | how many cards he took back off the strip |
| `level`, `ms` | the level, and how long the sentence took |
| `variant` | since phase 12: how this board asked — `BUILD` (cards), `GAP` (one small word missing), `TYPED` (he wrote the whole sentence). Absent on rows written before it, which are all `BUILD` |
| `judge` | on a `TYPED` board only: what the judge made of the sentence he wrote — see [The judge's verdict](#the-judges-verdict-judge) |

On a `GAP` board `chosen` is the one small word he picked; on a `TYPED` board it is the single
sentence he wrote. Same column, same question — "what did he put down?" — and `variant` says which of
the three to read it as.

**Knobs, and the first rules to try:**

- **Level** — already adaptive. Add one refusal to it: do **not** step him down on `ms` alone when
  `undo` is high and `firstTry` is true. That is a man composing carefully, which is the exercise.
- **Which word order to drill.** The first place `chosen` differs from `tiles` is the slot he loses.
  If it is the same slot across a fortnight — the object before the verb, say — that is a template
  to practise, and `SentenceTemplates` can weight it.
- **Sentence length.** Step the level down when the median `ms` at one level doubles over a
  fortnight even while `firstTry` holds: he is still right and it is costing him more than it did.

### Γράψε — trace and write (`module = 'TRACE'`, `itemId` = the word, or `trace:level:N`)

| Key | What it is |
|---|---|
| `text`, `level`, `hand` | what he wrote, at which level, with which hand he was told to use |
| `tries` | how many goes at this letter or word |
| `coverage`, `precision` | the two numbers the marking is made of, 0..1; absent on a skip |
| `meanDistance` | how far his ink ran from the letter, in canvas pixels |
| `letters` | the same marks letter by letter: `[{c, coverage, precision, ink}, …]`, where `ink` is that letter's own share of the line against how long the letter is. A letter that has a **diacritic** also carries `accent`: whether he wrote the mark. Nothing turns on it — the accent is spelling, and the two lines this scorer holds him to are about shape — but "does he write the accents?" is a real question and this is the only row that could answer it. On a dictated word each letter also carries `missed`: whether it had to be shown to him before he could write it |
| `inkRatio` | how much line he drew against how long the whole word is |
| `strictness` | `LOOSE` / `NORMAL` / `STRICT`, as a caregiver had it set |
| `strokes` | how many separate strokes the *marked* try took |
| `templateHeightPx` | how tall the letter came out, in the same pixels as `meanDistance`; absent where there was no paper at all |
| `ms` | milliseconds from the letter appearing to «Έτοιμο» |
| `variant` | since phase 13: `dictation` (level 4 — he heard the word and wrote it letter by letter) or `typed` (level 5 — he wrote a whole sentence about a picture). Absent on the levels that trace a shape, which is every row written before phase 13 |
| `fromMemory` | the word was written with nothing on the paper: the word level's own progression, earned by writing the word before it without help |
| `listened` | how many times he pressed «Άκου» for the word. It costs him nothing and is not help — at level 4 the word is *only* ever a sound — but a word he asks for four times is a word he could not hold |
| `noJudge` | a level-5 row that was **not** typed: he asked for the sentence level and «Έλεγχος με Claude» could not answer, so the sitting was the word level's work |
| `judge` | what Claude decided about the sentence he typed: `{source, accept, ms, expanded?}`, the same shape every other module writes |

**Knobs, and the first rules to try:**

- **Strictness.** Suggest «Χαλαρό» when the median `precision` is under 0.6 across a whole sitting;
  suggest «Αυστηρό» when it is over 0.95 for a week. A suggestion on the caregiver screen, never a
  silent change: how hard he is marked is somebody's decision, not the app's.
- **Which letters to practise.** A letter whose `letters[].coverage` is under the pass line on three
  sittings running goes on a practice list. That is one letter, not the word it was in.
- **Form, not tolerance.** A high `strokes` count against a low `coverage` is a letter he is drawing
  in the wrong pieces; loosening the tolerance would only hide it. Show the stroke order instead.
- **The ink budget** (`TraceScorer.INK_BUDGET`, 2.5 times a letter's own length) is the one number
  here that was set from synthetic traces. `letters[].ink` is what a real hand actually uses, so the
  rows can settle it: every row is a try that *passed* the budget — a trace that fails it is refused
  with «Πολύ μελάνι», which is a nudge and another go, and nothing is written until a letter is
  passed or passed on. So read these as margins. If they crowd 2.5, the budget is too tight for the
  hand writing them; if the largest of a month sits near 1, it could come down.
- **`templateHeightPx` adapts nothing by itself** and is here because without it none of the
  distances above can be compared between his phone and a tablet.
- **Which letters he cannot spell, as opposed to cannot draw.** A dictated letter with
  `missed: true` and a high `coverage` once it was shown is a letter he can *form* and could not
  *retrieve* — the opposite knob from the one above. Those go on a spelling list, not a tracing one,
  and the word level is where a tracing list belongs.
- **Whether level 4 is too long a word.** `listened` rising with the word's length across a
  fortnight is a working-memory ceiling rather than a hand problem: keep the dictation to the
  shorter half of the pool (`TraceViewModel.SHORT_POOL`) before stepping the dot down.
- **The accents, which nothing marks him on.** `accent: false` on every letter of a fortnight's
  dictated words is not a hand problem and not a spelling one either — the mark is not on the paper
  to copy, and nobody has ever asked him for it. If it is worth asking for, it is worth *asking*:
  a line on the caregiver screen, never a refusal on the writing screen.

### Δεξί χέρι — arcade (`module = 'ARCADE'`, `itemId = 'arcade:<game>'`)

| Key | What it is |
|---|---|
| `hits`, `misses` | the round |
| `sizeDp` | the target size he ended the game at — his difficulty, carried to tomorrow |
| `msPerTarget` | the **median** milliseconds one target took him, failed tries included |
| `missDistanceDp` | the **median** distance outside the target that his misses landed, in dp |
| `ms` | how long the whole game took |

`missDistanceDp` is absent for the pinch game, which has no distance to report.

**Knobs, and the first rules to try:**

- **The growth factor** (`Adaptive.GROW`, 1.15, and `SHRINK`, 0.92 — the same two numbers for every
  game and every hand). Per game: when the median `missDistanceDp` is under 4 dp, halve the growth —
  he nearly had it and a target that jumps 15 % is the app overreacting. Over 15 dp, double it.
- **Round length rather than target size.** `msPerTarget` climbing across a sitting is an arm
  tiring. Shorten the round (`TAP_TARGETS`, `DRAG_ROUNDS`) instead of growing the target, which is
  what the size rule would do and would be the wrong answer to the wrong question.

### SQL — beginner's queries (`module = 'SQL'`, `itemId = 'sql:level:N'`)

| Key | What it is |
|---|---|
| `kind` | how the board asked: `ORDER` (tiles into a query), `PICK` (three queries, one result), `KEYWORD` (one word missing), `WRITE` (he typed it) |
| `level` | 1–5, and the level *is* the kind: 1 order, 2 pick, 3 keyword, 4 write, 5 two tables (`ORDER` or `WRITE`) |
| `query` | what he put down — the tiles in the order he laid them, the option he tapped, or the query he wrote |
| `ok` | whether it was right |
| `tries` | how many goes he had, a refused statement included |
| `ms` | his thinking time, from the board being drawn |
| `tables` | which world the question was about: `HIS_LIFE` (`λέξεις`, `μέρα` — his own words and his own mornings) or `TEXTBOOK` (`users`, `orders`) |

`tries` counts every hand-in, including the ones that never became answers: a typo, or a statement
the guard refused by name. Those cost him nothing else — no mark, no step towards the reveal, no row
of their own. So on an `ok = true` row, `tries` above 1 is the column that says he was fighting the
keyboard rather than the question, and it is the only place that fight is visible: `outcome` is still
`CORRECT`, because getting the spelling right on the second go is not being helped. A query that ran
away and was stopped at two seconds is not even a try.

**Knobs, and the first rules to try:**

- **`tables`, first of all.** It is the one column nothing else in the app can give: whether he does
  better on questions about his own words than on the textbook's. Today the generator takes a table
  at random from the ones it can ask about (`SqlPuzzles.sources`), so the two worlds come up about as
  often as each other; if `HIS_LIFE` accuracy is consistently the higher, weight it — and that is a
  finding about motivation, not about SQL.
- **Which kind to drill.** The level *is* the kind, so `kind` and `level` say the same thing on every
  row but the level-5 ones, where both an `ORDER` and a `WRITE` board exist. Those two are the pair
  worth watching: `WRITE` far below `ORDER` at level 5 is a man who knows the shape and cannot
  produce it, which is what «Προτάσεις» measures about sentences — and it has the same answer, which
  is more ordering rather than an easier question.
- **`query` on a wrong `WRITE` row.** It is the query as he typed it. The first token that differs
  from the target is the thing he actually lost: `FORM` for `FROM` is a keyboard, a missing `WHERE`
  is the idea.
- **Whether six a sitting is right.** `ms` at level 4 is a man typing SQL on a phone with one hand.
  If the median doubles over the last two puzzles of a sitting, the sitting is too long
  (`SqlModule.PUZZLES_PER_SESSION`), not the level too hard.

### Μίλα — talk board (`module = 'TALKBOARD'`, `itemId` = the word)

| Key | What it is |
|---|---|
| `strip` | true when the tap also added the word to the sentence strip; absent otherwise |
| `stripLen` | how many words stood on the strip once this one joined it |

Nothing here is marked and nothing ever will be: a tap on the board is him speaking, and the app is
a voice, not an examiner. Rows without `strip` are still written as `{}`, exactly as before.

One row on this board *is* graded, and it is not a tap: **«Ολόκληρη»**, the expansion
(`itemId = 'talkboard:expand'`, `cueLevel = 3`, at most one per expansion).

| Key | What it is |
|---|---|
| `kind` | always `expand`, so the row can be told from a tap without parsing the id |
| `words` | the words he had put on the strip |
| `expanded` | the whole sentence the judge gave back |
| `judge` | see [The judge's verdict](#the-judges-verdict-judge) |
| `stripLen`, `sttOn`, `sttHeard`, `sttMatched`, `sttTries`, `ms` | as everywhere else |

**Knobs, and the first rules to try:**

- **The strip's length** (`SentenceStrip`'s cap). Raise it when `stripLen` reaches the cap on more
  than a fifth of the sentences he says: he is running out of room in sentences he wants to make.
- **When «Ολόκληρη» is offered.** It appears at two words. `stripLen` on the expansion rows says how
  many he actually had when he tapped it: if that is nearly always four or more, two is too eager a
  threshold and the button is in his way well before it is useful to him.
- **Whether the expansions are worth repeating.** An expansion row's `outcome` is `CORRECT` when he
  said the sentence back and the phone agreed, `ASSISTED` when he confirmed it himself, `SKIPPED`
  when he closed it. A month of `SKIPPED` is a feature he is being shown and does not want; a month
  of `CORRECT` is the therapy working, and the talk board is where to add more of it.

### The judge's verdict (`judge`)

Three modules write it — «Διάλογοι», «Προτάσεις» and the talk board's «Ολόκληρη» — and all three
write **the same shape**, built in one place (`Verdict.detail`) rather than in three view models that
would drift:

```json
"judge": {"source": "JUDGE", "accept": true, "ms": 1840, "expanded": "Πρέπει να πάρω τα φάρμακα."}
```

| Key | What it is |
|---|---|
| `source` | `JUDGE` when Claude decided it, `LOCAL` when the phone did — the toggle off, no key, or a call that failed |
| `accept` | whether the answer counted |
| `ms` | how long the verdict took to arrive, measured by the caller around its own call |
| `expanded` | the whole grammatical sentence handed back for him to repeat. **Absent** when there was none |

It is the only nested object any module writes, and the only one any new module may
(`Adapt.Detail.put(String, Map)`); everything else in a detail is a plain number, boolean or short
word. `expanded` is capped at 200 characters like every other string, because an attempt row leaves
the phone twice — it syncs to the father's server, and it goes to Claude inside the journey report.

**Knobs, and the first rules to try:**

- **Whether the judge is worth its latency.** `ms` with `source: "JUDGE"` is the whole cost of the
  feature. A median above about three seconds is a green button greyed for three seconds on every
  turn, and at that point the local fallback is the better experience even with a working key.
- **Whether it is actually running.** A month of `source: "LOCAL"` on a phone whose caregiver
  believes «Έλεγχος με Claude» is on is an expired key nobody was told about. The journey report now
  says so per module («χωρίς Claude N»); an insight rule could say it on the dashboard too.
- **Where the difficulty should go.** Accept rate per module, over `source: "JUDGE"` rows only, is
  the cleanest four-in-five signal the app has — the cue ladder's is muddied by «Άκου» on purpose.
  Above 90% for a fortnight is a dot that should go up.

### The five dots (`difficulty`)

**The dots are not on the attempt row**, and this is the one entry in this file that documents an
absence. They live in DataStore, one key per module — `difficulty_NUMBERS` and its seven siblings,
plus `difficulty_floor_*` / `difficulty_ceiling_*` for the caregiver's bounds — because a dot is a
*setting in force*, not a fact about one exercise, and it does not change between two rows of the
same sitting.

What is on the row is `level`, which the three levelled modules already wrote (phase 11) and which
moves *with* the dot: a dot picks a band of levels and the level is held inside it, so
`Difficulty.numbersDot(level)` and its two siblings read a row's dot back exactly. «Διάλογοι» and
«Δεξί χέρι» carry the thing the dot selects on instead — `tier` and `sizeDp` — which is the same
information one step closer to the exercise. «Λέξεις» and «Τραγούδα» carry neither: what the dot
selects there is a property of the *item* (its `kind`, its syllable count), so a row's dot is
recoverable only by looking the item up, and for a word a caregiver has since deleted, not at all.

The current dot per module is in the journey report's «Ανά άσκηση» section as `δυσκολία n/5`, so the
advisor can be asked which one to move next. If a later phase wants the dot *on the row* — to ask
"was this sitting run at 3 or at 4?" without joining against a preference that has since moved — one
`put("difficulty", …)` in each module's detail builder is the whole change, and this file is where to
say so.

### The sitting itself (`itemId = 'session:summary'`)

One extra row at the end of every Today session, written after the real ones have been counted. Its
`module` is the **last planned module** because a row has to have one; its `outcome` is `CORRECT`
because a sitting is not marked. See "What this costs" below.

| Key | What it is |
|---|---|
| `plannedModules` | the modules today's session was built from, in order |
| `plannedCount` | how many exercises were planned across all of them |
| `completed` | how many he really did (skips not counted) |
| `leftEarly` | true unless the last planned module finished by itself |
| `ms` | how long the sitting lasted, wall clock |
| `msPerModule` | time on exercises, module by module: `{"WORDCOACH": 120000, …}` |

**Knobs, and the first rules to try:**

- **How long a sitting is** (`SessionBudget.MAX_SESSION_ITEMS`, 15). Cut it by 3 when `leftEarly` is
  true on 3 of the last 5 sittings *and* `completed` is under half `plannedCount`. Raise it by 3
  when `leftEarly` is false and `ms` is under ten minutes for a week.
- **How the budget is shared.** It is shared evenly today. `msPerModule ÷ the module's planned
  share` is what one exercise really costs in each module — the trace module is minutes an exercise
  and the talk board is seconds — so share the sitting by time rather than by count.
- **How many modules a day is** (`ModuleRotation.MAX_MODULES`, 4). Drop to 3 when the last module in
  `plannedModules` is the one missing from `msPerModule` on 3 sittings running: he is never reaching
  it.

## How to read the data

The attempt rows are in the app database. Get it out with a backup (Ρυθμίσεις → Αντίγραφο
ασφαλείας), which writes `dimitris-<stamp>.zip` with `dimitris.db` at its root:

```sh
unzip -o dimitris-2026-09-06-1830.zip dimitris.db -d /tmp/dimitris
sqlite3 /tmp/dimitris/dimitris.db
```

Off a phone with USB debugging, `adb exec-out run-as gr.dimitris.app cat databases/dimitris.db >
dimitris.db` does the same — pull `dimitris.db-wal` and `dimitris.db-shm` beside it, or the last
sitting will be missing.

`module` and `outcome` are stored as their own names (`'WORDCOACH'`, `'CORRECT'`); `deleted` is 0 or
1; times are epoch milliseconds. `json_extract` needs SQLite 3.38 or the JSON1 extension, which every
current `sqlite3` has.

```sql
-- Every sitting: how long, how much of it he did, and whether he stayed to the end.
SELECT datetime(startedAt/1000, 'unixepoch', 'localtime')  AS at,
       json_extract(detail, '$.plannedCount')              AS planned,
       json_extract(detail, '$.completed')                 AS done,
       json_extract(detail, '$.leftEarly')                 AS left_early,
       json_extract(detail, '$.ms') / 60000                AS minutes
FROM attempts
WHERE itemId = 'session:summary' AND deleted = 0
ORDER BY startedAt DESC LIMIT 30;
```

```sql
-- The recognition-wait rule: the last five takes with recognition on.
-- Three or more empty 'heard' means give him two more seconds.
SELECT datetime(startedAt/1000, 'unixepoch', 'localtime') AS at,
       json_extract(detail, '$.sttHeard')                 AS heard,
       json_extract(detail, '$.sttTries')                 AS tries,
       json_extract(detail, '$.sttWaitMs')                AS wait_ms
FROM attempts
WHERE module IN ('WORDCOACH', 'SCRIPTS', 'SINGSAY')
  AND json_extract(detail, '$.sttOn') = 1 AND deleted = 0
ORDER BY startedAt DESC LIMIT 5;
```

```sql
-- The melody-tempo rule: passes at «Τραγούδα μαζί» (stage 2) per day.
-- Over 3 on two days running is a melody going too fast for him.
SELECT date(startedAt/1000, 'unixepoch', 'localtime')          AS day,
       ROUND(AVG(json_extract(detail, '$.repsPerStage[1]')), 1) AS reps_stage2,
       ROUND(AVG(json_extract(detail, '$.msPerStage[4]')), 0)   AS ms_speaking
FROM attempts
WHERE module = 'SINGSAY' AND deleted = 0
GROUP BY day ORDER BY day DESC LIMIT 14;
```

```sql
-- The arcade growth factor, per game.
SELECT itemId                                                    AS game,
       COUNT(*)                                                  AS games,
       ROUND(AVG(json_extract(detail, '$.missDistanceDp')), 1)   AS miss_dp,
       ROUND(AVG(json_extract(detail, '$.msPerTarget')), 0)      AS ms_per_target,
       ROUND(AVG(json_extract(detail, '$.sizeDp')), 1)           AS size_dp
FROM attempts
WHERE module = 'ARCADE' AND deleted = 0
GROUP BY itemId;
```

```sql
-- The trace strictness rule: median-ish precision per sitting day.
SELECT date(startedAt/1000, 'unixepoch', 'localtime')        AS day,
       ROUND(AVG(json_extract(detail, '$.precision')), 2)    AS precision,
       ROUND(AVG(json_extract(detail, '$.strokes')), 1)      AS strokes,
       MAX(json_extract(detail, '$.strictness'))             AS strictness
FROM attempts
WHERE module = 'TRACE' AND json_extract(detail, '$.precision') IS NOT NULL AND deleted = 0
GROUP BY day ORDER BY day DESC LIMIT 28;
```

```sql
-- How far off his wrong number answers are: 1 means the options are too close together.
SELECT json_extract(detail, '$.level')                                              AS level,
       json_extract(detail, '$.optionCount')                                        AS options,
       ROUND(AVG(ABS(json_extract(detail, '$.given') - json_extract(detail, '$.answer'))), 2) AS off_by
FROM attempts
WHERE module = 'NUMBERS' AND json_extract(detail, '$.given') IS NOT NULL
  AND json_extract(detail, '$.given') != json_extract(detail, '$.answer') AND deleted = 0
GROUP BY level, options ORDER BY level;
```

## What this costs, and what to watch

- **The summary row is counted as an exercise by the per-module numbers.** `ProgressStats.compute`
  counts every attempt row of a module, so one extra `CORRECT` lands on whichever module was planned
  last, on every sitting; the same row adds one to that day's exercise count. It is correctly
  *excluded* from everything that is about a word — `lifetime`, `recentByDay`, «Δύσκολες λέξεις»,
  «Μαθημένες λέξεις» — because those all test `items[itemId] != null` and `session:summary` is not a
  word. It is also excluded from the minutes, which only reconstruct sittings from rows with no
  `sessionId`. **The fix is one line in `ProgressStats` and one in `ModuleRotation`:** drop rows
  whose `itemId` is `session:summary` before counting. Until then, a module's attempt count is
  overstated by one per sitting, and `ModuleRotation` will think the last planned module was
  practised even on a sitting he never reached it in.
- **Nothing reads any of these keys.** They are written and synced and that is all. A rule only goes
  in once there are rows to argue with it about, and every rule above is a guess until then.
- **Nothing here leaves the phone that did not already.** An attempt row syncs to the father's
  server and goes to Claude inside the journey report, so `Adapt` refuses anything that is not a
  plain number, boolean or short word. The three exceptions — the trace module's per-letter marks,
  the numbers module's whole exercise, the dialogue's `scriptId` — were on these rows before this
  file existed and go through `Adapt.Detail.kept`, which is named so they can be found at a glance.
