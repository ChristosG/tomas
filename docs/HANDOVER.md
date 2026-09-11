# Dimitris' App — handover (2026-09-11)

**Phases 0–13** are implemented on branch `worktree-phase0`: phases 0–10 of
`docs/superpowers/specs/2026-09-05-dimitris-app-design.md`, then **phase 11** (polish after the first
field test), **phase 12** (§13 of the spec — the re-scope after Dimitris tried it himself and said
it was too easy) and **phase 13** (the rest of §13: the two new tiles, and content hard enough for the
man it describes). Nothing has been merged into `master` and nothing has been pushed anywhere: the
merge is your call.

This file no longer names a commit, because the last one it named was two phases stale by the time
anybody read it: `git log --oneline master..worktree-phase0` is the branch's real state, and
`git log -1` is the real head. Phases 11, 12 and 13 have their own section below, each with its own
"what only you can verify" list.

## What is in the APK

| Module | Greek title | Notes |
|---|---|---|
| Talk board (AAC) | Μίλα | quick phrases, categories, sentence strip, favourites, caregiver photos and voices |
| Word coach | Λέξεις | Leitner boxes 1–5, cue ladder 0–4, record and compare, speech recognition when enabled; five vocabulary tiers since phase 13, and the dots are a tier ceiling |
| Number sense | Αριθμοί | fifteen levels: dots, number line, counting, number words, coins, prices, paying, sums and carries, the tables, division, change, the clock and days, word problems |
| Sing-then-say | Τραγούδα και πες το | two-note melody from Greek stress sung in breath groups of six syllables, five MIT stages, caregiver sung model, 52 seed phrases — 20 of them whole everyday sentences since phase 13; **off by default since phase 12** |
| Dialogues | Διάλογοι | 14 seed scripts with tiers and intents, open answers judged for sense, caregiver editor with the other side's voice, cue ladder on his lines |
| Sentence builder | Προτάσεις | eight levels, accusative objects, distractor from level 4, articles and prepositions and clauses, gap-fill and typed boards |
| Steps of a task | Βήματα | **new in phase 13**; 20 everyday tasks of 3–6 steps, put in order and then told with «πρώτα… μετά… τέλος»; a step belonging to another task joins the board at dot 5 |
| Trace and write | Γράψε | five levels — capitals, small letters, words with his finger, dictation, a typed sentence; writing from memory inside the word level; finger-sized tolerances, filled-glyph scoring, accents recorded and never marked |
| Right-hand arcade | Δεξί χέρι | off by default; tap, trace, drag, pinch with adaptive size per game |
| Beginner SQL | SQL | **new in phase 13**; five kinds of question over four small tables — two of them his own words and his own mornings, two from the textbook; his typed queries really run, read-only, 50 rows, stopped at two seconds |
| Caregiver mode | — | biometric gate, words/photos/voices, dialogues, settings, backup, errors, progress, Claude |
| Progress | Πρόοδος | minutes, streak, per-module accuracy, cue trend, mastered words, insights, level steppers |
| Claude advisor | Ρώτα τον Claude | optional; needs an API key in settings; text-only summary; two-sentence encouragement read aloud |
| Sync | Συγχρονισμός | self-hosted server in `server/`; role screen on first run; caregiver phones pull his progress |

Tests on the final tree: **1275 JVM** (2 skipped — the two live-key Claude calls), **221 instrumented**
on the emulator from a fresh install with a local sync server, **29 server**.

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

## Phase 13 (two new tiles, harder content)

The rest of spec §13: the two tiles phase 12 could not fit, and content hard enough for the man §13
describes. Nothing was taken away from him — every change below is a new tile, a harder rung on a
ladder he already had, or words that were not there before.

- **Two new tiles, and they are on for everybody.** «SQL» and «Βήματα» are not in
  `Settings.DEFAULT_OFF`, so both are on the Today grid the day this build lands — on a phone that has
  been in use for months exactly as on a new one. There is no grandfathering pass, because *adding* a
  tile is not the kind of change that needs one; either can be switched off in Ρυθμίσεις → «Ασκήσεις».
  The grid stays at two columns and can now hold nine tiles. On a fresh install seven are on — the two
  new ones among them, and all seven fit the screen; with «Τραγούδα» and «Δεξί χέρι» switched on as
  well, the last row is cut across, which is what tells him there is more below.
- **«SQL» — his first queries, run for real.** Five levels are five kinds of question: put the words of
  a query in order, choose the query that gives a result, fill in the missing keyword, write the query
  yourself, and two tables at once (a `JOIN`, or the same question with `IN`). Four tables: two about
  his own life — `λέξεις(λέξη, κατηγορία, φορές)`, his own vocabulary and how often «Λέξεις» has asked
  him for each, and `μέρα(ώρα, δραστηριότητα, λεπτά)`, his last six sittings — and two from the
  textbook, `users` and `orders`. A table is only asked about once it has six real rows, so a phone
  installed this morning is asked about the textbook alone: nothing invents a morning he did not have.
  What he types really runs, in a fresh in-memory SQLite that can only `SELECT`, at most 50 rows,
  stopped at two seconds by a watchdog. **A typo is not a wrong answer**: «Η ερώτηση δεν τρέχει.» with
  SQLite's own English in a small second line (he was a programmer — `near "FORM": syntax error` is the
  most useful sentence on that screen), no mark, no step towards the reveal, no row. «Άκου» reads the
  Greek question, never the answer, so it costs him nothing.
- **«Βήματα» — πρώτα, μετά, τέλος.** Twenty everyday tasks of three to six steps, as picture-and-text
  tiles. He taps them into a numbered strip; then, with the strip still in front of him, he **tells**
  them, and that is where the three connectors come from. A wrong order marks the **first** step out of
  place and nothing else, leaves the strip exactly as he built it, and the hole that step leaves is
  where the next tile he taps lands — one tile to move, not five. Thirteen of the twenty tasks have
  more than one right order (the four things that go into a suitcase, the water and the coffee into the
  briki), and the check knows it. Two attempt rows per task, because ordering and telling are two
  different abilities and the whole point is to see which one he loses.
- **«Γράψε»: the five dots are five different exercises again.** His own name came off the ladder — he
  knows all his letters, and tracing «Δημήτρης» six times is copying, not writing; it is now only the
  fallback for a phone with no vocabulary at all. Writing a word **from memory** moved *inside* dot 3 as
  that level's own progression: a word written with no help earns him the next one with nothing to
  follow. The two new dots are dictation (the word is *said* and never shown; he writes it letter by
  letter on one paper, and a letter that missed is put there for him to trace over) and a typed sentence
  of his own about a picture. The typed level needs «Έλεγχος με Claude»; without it the sitting is the
  word level's work and the first screen says «Χρειάζεται τον έλεγχο με Claude.» once.
- **Accents are recorded and never marked.** A tonos used to be eight required pieces of the letter, so
  «ί» written correctly without its mark was refused at every strictness — and every word the dictation
  can offer carries exactly one accented vowel. A contour shorter than a letter's shortest real stroke
  is now a *mark on* the letter: drawn, measured, and not one he has to go over. Whether he wrote it is
  kept on the row (`letters[].accent`) so the question "does he write the accents?" can be asked later;
  nothing on the screen ever turns on it.
- **«Λέξεις»: five tiers of vocabulary instead of two.** 169 new words — the long everyday words a
  chemist and a bank are made of, the verbs and adjectives of an opinion, and the abstract nouns he
  understands and could not previously be asked for. Every noun in the seed now states its **gender**,
  which is also what lets the sentence builder use a caregiver's own nouns at levels 5–8: before, only
  a noun whose ending the app could read was admitted. A caregiver's editor gains two chip rows,
  «Γένος» (Α/Θ/Ο, words only) and «Δυσκολία» (1–5).
- **«Τραγούδα και πες το» sings whole sentences, and breathes.** Twenty everyday requests («Θα ήθελα να
  κλείσω ένα ραντεβού για αύριο το πρωί», «Πού είναι η στάση του λεωφορείου;») and a melody that cuts
  anything longer than six syllables into breath groups — at a comma, else before «και»/«να», else at
  the most even word boundary — with a rest three times as long before each group and 16 dp of space in
  front of its first syllable. No new control. The module is **still off unless a caregiver switches it
  on** (phase 12). The twenty sentences live on a shelf of their own, «Τραγούδι», which keeps them out
  of «Λέξεις» and off the talk board: a naming exercise is not a twenty-syllable read-aloud. The
  caregiver's editor says so in one line under the category chips, and «Δοκίμασέ το» is off for a phrase
  filed there — that button runs the word coach, which is the one place those sentences must not go.
- **The database moved to v9** — `items.tier` and `items.gender`, additive migration, both optional on
  the wire. His phone and every caregiver phone must move to the phase 13 build together, as with v7
  and v8.

### The five dots, where phase 13 moved them

| Module | 1 | 2 | 3 | 4 | 5 |
|---|---|---|---|---|---|
| Λέξεις | the everyday words | + the phrases | + the long everyday words | + the verbs and adjectives of an opinion | + the abstract nouns |
| SQL | the words of a query in order | choose the query that gives a result | the missing keyword | write the query | two tables at once |
| Βήματα | three steps | four | five | six | six, and a step from another task |
| Γράψε | capitals | small letters | words with his finger | the word said, never shown | a whole sentence on the keyboard |
| Τραγούδα και πες το | up to 5 syllables | up to 7 | up to 9 | up to 11 | any length |

Every row but «Γράψε» is a **ceiling**: dot 4 still asks for the easy things as well, because «νερό» is
worth saying on the day he asks for «ελευθερία» and a sitting wants an easy exercise at each end.
«Γράψε» is one level per dot, because its five are five different exercises and being moved off the one
he chose by a good morning is not something he asked for. The caregiver's floor and ceiling in
Ρυθμίσεις → «Όρια δυσκολίας» work exactly as they did.

**A ceiling is not all a dot does, on the two new tiles.** A «SQL» sitting is built at **one** level, so
his tap writes that level too: dot 5 means a sitting of two-table questions, and the ceiling is what
bounds how far a finished sitting may promote him afterwards. Without that the dots were decoration — a
mixed sitting is three puzzles and the progression wants five results, so a man who only opens «Σήμερα»
would never have met a `WHERE`. «Βήματα» has no stored level at all, so its sitting is **drawn at the
dot**: half of it (rounded up) from tasks of that difficulty and the rest from under it, which makes the
one task of a mixed sitting the work he asked for rather than a 4-in-20 chance of it.

### What happens once, on upgrade (phase 13)

1. **«Γράψε» is renumbered.** Level 4 used to be words and now means dictation; level 5 used to be
   words from memory and now means a keyboard. A phone sitting on either lands on **level 3**, which is
   where the work he was actually doing now lives, and the dot follows it. A caregiver's floor of 4 or 5
   comes down to 3 for the same reason; a ceiling of 4 comes down to 3, a ceiling of 5 stays, because
   "everything" is still everything. It runs once, behind a flag, and a phone that never opened «Γράψε»
   is untouched. **It cannot be undone:** a caregiver who had deliberately set his ceiling to 4 gets 3,
   and only her stepper puts it back.
2. **«SQL» and «Βήματα» come on by themselves.** See above — deliberate, and the opposite direction
   from phase 12's «Τραγούδα» grandfathering.
3. **The bundled words are re-graded.** The seed manifest is at v5, so on the next launch every seeded
   word that **nobody has edited** (our own id, the text unchanged, `updatedAt` at or below the seed's
   own stamp) takes the manifest's `tier`, `gender` and `category`. A word a caregiver has re-typed,
   re-filed or deleted is never touched, and neither is a word of her own that happens to have the same
   text. Without that pass the new tiers would have reached only phones installed after this build.

### Decisions made on his behalf (phase 13), and what they cost

- **The steps' groups are a judgement about his kitchen, not a fact.** Thirteen tasks accept more than
  one order, and I was generous where it was arguable — salt before the water boils, clothes before
  detergent. One number in `steps.json` changes any of them, but nobody has watched him do one yet.
- **«Ετοιμάζομαι να κοιμηθώ» leans on its distractor.** Grouping that task honestly left five
  interchangeable steps and one fixed one (the light goes out last), so at dot 5 most of what it tests
  is spotting the foreign tile rather than sequencing. The alternative was to delete a task he might
  really do; it was kept.
- **The accent rule is a measurement, not a proof.** "A contour shorter than eight pieces is a
  diacritic" was measured on the emulator's own font and pinned there for «Ο», «ι», «Ξ», «α», «ζ», «ξ»,
  «ς» and his name. A font with much shorter «Ξ» bars would be classified the same way. Check it on your
  phone's font if a letter ever passes that should not.
- **The twenty sung sentences belong to the singing tile alone.** As `PHRASE` items they would otherwise
  have become word-coach targets (a twenty-syllable read-aloud in a naming exercise) and twenty
  text-only cards on the talk board (clutter, for a slow reader). The «Τραγούδι» shelf is how they are
  kept out. The cost: a few genuinely useful request cards are missing from the talk board, and a
  caregiver who files one of her own phrases there will find it excluded from both — which is correct,
  and the line under the chips in her editor is where it is now said.
- **Nothing new appears at the default dot.** The vocabulary's default is still dot 2, so the new
  words are waiting behind a tap of his own. Moving the default would have changed every module's
  difficulty on upgrade, which is not a thing this phase should do quietly.
- **«SQL» level 5 is the textbook's two tables only.** `λέξεις` and `μέρα` are two facts about him and
  share no key, so there is nothing to join — which is also why every book teaches the join with
  `users` and `orders`. It does mean the hardest level never asks about his own life.
- **The twenty step tasks are fixed, and there is no editor for them.** At dot 1 there are only four, so
  every sitting at that dot is those four in a different order. A seed bump or a later editor is the
  answer if he tires of them.
- **24 of the 322 bundled single words ship with no picture** (16 abstract ones ARASAAC never drew, 8 where the
  only drawing available already belonged to another word). Their cue ladder is one rung shorter by
  design; nothing else about them differs, and a caregiver can photograph any of them.

### What only you can verify (phase 13)

1. **«SQL», levels 1 to 5, with Dimitris — and which tables he prefers.** The whole tile was his idea
   through you; nobody knows yet whether the questions about his own words land better than the
   textbook's, and the attempt row's `tables` column is there to answer it later. Watch level 4 in
   particular: typing SQL on a phone with one hand is the most demanding thing in the app.
2. **«Βήματα» with a task from his real day.** The twenty tasks are plausible, not observed. Pick one he
   actually does this week, watch the order he chooses, and tell me where the groups are wrong — and
   whether the telling («πρώτα… μετά… τέλος») is the exercise it was meant to be or a wall.
3. **Dictation by ear, on a phone with a voice.** The emulator has no speech engine, so level 4 has only
   ever been driven by tests. On your phone: the word must be *only* a sound, «Άκου» must repeat it as
   often as he likes, and a letter he gets wrong must appear for him to trace over rather than stop him.
4. **A long sung sentence, heard.** «Θα ήθελα να κλείσω ένα ραντεβού για αύριο το πρωί» is twenty
   syllables in five breath groups. The timing is proved by a test; whether the breaths fall where a
   person would take them is a thing only an ear can say. Listen at «Ρυθμός: Αργός» too.
5. **Difficulty 5 in «Λέξεις», with the new tier-5 words.** «ελπίδα», «ευθύνη», «εμπιστοσύνη» — abstract
   nouns, most of them with a loosely-related ARASAAC drawing or none at all. If the pictures are
   getting in the way rather than helping, they are better off text-led, and that is one flag per word.
6. **A six-step «Βήματα» board on your own screen.** With four lines in the strip the tiles go below the
   fold, so he has to scroll between the strip and the board. The two cheap fixes are both worse (a
   shorter strip line stops being a 72 dp target; hiding the board while he reads the strip is the
   reshuffle this app avoids everywhere), so it was left alone — but it wants a real look before it
   reaches him.
7. **Leaving a long sung sentence halfway through.** Anything over about six seconds of melody now
   streams to the audio track instead of being written in one buffer, which is new code in a delicate
   class. It is exercised by one 18-second test; a stop *during* a streamed sentence — pressing back, or
   «Στοπ» — has never been observed on a device. If the tile ever hangs or keeps singing after you
   leave, that is where to look.
8. **The «Τραγούδι» shelf, from a caregiver's side.** The category chip is offered in the word editor
   like any other, and a phrase filed there is silently excluded from «Λέξεις» and «Μίλα». Nothing on
   her screen says so. If that is confusing when somebody else uses the editor, a one-line hint under
   the chips is the fix.

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
- **One button is deliberately dead for a moment.** «Παράλειψη» is off for the two seconds a SQL query of
  his is inside SQLite, because the only way to cancel it is to throw away work he can see on the screen.
  The three judged boards — Γράψε level 5, «Προτάσεις» typed, «Βήματα»'s telling — keep theirs live and
  **cancel the judge** instead: the verdict he did not wait for is thrown away and the row says SKIPPED.
  The back arrow works throughout and writes nothing.
- **A «Βήματα» sitting plans in whole tasks, so a mixed sitting is one exercise shorter.** A task is two
  attempt rows, and the session's minimum share is three items — which now buys one task and promises two,
  rather than promising three and writing two. The sitting loses an item it could not have run.
- **The seed assets are 5.2 MB** — 326 vocabulary pictograms plus 92 for the step tasks, all in git. If
  the seed keeps growing at this rate they want to move out of the repository.
- **A caregiver who files one of her own phrases under «Τραγούδι»** will find it excluded from «Λέξεις»
  and from the talk board. That is what the shelf is for, and nothing on her screen says so.
- **The syllable counter is coarse**, so the sing-then-say dots are slightly stricter than speech:
  «για», «μια» and «πιο» each count two. Changing it would move every module that counts syllables.

## Merge

From the main checkout: `git merge worktree-phase0` on `master`, then `./gradlew -q testDebugUnitTest`. The worktree is `.claude/worktrees/phase0` (locked by the session that created it).
