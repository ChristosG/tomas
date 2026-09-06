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

Same keys as the word coach, plus `scriptId` and `position` (which dialogue, and which turn of it).

- Same two rules as above — the ladder and the recognition wait — because it is the same ladder.
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
| `listened`, `takeMs`, `peak`, `ms`, `sttOn`, `sttWaitMs`, `sttHeard`, `sttMatched`, `sttTries` | as the word coach |

**Knobs, and the first rules to try:**

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
| `type` | which exercise: `compare`, `line`, `count`, `word`, `coin`, `price`, `pay` |
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
| `letters` | the same marks letter by letter: `[{c, coverage, precision}, …]` |
| `inkRatio` | how much line he drew against how long the letter is |
| `strictness` | `LOOSE` / `NORMAL` / `STRICT`, as a caregiver had it set |
| `strokes` | how many separate strokes the *marked* try took |
| `templateHeightPx` | how tall the letter came out, in the same pixels as `meanDistance` |
| `ms` | milliseconds from the letter appearing to «Έτοιμο» |

**Knobs, and the first rules to try:**

- **Strictness.** Suggest «Χαλαρό» when the median `precision` is under 0.6 across a whole sitting;
  suggest «Αυστηρό» when it is over 0.95 for a week. A suggestion on the caregiver screen, never a
  silent change: how hard he is marked is somebody's decision, not the app's.
- **Which letters to practise.** A letter whose `letters[].coverage` is under the pass line on three
  sittings running goes on a practice list. That is one letter, not the word it was in.
- **Form, not tolerance.** A high `strokes` count against a low `coverage` is a letter he is drawing
  in the wrong pieces; loosening the tolerance would only hide it. Show the stroke order instead.
- **`templateHeightPx` adapts nothing by itself** and is here because without it none of the
  distances above can be compared between his phone and a tablet.

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

### Μίλα — talk board (`module = 'TALKBOARD'`, `itemId` = the word)

| Key | What it is |
|---|---|
| `strip` | true when the tap also added the word to the sentence strip; absent otherwise |
| `stripLen` | how many words stood on the strip once this one joined it |

Nothing here is marked and nothing ever will be: a tap on the board is him speaking, and the app is
a voice, not an examiner. Rows without `strip` are still written as `{}`, exactly as before.

- **The strip's length** (`SentenceStrip`'s cap). Raise it when `stripLen` reaches the cap on more
  than a fifth of the sentences he says: he is running out of room in sentences he wants to make.

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
