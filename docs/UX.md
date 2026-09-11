# Every screen, and what is under his thumb

Spec §13 added one rule to the six that were already there: **the interface stays simple whatever
the difficulty.** In full:

> one primary action per screen, at most three actions in the bottom area, every new activity is a
> tile on the grid, and difficulty is one row of five dots he can tap himself, bounded by the
> caregiver.

This file is the audit of that rule, screen by screen, and it is meant to be re-run — by hand, or by
reading the `bottom = { … }` block of every `DimitrisScreen` call — whenever a phase adds a control.

**What counts as a bottom action.** Everything inside `DimitrisScreen(bottom = …)`: the block pinned
under the content, where a left thumb lands. The back arrow and the talk-board icon in the header do
not count (they are the same two controls on every screen, in the same place, and they are not part
of the exercise). Neither does anything in the *content*, which is where a control goes when the
bottom area is full — «Βοήθεια» in the dialogues, «Το έγραψα» in the sentences, «Το έκανα» in
sing-then-say. That is a deliberate escape hatch, not a loophole: a control in the content sits next
to the thing it is about, and a man scanning one block of buttons for the one he wants is not helped
by a fourth.

**What counts as the primary.** The one action that moves the exercise forward. In code that is a
`BigButton` with `ButtonTone.Primary` (navy) or `Success` (green), and a screen state may hold
exactly one of them: a *state* with two is a violation even if no single frame shows both. Where a
state has neither, the primary is the `Secondary`-toned button that is the way on — the sing-then-say
tap pad, which is `Secondary` because it is not a confirmation, and is marked out instead by being
110 dp tall rather than the usual 72.

**«Άκου» is not counted.** It is a filled `Secondary` button, so it is loud on purpose: spec §12 says
the model is never withheld and it must not be made to look like a lesser choice either. It is in the
same place on all four speech screens, it is never the way forward, and it is never a screen's
primary. `QuietButton` is the app's genuinely secondary control — outlined, and used for «Παράλειψη»,
«Καθάρισε», «Βοήθεια» and the rest.

---

## Dimitris' side

| Screen | State | Bottom actions | Primary |
|---|---|---|---|
| Τίνος είναι αυτό το τηλέφωνο; (`RoleScreen`) | once, first launch | — (both choices are the content) | «Του Δημήτρη» |
| Σήμερα (`TodayScreen`) | always | «Μίλα», «Ξεκίνα» | «Ξεκίνα» |
| Συνεδρία (`SessionScreen`) | nothing due / all off | «Εντάξει» | «Εντάξει» |
| | summary | «Εντάξει» | «Εντάξει» |
| | running | *(the module's own screen)* | |
| Εξάσκηση (`PracticeScreen`) | no material yet | «Εντάξει» | «Εντάξει» |
| Λέξεις (`WordCoachScreen`) | listening | «Στοπ» | «Στοπ» |
| | answered | «Άκου», «Επόμενο» | «Επόμενο» |
| | otherwise | «Μίλα» / «Το είπα!», «Άκου», «Παράλειψη» | «Μίλα» / «Το είπα!» |
| Αριθμοί (`NumbersScreen`) | answered or revealed | «Επόμενο» | «Επόμενο» |
| | otherwise | «Παράλειψη» | — |
| | finished | «Εντάξει» | «Εντάξει» |
| Τραγούδα και πες το (`SingSayScreen`) | listening | «Στοπ» | «Στοπ» |
| | stages 1–4 (tapping) | «Χτύπα», «Άκου», «Παράλειψη» | «Χτύπα» |
| | stage 5 (saying it) | «Μίλα» / «Το είπα!», «Άκου», «Παράλειψη» | «Μίλα» / «Το είπα!» |
| | finished | «Εντάξει» | «Εντάξει» |
| Διάλογοι (`ScriptsScreen`) | listening | «Στοπ» | «Στοπ» |
| | his turn | «Μίλα» / «Το είπα!», «Άκου», «Παράλειψη» | «Μίλα» / «Το είπα!» |
| | the other person talking | «Συνέχεια» | — |
| | loading / finished | «Ετοιμάζω...» / «Τέλος διαλόγου» (both dead) | — |
| | finished | «Εντάξει» | «Εντάξει» |
| Προτάσεις (`SentencesScreen`) | correct | «Άκου», «Επόμενο» | «Επόμενο» |
| | typed board | «Άκου», «Έτοιμο», «Παράλειψη» | «Έτοιμο» |
| | gap board | «Άκου», «Παράλειψη» | — |
| | card board | «Άκου», σβήσε, «Παράλειψη» | — |
| | finished | «Εντάξει» | «Εντάξει» |
| Βήματα (`StepsScreen`) | listening | «Στοπ» | «Στοπ» |
| | ordering the steps | «Έτοιμο», «Άκου», «Παράλειψη» | «Έτοιμο» |
| | telling them | «Μίλα» / «Το είπα!», «Άκου», «Παράλειψη» | «Μίλα» / «Το είπα!» |
| | finished | «Εντάξει» | «Εντάξει» |
| Γράψε (`TraceScreen`) | passed | «Επόμενο» | «Επόμενο» |
| | recall word, still shown | «Καθάρισε», «Το είδα», «Παράλειψη» | «Το είδα» |
| | level 4 (dictation), clean paper | «Άκου», «Έτοιμο» (dead), «Παράλειψη» | — («Έτοιμο» wakes on the first stroke) |
| | level 4, ink on the paper | «Καθάρισε», «Έτοιμο», «Παράλειψη» | «Έτοιμο» |
| | level 5 (typed) | «Άκου», «Έτοιμο», «Παράλειψη» | «Έτοιμο» |
| | level 5, while «Διαβάζω...» | «Άκου», «Έτοιμο» (dead), «Παράλειψη» (dead) | — |
| | otherwise | «Καθάρισε», «Έτοιμο», «Παράλειψη» | «Έτοιμο» |
| | finished | «Εντάξει» | «Εντάξει» |
| Δεξί χέρι (`ArcadeScreen`) | playing | «Παράλειψη» | — |
| | finished | «Εντάξει» | «Εντάξει» |
| SQL (`SqlScreen`) | answered or revealed | «Άκου», «Επόμενο» | «Επόμενο» |
| | ordering board (levels 1, 5) | «Άκου», «Έτοιμο», «Παράλειψη» | «Έτοιμο» |
| | typed board (levels 4, 5) | «Άκου», «Έτοιμο», «Παράλειψη» | «Έτοιμο» |
| | choosing board (levels 2, 3) | «Άκου», «Παράλειψη» | — |
| | finished | «Εντάξει» | «Εντάξει» |
| Μίλα (`TalkBoardScreen`) | always | «Πες το», σβήσε, καθάρισε | «Πες το» |

Nine of his screens carry a control in the *content* rather than in the bottom area. Every one of
them is listed here, deliberately: the rule below is that a content control is an escape hatch and
not a loophole, and a list that is only *mostly* complete is how the loophole gets in.

- **Λέξεις**: «Βοήθεια», beside the picture it is a hint about; and the second «Μίλα» offered after
  the phone has asked him twice.
- **Διάλογοι**: «Βοήθεια», inside the turn card; and the second «Μίλα».
- **Τραγούδα και πες το**: «Το έκανα», under the syllables; «Ηχογράφηση» (only on the fallback
  recogniser path, where the window does not keep his own take); and the second «Μίλα».
- **Προτάσεις**: «Το έγραψα», under the sentence he typed.
- **Γράψε**: «Το έγραψα», under «Σωστά: …» on the level-5 typed board — the same control in the same
  place as the sentence builder's, because it is the same exercise and the bottom block is already
  «Άκου» / «Έτοιμο» / «Παράλειψη».
- **Αριθμοί**: «Άκου», between the question and the options — the question said again, next to the
  question, where the bottom area holds only «Παράλειψη».
- **SQL**: the words he has already laid down, in the strip above the board — tapping one takes it
  back. The undo lives next to the query he is building because the bottom block is already
  «Άκου» / «Έτοιμο» / «Παράλειψη», and because the thing being undone is *that word in that place*.
  The three options of a «διάλεξε» board are content too, like the numbers module's answers: they are
  the question, not an action.
- **Βήματα**: the steps he has already put in the strip — tapping one takes it back. The undo lives
  next to the sequence he is building, because the bottom block is already «Έτοιμο» / «Άκου» /
  «Παράλειψη» and because the thing being undone is *that step in that place*. The empty slot the mark
  leaves behind is content and **not** a control: nothing happens when it is touched, it only says
  where the next tile he taps will land. The whole telling, left on the screen after one that did not
  land, is content too: it is there to be read and repeated, not pressed.
- **Μίλα**: the whole of «Ολόκληρη», inside the sentence strip. «Ολόκληρη» while the strip is only
  words; then, over the chips, the sentence with «Μίλα» / «Το είπα!» and «Κλείσε» under it, and
  «Στοπ» alone while the microphone is open. The board's own three at the bottom («Πες το», σβήσε,
  καθάρισε) never move and never grow — which is why this lives in the strip and not beside them.

The row of five difficulty dots is content too, on the first screen of each module and nowhere else.

## The caregiver's side

Nothing here is one-handed or aphasia-facing, but the same audit is worth having: a caregiver
holding a phone in a hospital corridor is not reading a form either.

| Screen | Bottom actions | Primary |
|---|---|---|
| Φροντιστής (`CaregiverHomeScreen`) | «Πίσω στον Δημήτρη» | — (the entries are the content) |
| Λέξεις και φωτογραφίες (`ItemListScreen`) | «Νέα λέξη» | «Νέα λέξη» |
| Λέξη (`ItemEditScreen`) | «Δοκίμασέ το», «Αποθήκευση» | «Αποθήκευση» |
| Διάλογοι (`ScriptListScreen`) | «Νέος διάλογος» | «Νέος διάλογος» |
| Διάλογος (`ScriptEditScreen`) | «Παίξ' το», «Αποθήκευση» | «Αποθήκευση» |
| Πρόοδος (`ProgressScreen`) | «Ρώτα τον Claude», «Εξαγωγή αναφοράς» | «Ρώτα τον Claude» |
| Συμβουλή (`AdviceScreen`) | «Ρώτα τον Claude» (+ status lines, not actions) | «Ρώτα τον Claude» |
| Σφάλματα (`ErrorListScreen`) | «Καθαρισμός» | — |
| Ρυθμίσεις (`SettingsScreen`) | — (a scrolling list of switches) | — |
| Αντίγραφο (`BackupScreen`) | «Εξαγωγή και αποστολή», «Εισαγωγή από αρχείο» | «Εξαγωγή και αποστολή» |
| Συγχρονισμός (`SyncScreen`) | «Συγχρόνισε τώρα» | «Συγχρόνισε τώρα» |

---

## What this audit changed (phase 12, task 8)

Two of his screens were over the line and are not any more.

**Τραγούδα και πες το, the four tapping stages: four actions, two of them loud.** The bottom held
the tap pad, then a row of «Άκου» and «Το έκανα», then «Παράλειψη». «Το έκανα» has moved into the
content, under the syllables it is about — it is his to take and never asked of him, which is
exactly the kind of control that belongs beside its subject rather than in the block his thumb
sweeps. The bottom is now the pad, «Άκου», «Παράλειψη», like every other speech screen in the app.

**Γράψε at level 5, while the letter is still showing: four actions, two of them loud.** «Το είδα»
stood above the «Καθάρισε» / «Έτοιμο» row — and «Έτοιμο» was dead anyway, because there is nothing
to hand in until he has taken the letter away. The two now share one slot: the primary is whichever
of them is the actual next step, and it changes only on his own tap.

**«Τραγούδα και πες το» is now off unless a caregiver switches it on** ([`Settings.DEFAULT_OFF`]).
It is the answer to a phrase that will not come out at all — «Για μεγάλες φράσεις που δεν βγαίνουν
ακόμα.», which is what the switch in Ρυθμίσεις says — and a man who says most everyday words should
not meet it by default. **This is for new installs only**: a phone that already had the module keeps
it, because a build that quietly takes a tile off his Today screen because a default moved is the
app deciding something nobody asked it to decide. See `Settings.grandfatherNewlyDefaultOff`.

The module grid stays at two columns, which was already true and is worth writing down: the tiles
are 104 dp tall and a phone screen holds four rows of them under the greeting, with the row that
does not fit cut across rather than landing on the edge — a half-tile is what tells him there is
more below.

## What phase 13 added: the «SQL» tile

One new tile, and it is a tile like any other — that is the whole of what the audit has to say about
it. «SQL» is on the grid ([`Icons.Rounded.TableChart`]), it has the same row of five dots on its first
screen, its bottom block is «Άκου» / «Έτοιμο» / «Παράλειψη» in the order every other module uses them,
and it ends on «Εντάξει».

Two things about it are worth writing down because they were decisions and not defaults.

**«Άκου» reads the question, never the answer.** In the four speech modules «Άκου» *is* the model —
the word or the sentence he is being asked for — and it costs the row its first-try mark. Here the
question is Greek prose («Από τον πίνακα users, δείξε τη στήλη name όπου city = 'Αθήνα'») and the
answer is SQL, so hearing the question again gives away nothing at all. It costs him nothing, and the
attempt row carries no cue level: there is no ladder here to be on.

**The strip he lays the words into does not grow.** It is one 96 dp row — a 72 dp tile and its
padding — that scrolls sideways. While it grew with what he had put down, every tile he laid pushed
the board below it further down, so on a four-word query the last word was off the bottom of the
screen by the time he needed it: on the one board whose whole exercise is reaching each word in turn.
The instrumented `SqlFlowTest` is what found it and what keeps it found.

**The board comes back after two seconds, whatever he wrote.** A query that cannot finish is stopped
by a watchdog that pulls SQLite's own `CancellationSignal` — a `withTimeout` cannot, because the
coroutine doing the reading is blocked inside `rawQuery` with no suspension point to resume at — and
the screen says «Η ερώτηση άργησε πολύ.» with «Έτοιμο» live again. It costs him nothing: a query that
never answered is not a wrong answer, and no row is written for it. Neither is a typo, and neither is
a statement the guard refuses by name.

**On by default, for everybody.** «SQL» is not in [`Settings.DEFAULT_OFF`], so it needs no
grandfathering pass: it is on the grid the day this build lands, on a phone that has been in use for
months as much as on a new one. Chris asked for it because Dimitris was a programmer and still does
very basic SQL exercises; the switch in Ρυθμίσεις is there for anyone who decides otherwise.

[`Settings.DEFAULT_OFF`]: ../app/src/main/java/gr/dimitris/app/core/settings/Settings.kt
[`Icons.Rounded.TableChart`]: ../app/src/main/java/gr/dimitris/app/modules/sql/SqlModule.kt

## What phase 13 added: the «Βήματα» tile

The second of the two new tiles, and the one that is not about words at all: a task in three to six
steps, put in order and then told. «Βήματα» is on the grid ([`Icons.Rounded.FormatListNumbered`]), it
has the same row of five dots on its first screen, and it ends on «Εντάξει». Four things about it were
decisions rather than defaults.

**Two stages, one screen, and the buttons do not move between them.** The strip he builds while
ordering is the strip he reads from while telling, in the same place — a second screen would have taken
it away at the moment it became useful. The bottom block is therefore the same three places in both
stages: the green primary on top («Έτοιμο», then «Μίλα» / «Το είπα!»), «Άκου» under it, «Παράλειψη» at
the foot. The app has two conventions for that order — the four speech modules put the primary first,
«SQL» and «Προτάσεις» put «Άκου» first — and a screen that switched from one to the other halfway
through a task would move a button under his thumb between one tap and the next. It picks the speech
one, because the second stage *is* speech.

**«Άκου» says two different things, and only one of them costs him anything.** While he is ordering it
reads the task and the strip **as it stands** — his own order, said back to him, which is how a man who
reads slowly checks his own work without being handed the answer — so it is free, exactly as it is in
«SQL». While he is telling it reads the whole telling, which *is* the answer, so the row carries
[`CueLadder.LISTENED`] and the exercise comes out as assisted work. Same button, same place, never
withheld (spec §12); what changes is what the row says.

**One step is marked, never four — and it is one tile to move.** A wrong order marks the **first** step
that is out of place and nothing else, and the strip is left exactly as he built it. Marking every step
after the first one as well would be true and useless: a man who put step 3 where step 2 goes has one
tile to move, and a strip of four marks reads as "you got it all wrong", which is both untrue and the
one thing this app may never say.

The mark is also where the *next* tile goes. He taps the misplaced step out of the strip, the hole stays
open in its place — drawn as an outlined line with the number it will have, «Βάλε εδώ το σωστό βήμα.» —
and the next tile he taps on the board drops into it rather than onto the end. Without that the mark
cost him the tail: taking a tile out shifted everything up, so putting a step back into the middle meant
dismantling the rest of the strip, five tiles on a six-step task. The marked line also brings itself
into view, because on a six-step task the step that is out of place can be line 6 with the screen
sitting at line 1, and a mark he cannot see is a correction he cannot make.

**More than one order is right, and the app knows which.** Thirteen of the twenty tasks contain steps
whose order is his to choose — the four things that go into a suitcase, the water and the coffee into
the briki — so those steps share a *group* in the seed and the check compares the sequence of groups.
«Φτιάχνω τη βαλίτσα» accepts all twenty-four of its right answers. The seven tasks whose order is
causally forced end to end still have exactly one.

**Two attempt rows per task, and two of the session's items.** `steps:order:<task>` for the sequencing
and `steps:tell:<task>` for the telling, because they are two exercises and a reader who cannot tell
them apart cannot see the thing worth seeing — he orders well and tells badly, or the other way about.
The module's plan is therefore two placeholders per task, so what the session promised and what it
counts are the same number; the screen runs one task per pair it is handed.

[`Icons.Rounded.FormatListNumbered`]: ../app/src/main/java/gr/dimitris/app/modules/steps/StepsModule.kt
[`CueLadder.LISTENED`]: ../app/src/main/java/gr/dimitris/app/modules/wordcoach/CueLadder.kt

## What phase 13 changed: two new writing boards, and a breath

No new tile and no new control — «Γράψε» already had five dots and its three bottom actions, and
«Τραγούδα και πες το» keeps exactly the screen it had. What changed is what two of those dots ask for,
and both changes had to fit inside the three places the bottom block has.

**Level 4, «Υπαγόρευση»: «Άκου» and «Καθάρισε» share one slot.** The word is only ever a sound here, so
hearing it again is not a hint and is never withheld — but wiping the paper is also something he needs,
and a fourth button is not available. They take turns, the way «Το είδα» and «Έτοιμο» do on the recall
board and for the same reason: the slot holds whichever is the real next step. With a clean sheet there is
nothing to wipe and nothing to hand in, so it is «Άκου»; the moment there is ink on the paper it is
«Καθάρισε», and «Έτοιμο» beside it wakes up. The cost is the converse and it is real: **with ink on the
paper he cannot ask for the word again without clearing first.** Hearing it matters most on an empty
sheet, which is why that is the way round it went; if he turns out to want the word again with ink
already on the paper, this is the trade to revisit.

**Level 5, the typed sentence: «Παράλειψη» is off while «Διαβάζω...» is on the screen.** It is the only
button in the app that is deliberately dead for as long as eight seconds, and the reason is that the
verdict he is waiting for belongs to *this* board: a skip taken mid-judgement used to let board 1's
answer land on board 2, marking a sentence he never wrote. «SQL» does the same for the two seconds a
query of his is inside SQLite. The back arrow still works throughout and still writes nothing, and the
wait is the judge's own timeout at the very most — but it is the first thing to look at if a phone with
no signal ever leaves him stuck on a board.

**The breath is spacing and silence, not a control.** A sentence longer than six syllables is now sung
in breath groups, and both halves of that are things he hears and sees rather than things he presses:
the first syllable of each group carries 16 dp of extra space before it, and the rest before it lasts
three times the ordinary gap between syllables. A "breathe here" affordance would have been a fourth
thing on a screen that already has the tap pad, «Άκου» and «Παράλειψη» — and it would have asked him to
do on purpose the one thing the melody is there to do for him.
