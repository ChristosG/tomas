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
| Γράψε (`TraceScreen`) | passed | «Επόμενο» | «Επόμενο» |
| | level 5, letter still shown | «Καθάρισε», «Το είδα», «Παράλειψη» | «Το είδα» |
| | otherwise | «Καθάρισε», «Έτοιμο», «Παράλειψη» | «Έτοιμο» |
| | finished | «Εντάξει» | «Εντάξει» |
| Δεξί χέρι (`ArcadeScreen`) | playing | «Παράλειψη» | — |
| | finished | «Εντάξει» | «Εντάξει» |
| Μίλα (`TalkBoardScreen`) | always | «Πες το», σβήσε, καθάρισε | «Πες το» |

Four of his screens carry a control in the *content* rather than in the bottom area:

- **Λέξεις**: «Βοήθεια», beside the picture it is a hint about; and the second «Μίλα» offered after
  the phone has asked him twice.
- **Διάλογοι**: «Βοήθεια», inside the turn card; and the second «Μίλα».
- **Τραγούδα και πες το**: «Το έκανα», under the syllables; «Ηχογράφηση» (only on the fallback
  recogniser path, where the window does not keep his own take); and the second «Μίλα».
- **Προτάσεις**: «Το έγραψα», under the sentence he typed.

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

[`Settings.DEFAULT_OFF`]: ../app/src/main/java/gr/dimitris/app/core/settings/Settings.kt
