# Dimitris' App («Δημήτρης»)

A personal Android app for Dimitris: daily speech, number and writing practice after a stroke, a talk board for everyday communication, and a caregiver mode for the people around him. Greek-only, one-handed, works fully offline. Not a product.

- Design: `docs/superpowers/specs/2026-09-05-dimitris-app-design.md`
- Handover and known limitations: `docs/HANDOVER.md`
- Sync server for the family's own web servers: `server/README.md` (with a Greek summary)

## Build and install

Requirements: JDK 21, Android SDK with platform 37, a phone with Android 8 or newer.

```bash
./gradlew installDebug                      # the only connected device
ANDROID_SERIAL=<serial> ./gradlew installDebug
./gradlew testDebugUnitTest                 # JVM tests
./gradlew connectedDebugAndroidTest         # emulator tests (run `adb shell pm clear gr.dimitris.app` first)
```

## Releases

Pushing a tag such as `v0.2` builds a signed APK on GitHub Actions and attaches it to a GitHub Release:

```bash
git tag v0.2
git push origin v0.2
```

Download `dimitris-app-<version>.apk` from the Releases page on the phone, open it, allow installing from this source once, and it installs over the previous release. The signing keystore is kept outside the repository (`~/.dimitris-app/`) and in the repository secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. A phone that has a debug build must uninstall it once before the first release build.

## Credits

Pictograms by [ARASAAC](https://arasaac.org) (Government of Aragón), licensed CC BY-NC-SA 4.0; used here for personal, non-commercial therapy only.
