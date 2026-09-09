# Dimitris' App — Design Spec

Date: 2026-09-05
Status: approved in conversation, awaiting written review
Owner: Chris (with Dimitris' father as co-maintainer)

## 1. Purpose

A personal Android app, built for one person, to give Dimitris high-dose daily practice for the abilities his stroke damaged, and a communication aid for the moments speech fails him.

Dimitris had a left-hemisphere stroke 2.5 years ago (carotid rupture). His profile, as observed by Chris:

- Right-side weakness (hemiparesis): can move arm and leg, cannot write, walks with difficulty.
- Expressive (Broca-type) aphasia: understands clear sentences, struggles to produce words, helped by cueing (hints from a partner).
- Likely apraxia of speech: what he says is hard to articulate.
- Acalculia: writes digits in the air to hold a number, weak sense of magnitude (100 vs 12).
- Preserved: memory, personality, humour, ability to learn, singing (melody and overlearned sequences).
- Uses a phone well; already communicates by pointing at the Wolt app.
- Greek-speaking. His English is mostly gone.
- Attends a rehab "school", gym and physio.

Guiding sentence, endorsed by Chris: **he is a full adult mind with broken output channels.** Nothing in the app is childish.

This is not a product. No Play Store, no accounts, no analytics SDKs, no ads, no English UI. Cost of AI APIs is not a constraint; dependence on third-party backends (Firebase, Supabase) is not wanted, because Chris and Dimitris' father host their own servers.

## 2. Non-negotiable design rules

1. **Greek everywhere.** Every string, both faces of the app.
2. **Left hand, one thumb.** Minimum touch target 72dp. Primary actions bottom-left or bottom-centre. No precise drags, no double taps, no pinch except in the right-hand arcade where it is the exercise.
3. **No time pressure.** No countdowns, no timers, nothing that fails him for being slow.
4. **Pictures and audio first.** Text is a hint layer, never the only channel.
5. **Success is signalled three ways**: icon, sound, haptic. Never colour alone.
6. **Adult tone.** Adult palette, adult copy, no cartoon mascots, no stars-and-confetti. He is a grown man relearning, not a child learning.
7. **High success rate.** Difficulty adapts so he lands around four correct in five. He should want to come back.
8. **Personal content over generic.** His people, his places, his coffee, his songs.
9. **Works fully offline, always.** Cloud services are optional add-ons switched on in caregiver settings.
10. **Mirror his speech therapist.** Caregiver mode makes it easy to load what the therapist is working on this month.

## 3. Shape of the app

One APK, two faces.

**Dimitris' face** (default on launch):

- **Σήμερα (Today)**: one large button that starts a mixed session of 10 to 15 minutes built from whatever is due across enabled modules. Below it, a grid of the modules for free practice. The talk board is always one tap away from any screen.
- A session ends with a plain summary: what he practised, and one encouraging line read aloud.

**Caregiver face**:

- Entered by a 2-second long-press on the logo on the Today screen, followed by a single "Λειτουργία φροντιστή;" confirm. No PIN by default.
- Optional lock, off by default, using Android `BiometricPrompt` with device-credential fallback. On Dimitris' phone this authenticates Dimitris himself, so it only prevents accidental entry. On caregiver phones (phase 10, sync) the lock is on by default.
- Contains: content entry (photo, word, category, recording), scripts editor, per-module settings, progress and insights, error log, export/import, cloud provider settings, Claude settings.

App name on the launcher: **Δημήτρης**. Package: `gr.dimitris.app`.

## 4. Stack and project layout

- Kotlin, Jetpack Compose (Material 3), single Gradle module `app`.
- minSdk 26, targetSdk and compileSdk 37. AGP 9.4 with built-in Kotlin (no `kotlin-android` plugin), Kotlin 2.4, KSP 2.3, Gradle 9.7. JDK 21 runs the build, bytecode targets Java 17. Verified to build on Chris's machine on 2026-09-05; the current androidx line (Compose 1.12, core 1.19) requires exactly this floor.
- Room for the database. Files (photos, recordings) in app-private storage.
- Manual dependency wiring through one `AppGraph` object. No Hilt, no Koin. Readability for Dimitris' father beats framework elegance.
- Coroutines and Flow for async and reactive data.

Packages inside `gr.dimitris.app`:

```
core/
  data/        Room entities, DAOs, repositories
  speech/      TextToSpeech and SpeechToText provider interfaces + Android impls
  audio/       recording, playback, tone synthesis (melody engine)
  scheduler/   Leitner spaced repetition, session builder
  greek/       syllable splitter, first-sound extraction, number-to-words
  log/         error/crash log
ui/
  theme/       tokens: sizes, type scale, palette, haptics, sounds
  components/  BigButton, PictureCard, CueLadderBar, SentenceStrip, ...
modules/
  talkboard/
  wordcoach/
  numbers/
  singsay/
  scripts/
  sentences/
  trace/
  arcade/
  Module.kt    the shared module interface
caregiver/
  content/     item entry, photo capture, recording
  scripts/
  progress/    dashboard, insights, export
  settings/
  insights/    rule engine + Claude client
today/
  TodayScreen, SessionRunner
```

## 5. Data model

Every table has `id: UUID`, `createdAt`, `updatedAt`, `deleted: Boolean`. Attempts and error log rows are append-only; a session row is written when it starts and finalized once when it ends. This is the whole contract needed by the later sync: push rows changed since X, pull rows changed since Y, last-write-wins on content tables and sessions, union on the append-only log tables.

- **Item** — the unit of everything.
  `text` (Greek), `kind` (WORD, PHRASE, NUMBER, SCRIPT_LINE), `category` (e.g. FOOD, PLACES, PEOPLE, VERBS, FEELINGS, BODY, NUMBERS, CUSTOM), `tags`, `imagePath?`, `modelRecordingId?`, `firstSound` and `firstSyllable` (auto-derived, overridable), `source` (SEED, CAREGIVER), `enabledModules` (bitmask/set).
- **Recording** — `itemId`, `path`, `recordedAt`, `who` (DIMITRIS, CAREGIVER), `durationMs`.
- **Attempt** — `itemId`, `module`, `sessionId?`, `startedAt`, `durationMs`, `outcome` (CORRECT, ASSISTED, SKIPPED), `cueLevel` (0–4, or null where the ladder does not apply), `selfRecordingId?`, `detail` (JSON, module-specific).
- **Schedule** — `(itemId, module)` primary key, `box` (1–5), `nextDueAt`, `lastSeenAt`, `streak`.
- **Session** — `startedAt`, `endedAt?`, `plannedModules`, `plannedItemCount`, `completedItemCount`.
- **Script** — `title`, `lines`: ordered list of `{ speaker: DIMITRIS | OTHER, itemId }`.
- **ErrorLog** — `at`, `where`, `message`, `stack`.
- **Settings** (DataStore, not Room): speech rate, enabled modules, per-module difficulty overrides, hand preference for trace and arcade, cloud provider choice and keys (in `EncryptedSharedPreferences`), Claude model and key, caregiver lock on/off, sync endpoint and token (phase 10).

Export/import: a zip of the Room database plus the media folder, produced through the share sheet. This is the phase-0 way to move data and to back it up.

## 6. Speech, sound and content

### Providers

Two interfaces, so cloud providers can be swapped in later without touching modules:

```kotlin
interface TextToSpeech {
    suspend fun speak(text: String, rate: Float = 1f): Result<Unit>
    suspend fun isGreekAvailable(): Boolean
}
interface SpeechToText {
    suspend fun listen(maxSeconds: Int): Result<Transcript>  // text + confidence
    val isAvailable: Boolean
}
```

Phase 0 implementations: Android `TextToSpeech` (locale `el-GR`) and Android `SpeechRecognizer` (`el-GR`). First run checks the Greek voice; if missing, a one-tap route to the system voice installer.

Later implementations (post phase 9, cost is not a constraint): neural Greek TTS (Google, Azure or ElevenLabs class; ElevenLabs allows cloning a caregiver's voice), and a Whisper-class STT that is more tolerant of aphasic speech. Selected in caregiver settings. Recordings leave the device only when a cloud provider is enabled.

### Model voice

For every item: play the caregiver recording if one exists, else TTS. Speech rate default 0.8, adjustable.

### Cue ladder

Shared component used by word coach, script practice and sentence builder.

| Level | What he gets |
|---|---|
| 0 | Picture only |
| 1 | First sound (e.g. "κ") |
| 2 | First syllable ("κα") |
| 3 | Whole word spoken |
| 4 | Whole word spoken and shown written, large |

One tap per level. At any level he can tap "Το είπα" (I said it) or record himself and hear model and self back to back. The outcome is CORRECT if he confirms at level 0–2, ASSISTED at 3–4, SKIPPED if he moves on. The level reached is the score, and it drives the scheduler.

### Greek syllable splitter (`core/greek`)

Rule-based: vowels α ε η ι ο υ ω, digraphs αι ει οι ου υι, diphthongs αυ ευ ηυ, consonant clusters that can begin a Greek word stay together (e.g. στ, σπ, τρ, θρ), otherwise split between consonants. Stress marks preserved. Every item has an override field in caregiver mode for the words the rules get wrong. **Chris writes this function.**

### Seed content

ARASAAC pictograms (CC BY-NC-SA, acceptable for a personal non-commercial app), which ship Greek labels. A curated set of a few hundred across FOOD, PLACES, PEOPLE, VERBS, FEELINGS, BODY, NUMBERS, bundled as assets with a JSON manifest. Personal photos and words from caregivers layer on top and are prioritised by the session builder.

### Melody engine (`core/audio`)

Two synthesized pitches (a comfortable interval, e.g. G3 and B3) via `AudioTrack`. A phrase's syllables are each assigned HIGH or LOW; stressed syllables default to HIGH. A tap pad for the left hand keeps the beat (one tap per syllable). Best model is a caregiver singing the phrase into the app; the synthesized version is the fallback.

## 7. Modules

Each module implements:

```kotlin
interface Module {
    val id: ModuleId
    val titleGreek: String
    fun itemsFor(session: SessionPlan): List<Item>
    @Composable fun Screen(items: List<Item>, onAttempt: (Attempt) -> Unit, onDone: () -> Unit)
}
```

Build order below is the phase order. Each phase ends with Dimitris using it on his phone while Chris watches.

### Phase 0 — Plumbing
Project, database, TTS wrapper, recording and playback, design system, caregiver content entry (camera or gallery photo, Greek word, category, optional recording), Today screen with an empty session runner, export/import, error log.

### Phase 1 — Talk board (AAC)
- Category grid → item grid. Tap an item: the phone speaks it.
- A sentence strip at the top chains items: "Θέλω" + "καφέ" → speaks the sentence.
- Quick phrases row (Ναι, Όχι, Περίμενε, Βοήθεια, Πονάω, Τουαλέτα).
- Favourites auto-sorted by usage. Caregivers can pin.
- Every tap logs an Attempt with outcome CORRECT and no cue level; usage feeds insights.

### Phase 2 — Word coach
- Session: due items from the scheduler, filled with up to 8 new items per day, ordered easy-hard-easy.
- Per item: picture → cue ladder → optional self-recording → next.
- Scheduler: Leitner boxes 1–5 with intervals 1, 2, 4, 8, 16 days. CORRECT at cue 0–1 moves up a box, CORRECT at 2 stays, ASSISTED stays, SKIPPED moves down. **Chris writes the move policy.**

### Phase 3 — Number sense
Levels, each unlocked by accuracy on the previous:
1. Which is more: two piles of dots with the digit under each, 0–10.
2. Number line 0–10: a digit appears, he taps where it goes.
3. Counting: tap each object, hear the count.
4. Which is more, 0–100, dots fade to digits only.
5. Match digit ↔ Greek word ↔ quantity (`core/greek` number-to-words).
6. Which is more and number line, 0–1000.
7. Euro: which coin or note, which price is bigger, real prices from his Wolt favourites.

**Chris writes the difficulty progression** (when to unlock, when to step back).

### Phase 4 — Sing then say (Melodic Intonation Therapy)
Phrases he needs daily (θέλω καφέ, πάμε σπίτι, καλημέρα, πώς είσαι, ...). Stages per phrase:
1. Listen: model sung, syllables light up, tap pad pulses.
2. Sing together: backing plays, he taps and sings.
3. Sing with the backing fading over repetitions.
4. Speak with taps only.
5. Speak.
Progress is the stage reached, stored in `Attempt.detail`. He can record and compare at any stage.

### Phase 5 — Script practice
Scripts written in caregiver mode: coffee order, taxi, phone call to parents, doctor's reception, small talk. The OTHER lines are caregiver recordings; his lines use the cue ladder. Runs as a dialogue: the app speaks, waits for him, he taps through.

### Phase 6 — Sentence builder
Two to four big tiles (picture + word). He taps them in order into the sentence strip; the app speaks the result. Correct order plays the full sentence with a success signal. Higher levels add one distractor tile. Sentences generated from a small set of templates (who–does–what, who–wants–what) over his items.

### Phase 7 — Trace and write
Huge letter or word on screen with a stroke template. He traces with a finger; score is mean distance from the template. Progression: Greek capitals, lowercase, his name, his items' words, then write from memory after tracing (copy-and-recall). Hand preference is a setting; the other hand is allowed.

### Phase 8 — Right-hand arcade
Only after a word with his physio. Tap targets, trace paths, hold-and-drag, pinch a photo to zoom. Target size shrinks as accuracy rises, grows back on misses. Hand is explicit: "Δεξί χέρι".

### Phase 9 — Progress, insights, Claude
- Dashboard: minutes per day, streak, accuracy per module per week, mean cue level per week (down is good), items mastered (box 5), talk board usage.
- Rule engine produces Greek insight lines, e.g. "Οι λέξεις που αρχίζουν από π βελτιώθηκαν αυτή την εβδομάδα", "Τα νούμερα 0–100 είναι έτοιμα για το επόμενο επίπεδο".
- Claude (optional, caregiver-side): "Ρώτα τον Claude" builds a compact text summary of the last N weeks (items, outcomes, cue levels, module stats; never audio or photos), calls the Anthropic Messages API with a fixed system prompt describing Dimitris' profile, and returns two things: advice for caregivers, and a two-sentence encouragement for Dimitris that the app reads aloud. Model and key set in caregiver settings; nothing is sent without a key. Follow the `claude-api` skill when implementing.

### Phase 10 — Sync
A small self-hosted API on Chris's or the father's server. Protocol: `POST /push` with rows changed since the last cursor, `GET /pull?since=` for the reverse, media uploaded by content hash. Auth by a long shared token over TLS. Caregiver phones install the same APK, choose "caregiver" at first run, and get the remote view and content entry. Gets its own spec when we reach it.

## 8. Error handling and resilience

- Offline is the normal state. No module ever needs the network.
- Greek TTS missing → clear one-tap install screen, not a crash.
- Microphone denied → recording features hide; everything else works.
- Camera denied → gallery picker still works.
- Uncaught exceptions → written to ErrorLog with stack, app restarts to Today. Caregiver mode shows the log as a plain list, included in export.
- Media files missing (deleted, moved) → item still works with TTS and a placeholder picture.
- Cloud providers failing → silent fallback to the Android provider for that utterance, error logged.

## 9. Testing

- Unit tests: syllable splitter, first-sound extraction, number-to-words, scheduler move policy and due selection, session builder ordering, number-sense generators and progression, tracing scorer, sentence templates, insight rules, sync cursor logic (phase 10).
- Compose UI tests: cue ladder flow, talk board tap-to-speak and sentence strip, Today session runner.
- Manual: API 36 emulator on Chris's machine, then Dimitris' phone.
- The real test is Dimitris, at the end of every phase.

## 10. Chris's contributions

Where a design choice really matters, Chris writes the code, with signature, tests and a marked spot prepared by Claude:

1. `core/greek/Syllabifier.kt` — the Greek syllable splitter.
2. `core/scheduler/LeitnerPolicy.kt` — move-up and move-down rules.
3. `modules/numbers/Progression.kt` — when levels unlock and step back.
4. Later, the insight rules in `caregiver/insights/Rules.kt`.

## 11. Out of scope

- Any language other than Greek.
- Accounts, login, Play Store, analytics, ads.
- Anything that scores him against time.
- Medical claims. The app is practice and a communication aid, complementary to his therapists, never a replacement.

## 12. Amendments after the first field test (2026-09-06)

Chris tested the first release on his phone. These amendments follow from his notes and from one principle he set: every choice is made for what helps Dimitris learn, nothing else.

**Errorless learning is the rule for speech.** Hearing the model is never withheld: every screen that has a model (word coach, dialogues, sing-then-say, sentences) shows an always-enabled «Άκου». Listening raises the recorded cue level (never below 3) so the data stay honest, and nothing he sees ever frames an assisted answer as a failure.

**Writing is different.** A wrong letter form must not be rewarded, or the wrong movement gets learned. The trace scorer judges shape, not just proximity: the template is split into segments that must each be touched (coverage), and most of his ink must lie on the letter (precision), with tolerances in absolute finger units rather than fractions of the letter. A caregiver setting «Αυστηρότητα γραψίματος» (Χαλαρό / Κανονικό / Αυστηρό) scales those tolerances. After a miss the letter is shown and he copies it; that remains errorless.

**Speech recognition waits for him.** Aphasia delays initiation, so the recogniser waits several seconds before giving up, shows that it is listening, and has a «Στοπ». Silence in a recording is caught («Δεν σε άκουσα»). When recognition is on, every screen where he records compares what it heard with the target and offers one gentle «Δοκίμασε ξανά» before he may still confirm; a match confirms for him.

**The melody is tunable.** Caregiver settings for tempo (Κανονικό / Αργό) and key (Κανονικό / Χαμηλό, for a low male voice).

**Caregivers can verify what they add.** «Δοκίμασέ το» in the word editor runs that word at once; new caregiver words go first in the next session.

**The Claude advisor gets a memory and a journey.** Instead of a one-page summary it receives: Dimitris' profile, the caregivers' notes (what the therapist said, current goals), the lifetime table of every word practised (attempts, outcomes, mean help, first/last seen, box), the last 28 days in per-word-per-day detail, daily minutes, the previous advices it gave, and the app's own insight lines. It answers in three sections: for the caregivers, for Dimitris (read aloud), and «Εστίαση», a small JSON block naming the words, sounds, modules and level suggestions to boost. Advices and notes are stored (and synced) so the next consultation builds on the last; the app schedules the focus words first for the following week. Still text-only, still only when a caregiver taps the button, and the caregiver sees exactly what is sent.

## 13. Re-scope after Dimitris' own feedback (2026-09-10)

Dimitris tried v0.2.0 and said it is too easy. The picture is stronger than §1 assumed: he says most everyday words (not always cleanly), reads Greek slowly but understands abstract words, reasons well, uses a chat assistant with photos and a few typed words, and did SQL as a programmer. His speech is telegraphic («φάρμακα πρέπει πάρω»). What he lacks is full sentences and multi-step tasks; that is the new centre of the app.

**Principles that do not change:** Greek-only, one-handed, adult tone, errorless speech, shape-judged writing, no timers. **One added:** the interface stays simple whatever the difficulty — one primary action per screen, at most three actions in the bottom area, every new activity is a tile on the grid, and difficulty is one row of five dots he can tap himself, bounded by the caregiver.

**Sentence expansion is the core therapy.** Wherever he speaks or taps content words, the app can return the full sentence, speak it, and let him repeat it: on the talk board (tap words → «Ολόκληρη» → the grammatical sentence), in dialogues (his open answer → accepted, or nudged towards the full form), and in the sentence builder (typed or spoken sentences judged for grammar).

**Open dialogues.** His answers are judged for relevance and form, not matched to a script line. Any sensible reply counts; a telegraphic one earns the expanded form to repeat; questions get harder (two-step answers).

**Judgement and expansion through one Claude API.** Per-turn judging uses a fast model (`claude-haiku-4-5-20251001`), advice keeps `claude-opus-5`. Turn judging sends only text (the prompt, the target, his transcript) and only when the caregiver has turned «Έλεγχος με Claude» on and a key exists; without it the app falls back to local matching.

**Transcription stays free and on the phone.** Android's on-device recogniser (Android 13 and newer) with the Greek pack downloaded from a caregiver setting; the network recogniser only as a fallback, with honest Greek lines for "no connection" and "Greek pack missing". One speech control: «Μίλα» records his take and transcribes the same audio; «Άκου» afterwards plays the model and his take back to back.

**Difficulty 1–5 everywhere,** set by him on each module's first screen and bounded by the caregiver. Numbers grow to 1000 and beyond with operations, change, time, dates and two-step word problems; sentences to seven words with articles, prepositions and clauses, plus gap-filling and typed sentences; vocabulary gains abstract and multi-syllable tiers; writing gains dictation and typed sentences; sing-then-say moves to long sentences and stays off unless enabled.

**Two new tiles** (phase 13): «SQL», beginner puzzles run for real on tiny tables, both about his life and textbook ones; and «Βήματα», ordering and telling the steps of a task, for the multi-step reasoning he calls «είμαι καμένος».

**Privacy.** Nothing about his health beyond what §1 states enters prompts, reports or documents. Per-turn judging is opt-in and text-only.
