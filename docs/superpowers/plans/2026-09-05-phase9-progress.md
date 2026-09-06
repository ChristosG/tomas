# Dimitris' App — Phase 9 (Progress, Insights, Claude) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Caregivers see how Dimitris is doing (minutes, streak, accuracy per module, cue-level trend, mastered words, talk board usage, current levels), read plain-Greek insights the app derives itself, adjust levels by hand, and optionally ask Claude for advice plus a two-sentence encouragement that the phone reads to Dimitris.

**Architecture:** Pure `ProgressStats` (from attempts/sessions/schedules) and `InsightRules` (Chris-marked) with tests; a `ProgressScreen` with small Canvas bar charts and level controls; `SecretStore` (EncryptedSharedPreferences) for the API key; `AdviceSummary` (pure, text-only, never audio or photos); `ClaudeAdvisor` on the official Anthropic Java SDK from Kotlin; an `AdviceScreen`. Nothing leaves the device unless a key is set and the caregiver taps the button.

**Tech Stack:** as before + `com.anthropic:anthropic-java:2.34.0` (official SDK; Kotlin uses the Java SDK), `androidx.security:security-crypto` (already declared), `INTERNET` permission.

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§7 phase 9)

## Global Constraints

- Greek-only UI; 72dp; bottom actions.
- Claude: model `claude-opus-5` (configurable string in settings, default that), adaptive thinking (`ThinkingConfigAdaptive`), `maxTokens = 4000`, a fixed Greek system prompt describing Dimitris' profile and the two required sections; the user message is the `AdviceSummary` text. Response parsed into `caregivers` and `dimitris` parts by the markers `## Για τους φροντιστές` and `## Για τον Δημήτρη`. `stop_reason == refusal` or any exception → Greek error text, recorded via `graph.errors`. No key → the button is disabled with "Βάλε κλειδί στις ρυθμίσεις".
- The summary contains: date range, minutes, streak, per-module attempt counts and accuracy, mean cue level per week, mastered count, ten most-skipped items (text only), ten most-used talk board items (text only), current levels, the rule-engine insights. Never audio, photos, or file paths.
- `InsightRules` implemented by Claude with `// CHRIS: rewrite me` and full tests (controller ruling).
- Commits `feat(phase9): ...` with the Co-Authored-By trailer; conventions as previous phases.

---

### Task 1: ProgressStats and InsightRules (pure)

**Files:** create `caregiver/progress/ProgressStats.kt`, `caregiver/insights/InsightRules.kt`; tests `ProgressStatsTest.kt`, `InsightRulesTest.kt`.

**Interfaces:**
- `data class DayStat(val day: Long /* startOfDay */, val minutes: Int, val attempts: Int)`; `data class ModuleStat(val module: ModuleId, val attempts: Int, val correct: Int, val assisted: Int, val skipped: Int) { val accuracy: Float }`; `data class WeekCue(val weekStart: Long, val meanCue: Float)`; `data class Progress(val from: Long, val to: Long, val days: List<DayStat>, val streakDays: Int, val modules: List<ModuleStat>, val cueTrend: List<WeekCue>, val mastered: Int, val mostSkipped: List<Pair<String, Int>>, val mostUsedTalk: List<Pair<String, Int>>)`.
- `object ProgressStats { fun compute(attempts: List<Attempt>, sessions: List<Session>, schedules: List<Schedule>, items: Map<String, Item>, from: Long, to: Long, zone: ZoneId = ZoneId.systemDefault()): Progress }` — minutes per day from session durations (`endedAt - startedAt`, capped at 60 min each); streak = consecutive days ending today (or yesterday) with ≥ 1 attempt; cue trend over WORDCOACH/SCRIPTS/SINGSAY attempts with non-null cueLevel, grouped by ISO week; mastered = schedules with box 5; mostSkipped = item texts by SKIPPED count (real items only, synthetic ids excluded); mostUsedTalk = TALKBOARD attempts by item text.
- `object InsightRules { fun generate(p: Progress, attempts: List<Attempt>, items: Map<String, Item>): List<String> }` — Greek one-liners, at most 6, in priority order: streak ≥ 3 ("Σερί N ημερών. Συνέχισε έτσι!"); a module with ≥ 20 attempts this period and accuracy ≥ 0.8 ("Οι Λέξεις πάνε πολύ καλά: N% σωστά."); cue trend improved by ≥ 0.5 between first and last week ("Χρειάζεται λιγότερη βοήθεια από την προηγούμενη εβδομάδα."); a first-sound group (by `Item.firstSound`) with ≥ 6 attempts whose accuracy rose ≥ 0.2 versus the previous period ("Οι λέξεις που αρχίζουν από «π» βελτιώθηκαν."); ≥ 3 items skipped ≥ 3 times ("Δύσκολες λέξεις: καφές, ψωμί, νερό. Δοκίμασε φωτογραφία ή φωνή."); no session for ≥ 3 days ("Καμία άσκηση εδώ και N μέρες.").

- [ ] Failing tests with hand-built attempt lists (fixed epoch times), then implement, `./gradlew -q testDebugUnitTest`, commit `feat(phase9): progress statistics and insight rules`.

---

### Task 2: Progress screen with level controls

**Files:** create `caregiver/progress/ProgressViewModel.kt`, `ProgressScreen.kt`, `Charts.kt` (`BarChart(values: List<Float>, labels: List<String>)` on `Canvas`, navy bars on mist background, no library); modify `Nav.kt` (`caregiver/progress`), `CaregiverHomeScreen.kt` (entry "Πρόοδος" first in the list), `core/data/Daos.kt` (+ `AttemptDao.between(from, to)`, `SessionDao.between(from, to)`, `ScheduleDao.allActive()`).

- [ ] ViewModel loads the last 28 days (`startOfDay(now) - 27 days .. now`), computes `Progress` and insights on `Dispatchers.Default`, exposes level values (`numbersLevel`, `sentencesLevel`, `traceLevel`) with setters.
- [ ] Screen sections: "Αυτή την εβδομάδα" (minutes bar chart per day, streak line), "Ανά άσκηση" (one row per module: name, attempts, accuracy %), "Πόση βοήθεια" (cue trend bar chart, lower is better, caption "Λιγότερο = καλύτερα"), "Μαθημένες λέξεις: N", "Δύσκολες λέξεις" list, "Στον πίνακα λέει πιο συχνά" list, "Τι βλέπω" insight lines, "Επίπεδα" with three stepper rows (− value +, 72dp buttons) for numbers (1–7), sentences (1–4), trace (1–5), and at the bottom `BigButton("Ρώτα τον Claude")` (enabled only with a key; Task 3 wires the action) plus `QuietButton("Εξαγωγή αναφοράς")` that shares the `AdviceSummary` text as plain text (share sheet).
- [ ] Build, install, look; commit `feat(phase9): progress dashboard with level controls`.

---

### Task 3: Claude advisor

**Files:** modify `gradle/libs.versions.toml` + `app/build.gradle.kts` (`anthropic = "2.34.0"`, `implementation("com.anthropic:anthropic-java:2.34.0")`; add `packaging { resources.excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties", "META-INF/DEPENDENCIES") }` only if the build complains about duplicate META-INF entries), `AndroidManifest.xml` (`<uses-permission android:name="android.permission.INTERNET" />`); create `core/secrets/SecretStore.kt`, `caregiver/insights/AdviceSummary.kt`, `ClaudeAdvisor.kt`, `AdviceScreen.kt`; modify `caregiver/SettingsScreen.kt` (section "Claude": masked key field with "Αποθήκευση κλειδιού" and "Διαγραφή", model field default `claude-opus-5`), `Nav.kt` (`caregiver/advice`), `ProgressScreen.kt` (button navigates), `core/settings/Settings.kt` (+ `claudeModel`).
Tests: `AdviceSummaryTest` (contains counts, never contains "/photos/" or ".m4a"; Greek headings), `ClaudeAdvisorParseTest` (splits the two sections; missing markers → whole text as caregivers, Dimitris part empty).

- [ ] `SecretStore(context)`: `EncryptedSharedPreferences` (`MasterKey` AES256_GCM) with `getClaudeKey(): String?`, `setClaudeKey(key: String?)`.
- [ ] `AdviceSummary.build(p: Progress, insights: List<String>, levels: Map<String, Int>): String` — Greek headed sections, plain text, ≤ 6 000 characters.
- [ ] `ClaudeAdvisor(secrets, model: () -> String)`:
```kotlin
package gr.dimitris.app.caregiver.insights

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.ThinkingConfigAdaptive
import gr.dimitris.app.core.secrets.SecretStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Advice(val caregivers: String, val dimitris: String)

class ClaudeAdvisor(private val secrets: SecretStore, private val model: suspend () -> String) {
    val hasKey: Boolean get() = !secrets.getClaudeKey().isNullOrBlank()

    suspend fun ask(summary: String): Result<Advice> = withContext(Dispatchers.IO) {
        val key = secrets.getClaudeKey()?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("Δεν υπάρχει κλειδί"))
        runCatching {
            val client = AnthropicOkHttpClient.builder().apiKey(key).build()
            val params = MessageCreateParams.builder()
                .model(model())
                .maxTokens(4000L)
                .thinking(ThinkingConfigAdaptive.builder().build())
                .system(SYSTEM_PROMPT)
                .addUserMessage(summary)
                .build()
            val response = client.messages().create(params)
            if (response.stopReason().toString().equals("refusal", ignoreCase = true)) error("Ο Claude δεν απάντησε σε αυτό το αίτημα.")
            val text = response.content().stream().flatMap { it.text().stream() }.map { it.text() }.toList().joinToString("\n")
            parse(text)
        }.recoverCatching { e ->
            if (e is AnthropicServiceException) throw IllegalStateException("Σφάλμα από τον Claude: ${e.statusCode()}", e) else throw e
        }
    }

    companion object {
        const val CAREGIVERS = "## Για τους φροντιστές"
        const val DIMITRIS = "## Για τον Δημήτρη"
        val SYSTEM_PROMPT = """
            Είσαι σύμβουλος για την αποκατάσταση του Δημήτρη, ενός ενήλικα με αφασία Broca και δεξιά ημιπάρεση μετά από εγκεφαλικό
            στο αριστερό ημισφαίριο πριν από 2,5 χρόνια. Καταλαβαίνει καλά, δυσκολεύεται να εκφραστεί, έχει ακαλκουλία, θυμάται
            και τραγουδά. Θα λάβεις μια περίληψη της εξάσκησής του από την εφαρμογή του. Απάντησε στα ελληνικά, με απλά λόγια,
            χωρίς ιατρικές διαγνώσεις, ακριβώς με αυτές τις δύο ενότητες και τίτλους:
            $CAREGIVERS
            (3 έως 6 σύντομες, συγκεκριμένες προτάσεις για το τι να κάνουν οι φροντιστές την επόμενη εβδομάδα)
            $DIMITRIS
            (ακριβώς 2 σύντομες, ζεστές προτάσεις προς τον Δημήτρη, σε δεύτερο πρόσωπο, χωρίς αριθμούς)
        """.trimIndent()

        fun parse(text: String): Advice {
            val c = text.indexOf(CAREGIVERS); val d = text.indexOf(DIMITRIS)
            if (c == -1 || d == -1 || d < c) return Advice(text.trim(), "")
            return Advice(text.substring(c + CAREGIVERS.length, d).trim(), text.substring(d + DIMITRIS.length).trim())
        }
    }
}
```
  (If the Java SDK cannot be used on Android — e.g. an unresolvable dependency conflict — the implementer reports BLOCKED with the exact error; the controller decides on a raw-HTTP fallback. Do not silently switch.)
- [ ] `AdviceScreen(onBack)`: shows a "Ρωτάω τον Claude..." state, then the two sections; `BigButton("Άκου για τον Δημήτρη")` speaks the Dimitris part with TTS; errors in Greek; the summary sent is viewable under "Τι στάλθηκε".
- [ ] Settings section "Claude" with the masked key field, save/delete, model field; `AppGraph` gets `secrets` and `advisor`.
- [ ] Build; unit tests; install; with no key the button is disabled; with an invalid key the Greek error appears (test with `sk-ant-invalid`). Commit `feat(phase9): Claude advisor with encrypted key`.

---

### Task 4: Phase 9 verification

- [ ] Full suites green; install on the phone; Chris enters his key later — verify the no-key and bad-key paths; Σφάλματα shows the bad-key error once. Append notes; commit `docs(phase9): verification notes`.

---

## Verification notes

Written at the end of the phase-9 fix wave, on `worktree-phase0`. Device: `emulator-5554` only —
the attached phone was never addressed. Commits: `5a52db2`, `5401236` (dashboard), `7b469d1`,
`9f85d88` (advisor), then the fix wave `cd59252`, `1f6584d`, `d3e6f4d`.

### Suites

| Suite | Count | Notes |
|---|---|---|
| `./gradlew -q testDebugUnitTest` | **362**, green | 1 skipped — `ClaudeAdvisorLiveTest`, see below |
| `./gradlew connectedDebugAndroidTest` | **79**, green | run once at the end after `adb shell pm clear gr.dimitris.app` |

Phase 9 added 64 unit tests (298 → 362) and 12 instrumented ones (67 → 79), including the
`5 → 6` migration that indexes `sessions.startedAt`.

### **No live Claude call has ever been made.**

This is the one thing about phase 9 that is not verified end to end, and it should be read plainly:
**the happy path has never returned a 200 from the Anthropic API.** The dev machine this was built
on has no `ANTHROPIC_API_KEY` in its environment, so `ClaudeAdvisorLiveTest` — the one test that
would spend money — has skipped on every run. Nothing has ever seen a real answer split into its two
Greek sections by `ClaudeAdvisor.parse`.

What *is* verified, on the emulator, against the real `api.anthropic.com`:

- the request is well formed as far as authentication — a deliberately wrong key (`sk-ant-invalid…`)
  reaches Anthropic and comes back **401**, so the model id, the adaptive thinking config, the
  system prompt and the body were all accepted up to the point where the key was checked;
- the 401 becomes «Το κλειδί δεν έγινε δεκτό. Έλεγξε το κλειδί στις ρυθμίσεις.» on screen and one
  sanitised row in `Σφάλματα`, with no trace of `sk-ant`, `x-api-key` or `api.anthropic` anywhere in
  `dimitris.db` or its write-ahead log;
- the two halves of the *response reading* — which text blocks become the answer, which stop reason
  is a refusal, which is a truncation — are pinned by `ClaudeAdvisorMessageTest` against `Message`
  objects built with the SDK's own builders, so they do not depend on a network call at all;
- `ClaudeAdvisorParseTest` pins the split, the 400-character cap on the part read aloud to Dimitris,
  and every shape where the split cannot be trusted (his half stays empty).

**How Chris closes the gap, once, with his own key:**

```
export ANTHROPIC_API_KEY=sk-ant-...      # his own key, in the shell only
./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.caregiver.insights.ClaudeAdvisorLiveTest'
```

The test is guarded by `Assume.assumeTrue(System.getenv("ANTHROPIC_API_KEY") != null)`, so it skips
silently everywhere else and needs no flag. It sends a small made-up summary — nothing of Dimitris'
own goes over the wire in a test — and asserts the caregivers' section comes back non-empty. It
costs one short Opus request. Do not put the key in `gradle.properties`, `local.properties` or any
file in the repository: the app's own key lives in `EncryptedSharedPreferences` on the phone and
nowhere else, and the test's key should live in a shell and nowhere else.

The same gap closes from the other side the first time a caregiver taps «Ρώτα τον Claude» with a
real key on the phone. Until one of those two things happens, treat the advisor as *built and
unproven on its happy path*.

### Manual checks on the emulator (fresh install, `pm clear`, a mixed session played first)

Screenshots in `.superpowers/sdd/2026-09-05-phase9-progress/shots/`.

| # | Shot | What it shows |
|---|---|---|
| 16 | `16-dashboard-caption-and-board.png` | «Τελευταίες 4 εβδομάδες» under «Ανά άσκηση»; «Μίλα — 2 ασκήσεις — πίνακας» with no percentage next to «Λέξεις — 100% σωστά» |
| 17 | `17-stepper-three-fast-taps.png` | three fast taps on `+` move Αριθμοί from 1 to 4 (DataStore holds 4) |
| 18 | `18-key-field-password-keyboard.png` | the key field with a password keyboard — `inputType=0x81`, no autocorrect |
| 19 | `19-advice-bad-key-error.png` | the Greek key message after a real 401 |
| 20 | `20-advice-summary-preview.png` | «Τι θα σταλεί» — the talk board with no percentage, the levels with their ranges |
| 21 | `21-error-log-one-sanitised-row.png` | `Σφάλματα`: one row, `claude advice`, the Greek sentence and nothing else |
| 22 | `22-share-summary.png` | the share sheet carrying the same summary |

Earlier shots `01`–`15` are from the two build rounds and are kept for the record.

`Σφάλματα` holds exactly one row after the whole pass — the bad-key error. Nothing else was
recorded: leaving a screen mid-load no longer writes a cancellation row, and the "no key" state is
not a fault to log.

### Known gaps, carried forward

1. The live call above.
2. `InsightRules` still carries its `CHRIS: rewrite me.` markers, and those sentences now go into the
   summary the advisor reads as well as onto the screen.
3. The release build is unminified; the Anthropic SDK's HttpComponents and victools weight is dead
   code the moment R8 is turned on, and whoever turns it on will need keep rules for the SDK's
   reflective Jackson models.

## Execution record (controller rulings, 2026-09-06)

Copied from the SDD ledger at phase close. Task reviews + final review: 0 Critical / 13 Important / 28 Minor in total, all fixed or ruled across two fix waves; the last re-review left two Minors (a stale answer shown against a fresh summary; the model-id write racing back) fixed in phase 10. No live Claude call has been made: the dev machine has no ANTHROPIC_API_KEY — Chris runs ClaudeAdvisorLiveTest once with a key (see Verification notes).

## Pre-flight conflict scan (2026-09-05)

| Tasks | Shared surface | Produces vs consumes | Finding |
|---|---|---|---|
| 1 / phases 3–8 | synthetic attempt ids (numbers:level:N, sentences:level:N, trace:level:N, arcade:<game>) | ProgressStats excludes them from mostSkipped | Ruling: synthetic = itemId absent from the items map (no string parsing) — cost: none |
| 1 / phase 4 | SINGSAY cueLevel = 5 − stage (phase-4 ruling) | cue trend over WORDCOACH/SCRIPTS/SINGSAY | consistent (lower = better in all three) |
| 1 / phase 2 | Session.endedAt nullable; sessions finalized once | minutes per day from endedAt − startedAt capped at 60 min; unfinished sessions count 0 | consistent |
| 2 / phases 3, 6, 7 | Settings.numbersLevel (1–7), sentencesLevel (1–4), traceLevel (1–5) | steppers | consistent (all three exist by phase 9) |
| 2 / phase 0 | CaregiverHomeScreen entries; Nav routes | "Πρόοδος" first | consistent |
| 3 / toolchain | anthropic-java 2.34.0 on Android (OkHttp, Java 17 bytecode, possible META-INF duplicates; Kotlin uses the Java SDK) | build must pass; network on Dispatchers.IO | Ruling: if the SDK cannot be made to build on Android within the task, the implementer reports BLOCKED with the exact error rather than switching to raw HTTP — cost: a fix dispatch |
| 3 / phase 0 | SecretStore (EncryptedSharedPreferences) — the key must never reach error_logs, logs, the summary, or a backup zip | Backup exports app files: check that EncryptedSharedPreferences' file is not inside the exported set | Ruling: the key is excluded from backups; error messages from the SDK are sanitised before graph.errors — cost: none |
| 3 / phase 1 | the Dimitris part is read aloud through graph.voice.speak (Result checked) | consistent |
| 3 alone | model string configurable, default claude-opus-5; adaptive thinking; maxTokens 4000; refusal stop reason handled | consistent with the claude-api skill |
| all | Greek-only, 72dp, bottom actions | consistent |

Scan result: three rulings carried into dispatches.

## Task log
Tasks 1+2: dispatched as one batch — BASE c4d8441, model opus (in parallel with the phase-8 scoped re-review, read-only; residuals fold into this task's fix round)
Tasks 1+2: implementer DONE (5a52db2, 5401236; JVM 333, connected 69). Accepted: mastered = distinct items in box 5; per-module Greek subjects; 56-day load for the previous period. Review dispatched (opus); Task 3 (Claude advisor) dispatched in parallel — BASE = the phase-8 docs commit after 5401236, model opus; review residuals fold into the Task 3 fix round.
Tasks 1+2: review DONE (0 Critical / 4 Important / 14 Minor; both ✅). Ruling: all four Important (talk board excluded from the first-sound rule and from the accuracy line, a 4-week caption, optimistic stepper state) and the real minors (index on sessions.startedAt via an additive migration if cheap — else a bounded query; count query for mastered; clock seam in the ViewModel; midnight/DST/zone tests; ProgressScreenTest independent of pre-existing rows) fold into the Task 3 fix round — one implementer at a time.
Task 3: implementer DONE_WITH_CONCERNS (7b469d1 sdk, 9f85d88; JVM 346 + 1 skipped live test, connected 75; no live call — no ANTHROPIC_API_KEY on this machine). Rulings: maxTokens raised to 8000 (AI cost is no concern; thinking counts against it) — into the fix round; explicit refusal handling kept (no fallback flag); the 401/403 key message accepted; APK 33 MB and release minify off — noted for Chris, not changed. Review dispatched (opus).
Task 3: review DONE (0 Critical / 4 Important / 7 Minor). Rulings: the Dimitris section is capped at 400 characters at parse time (two short sentences); client timeout 120 s; maxTokens 8000 with max_tokens stop detection surfaced as a Greek 'answer cut short' line; keystore mismatch heals by clearing the prefs through SharedPreferences; password keyboard on the key field; network half unit-tested through builder-built Messages. Fix wave — BASE 9f85d88, resuming implementer ac87706e9e9bd1498 with both reviews (Tasks 1+2 and Task 3) and Task 4 verification notes.
Fix wave DONE (cd59252, 1f6584d, d3e6f4d, 7c39d65; JVM 362 + 1 skipped, connected 79; DB v6 adds the sessions.startedAt index). Accepted: bestModule excludes the talk board; Throwable-wide keystore recovery; no MockWebServer seam; no ProgressViewModelTest. Final whole-phase review dispatched (opus) over c4d8441..7c39d65. Phase 10 Task 1 (server/ only, Node) dispatched in parallel — it must NOT commit; the controller commits its files when no app implementer is running.
Final review DONE: 0 Critical / 5 Important / 7 Minor. Rulings — I1: mastered joins items (WORD/PHRASE only); I2: minutes per day = session durations plus sittings of attempts without a session (consecutive attempts with gaps under 5 min, each sitting at least 1 min, talk board included); I3: line-anchored marker regex + duplicated-marker test; I4: one request at a time (a second ask while one is in flight shows 'Περίμενε την απάντηση.'), the request runs on graph.scope so back never orphans a billed call, SDK maxRetries(1); I5: cue-trend insight compares the last two weeks. Fix wave 2 — BASE 7c39d65, resuming implementer ac87706e9e9bd1498; explicit git paths only (untracked server/ files belong to phase 10).
Fix wave 2 DONE (b8740c4, e2d97e6, 87ac0c4, 863a1b1; JVM 383 + 1 skipped, connected 79). Accepted: cancel = outlive (guard prevents a second request); sittings are an under-estimate; idle line silent beyond 56 days. Scoped re-review dispatched (opus).
Fix-wave-2 re-review: 2 Minor open — A: a previous answer shown against a fresh summary (clear the answer when the summary changes / show the sent summary), B: the model id write on focus loss can be cancelled by back (write through graph.scope). Ruling: both fold into the phase 10 fix round (one implementer at a time). Phase 9 closed — execution record commit deferred until the phase-10 implementer reports.
