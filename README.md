# Dimitris' App («Δημήτρης»)

A personal Android app for Dimitris: daily speech, number, writing and step-by-step practice after a stroke, a talk board for everyday communication, and a caregiver mode for the people around him. Greek-only, one-handed, works fully offline. Not a product.

## Read first

- [Design specification](docs/superpowers/specs/2026-09-05-dimitris-app-design.md) — who the app is for, the design rules, every module, the data model and the sync contract.
- [Handover notes](docs/HANDOVER.md) — what is built, what only a human can verify, the decisions made on Chris's behalf, known limitations.
- [Sync server](server/README.md) — how to run the family's own sync server (English, with a Greek summary: «Περίληψη στα ελληνικά»).
- [Adaptation data](docs/ADAPTATION.md) — what every exercise records that nothing adapts from yet, and the first rule to try with it.
- [Screens and what is under his thumb](docs/UX.md) — every screen on both sides of the app, its bottom-area actions and its one primary, and the audit rule they are held to.

## Implementation plans (one per phase, each ending with its execution record)

| Phase | Plan |
|---|---|
| 0 | [Plumbing: database, audio, caregiver mode, backup](docs/superpowers/plans/2026-09-05-phase0-plumbing.md) |
| 1 | [Talk board](docs/superpowers/plans/2026-09-05-phase1-talkboard.md) |
| 2 | [Word coach and the daily session](docs/superpowers/plans/2026-09-05-phase2-wordcoach.md) |
| 3 | [Number sense](docs/superpowers/plans/2026-09-05-phase3-numbers.md) |
| 4 | [Sing-then-say (melodic intonation)](docs/superpowers/plans/2026-09-05-phase4-singsay.md) |
| 5 | [Dialogues (script practice)](docs/superpowers/plans/2026-09-05-phase5-scripts.md) |
| 6 | [Sentence builder](docs/superpowers/plans/2026-09-05-phase6-sentences.md) |
| 7 | [Trace and write](docs/superpowers/plans/2026-09-05-phase7-trace.md) |
| 8 | [Right-hand arcade](docs/superpowers/plans/2026-09-05-phase8-arcade.md) |
| 9 | [Progress, insights and the Claude advisor](docs/superpowers/plans/2026-09-05-phase9-progress.md) |
| 10 | [Sync between the family's phones](docs/superpowers/plans/2026-09-05-phase10-sync.md) |
| 11 | [Polish after the first field test](docs/superpowers/plans/2026-09-06-phase11-polish.md) |
| 12 | [Harder, and about sentences](docs/superpowers/plans/2026-09-10-phase12-sentences-and-difficulty.md) |
| 13 | [Two new tiles, and harder content](docs/superpowers/plans/2026-09-10-phase13-new-tiles-and-content.md) |

Feedback on the medical reasoning is as welcome as feedback on the code: the therapy assumptions are spelled out in the specification and in each plan's execution record, and they are guesses by a programmer until a clinician says otherwise.

## Build and install

Requirements: JDK 21, Android SDK with platform 37, a phone with Android 8 or newer.

```bash
./gradlew installDebug                      # the only connected device
ANDROID_SERIAL=<serial> ./gradlew installDebug
./gradlew testDebugUnitTest                 # JVM tests
./gradlew connectedDebugAndroidTest         # emulator tests (run `adb shell pm clear gr.dimitris.app` first)
```

## Releases

Pushing a tag such as `v0.2` builds a signed APK on GitHub Actions and attaches it to a [GitHub Release](https://github.com/ChristosG/tomas/releases):

```bash
git tag v0.2
git push origin v0.2
```

Download `dimitris-app-<version>.apk` from the Releases page on the phone, open it, allow installing from this source once, and it installs over the previous release. The signing keystore is kept outside the repository (`~/.dimitris-app/`) and in the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. A phone that has a debug build must uninstall it once before the first release build.

## Credits

Pictograms by [ARASAAC](https://arasaac.org) (Government of Aragón), licensed CC BY-NC-SA 4.0; used here for personal, non-commercial therapy only.
