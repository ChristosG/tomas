# Dimitris' App — Phase 2 (Word Coach, Scheduler, Session Runner) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Dimitris presses "Ξεκίνα" and gets a real daily session: due words chosen by spaced repetition, each shown as a picture with a five-level cue ladder, self-recording and comparison, optional soft speech recognition, and a spoken summary at the end.

**Architecture:** Pure-Kotlin core first (`LeitnerPolicy`, `Scheduler`, `SessionBuilder`, `CueLadder`, `SpeechMatch`), all unit-tested with fakes. Then the `WordCoachModule` (the first `Module` implementation), the session runner in `today/`, a free-practice route, `SpeechToText` behind an interface with the Android recognizer, and caregiver settings for module and recognition toggles. Word coach writes `Attempt(WORDCOACH, cueLevel 0–4)` and updates `Schedule` rows.

**Tech Stack:** as Phase 0/1. `android.speech.SpeechRecognizer` with `el-GR`.

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§3, §5 Schedule/Session, §6 cue ladder, §7 phase 2)

## Global Constraints

- Every user-visible string is Greek. 72dp touch minimum. Primary actions at the bottom. No timers or countdowns; nothing auto-advances on a clock.
- Cue ladder: level 0 picture only; 1 first sound; 2 first syllable (skipped when `Item.firstSyllable == null`); 3 whole word spoken; 4 spoken and shown written. Confirm at 0–2 → CORRECT, at 3–4 → ASSISTED, skip → SKIPPED.
- Leitner: boxes 1–5, intervals 1, 2, 4, 8, 16 days. CORRECT at cue 0–1 moves up, CORRECT at 2 stays, ASSISTED stays, SKIPPED moves down. Up to 8 new items per day, session ordered easy–hard–easy, at most 12 items per module per session.
- Speech recognition is off by default and never blocks or fails him; it only adds encouragement.
- Functions reserved for Chris (`LeitnerPolicy.nextBox`) are implemented with a `// CHRIS: rewrite me` header comment and full tests (controller ruling, 2026-09-05).
- Build from `/mnt/nvme2TB/tomas/.claude/worktrees/phase0` with `./gradlew`; instrumented tests with `ANDROID_SERIAL=emulator-5554`. Commits `feat(phase2): ...` ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## File structure

```
app/src/main/java/gr/dimitris/app/
  core/scheduler/LeitnerPolicy.kt, Scheduler.kt, SessionBuilder.kt      NEW
  core/speech/SpeechToText.kt, AndroidSpeechToText.kt, SpeechMatch.kt   NEW
  core/settings/Settings.kt                                            + sttEnabled, enabledModules
  modules/Module.kt                                                    + practiceFor default
  modules/wordcoach/CueLadder.kt, WordCoachModule.kt, WordCoachViewModel.kt, WordCoachScreen.kt   NEW
  today/SessionViewModel.kt (NEW), SessionScreen.kt (rewrite), PracticeScreen.kt (NEW), TodayScreen.kt (+ module grid)
  AppGraph.kt                                                          + stt, scheduler, modules = [WordCoachModule]
  Nav.kt                                                               + PRACTICE route
  caregiver/SettingsScreen.kt                                          + module + STT toggles
app/src/test/.../core/data/FakeScheduleDao.kt, FakeAttemptDao.kt
app/src/test/.../core/scheduler/LeitnerPolicyTest.kt, SchedulerTest.kt, SessionBuilderTest.kt
app/src/test/.../core/speech/SpeechMatchTest.kt
app/src/test/.../core/settings/SettingsTest.kt (+2)
app/src/test/.../modules/wordcoach/CueLadderTest.kt
app/src/androidTest/.../today/SessionFlowTest.kt
```

---

### Task 1: Leitner policy and Scheduler

**Files:**
- Create: `core/scheduler/LeitnerPolicy.kt`, `core/scheduler/Scheduler.kt`
- Test: `app/src/test/java/gr/dimitris/app/core/data/FakeScheduleDao.kt`, `core/scheduler/LeitnerPolicyTest.kt`, `SchedulerTest.kt`

**Interfaces:**
- Consumes: `ScheduleDao` (`upsert`, `get`, `due`, `all`), `Schedule`, `Outcome`, `ModuleId`, `now()`.
- Produces: `LeitnerPolicy.nextBox(box, outcome, cueLevel): Int`, `LeitnerPolicy.intervalMillis(box): Long`, `MIN_BOX=1`, `MAX_BOX=5`, `DAY_MS`; `Scheduler(schedules, clock)` with `suspend fun due(module): List<Schedule>`, `suspend fun record(itemId, module, outcome, cueLevel): Schedule`.

- [ ] **Step 1: Fake DAO and failing tests**

`app/src/test/java/gr/dimitris/app/core/data/FakeScheduleDao.kt`:
```kotlin
package gr.dimitris.app.core.data

class FakeScheduleDao : ScheduleDao {
    val rows = mutableMapOf<Pair<String, ModuleId>, Schedule>()
    override suspend fun upsert(schedule: Schedule) { rows[schedule.itemId to schedule.module] = schedule }
    override suspend fun get(itemId: String, module: ModuleId): Schedule? = rows[itemId to module]?.takeIf { !it.deleted }
    override suspend fun due(module: ModuleId, now: Long): List<Schedule> =
        rows.values.filter { it.module == module && !it.deleted && it.nextDueAt <= now }.sortedBy { it.nextDueAt }
    override suspend fun all(module: ModuleId): List<Schedule> = rows.values.filter { it.module == module && !it.deleted }
}
```

`core/scheduler/LeitnerPolicyTest.kt`:
```kotlin
package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test

class LeitnerPolicyTest {
    @Test fun `correct with little help moves up`() {
        assertEquals(2, LeitnerPolicy.nextBox(1, Outcome.CORRECT, 0))
        assertEquals(3, LeitnerPolicy.nextBox(2, Outcome.CORRECT, 1))
    }
    @Test fun `correct at syllable cue stays`() = assertEquals(2, LeitnerPolicy.nextBox(2, Outcome.CORRECT, 2))
    @Test fun `assisted stays`() = assertEquals(3, LeitnerPolicy.nextBox(3, Outcome.ASSISTED, 4))
    @Test fun `skipped moves down`() = assertEquals(2, LeitnerPolicy.nextBox(3, Outcome.SKIPPED, null))
    @Test fun `clamped to 1 and 5`() {
        assertEquals(1, LeitnerPolicy.nextBox(1, Outcome.SKIPPED, null))
        assertEquals(5, LeitnerPolicy.nextBox(5, Outcome.CORRECT, 0))
    }
    @Test fun `null cue counts as no help`() = assertEquals(2, LeitnerPolicy.nextBox(1, Outcome.CORRECT, null))
    @Test fun `intervals double per box`() {
        assertEquals(1 * LeitnerPolicy.DAY_MS, LeitnerPolicy.intervalMillis(1))
        assertEquals(16 * LeitnerPolicy.DAY_MS, LeitnerPolicy.intervalMillis(5))
        assertEquals(16 * LeitnerPolicy.DAY_MS, LeitnerPolicy.intervalMillis(9))
    }
}
```

`core/scheduler/SchedulerTest.kt`:
```kotlin
package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.FakeScheduleDao
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SchedulerTest {
    private val dao = FakeScheduleDao()
    private var clock = 1_000_000L
    private val scheduler = Scheduler(dao) { clock }

    @Test fun `first record creates box 1 row then moves up on success`() = runTest {
        val s = scheduler.record("a", ModuleId.WORDCOACH, Outcome.CORRECT, 0)
        assertEquals(2, s.box)
        assertEquals(clock + 2 * LeitnerPolicy.DAY_MS, s.nextDueAt)
        assertEquals(1, s.streak)
        assertEquals(clock, s.lastSeenAt)
    }

    @Test fun `skip resets streak and comes back tomorrow`() = runTest {
        scheduler.record("a", ModuleId.WORDCOACH, Outcome.CORRECT, 0)
        scheduler.record("a", ModuleId.WORDCOACH, Outcome.CORRECT, 1)
        val s = scheduler.record("a", ModuleId.WORDCOACH, Outcome.SKIPPED, null)
        assertEquals(2, s.box)
        assertEquals(0, s.streak)
        assertEquals(clock + 2 * LeitnerPolicy.DAY_MS, s.nextDueAt)
    }

    @Test fun `due lists only rows whose time has come`() = runTest {
        scheduler.record("a", ModuleId.WORDCOACH, Outcome.CORRECT, 0)   // due in 2 days
        scheduler.record("b", ModuleId.WORDCOACH, Outcome.ASSISTED, 4)  // box 1, due in 1 day
        clock += LeitnerPolicy.DAY_MS + 1
        assertEquals(listOf("b"), scheduler.due(ModuleId.WORDCOACH).map { it.itemId })
    }
}
```

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.core.scheduler.*'` — Expected: compilation error.

- [ ] **Step 2: Implement**

`core/scheduler/LeitnerPolicy.kt`:
```kotlin
package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.Outcome

/**
 * CHRIS: rewrite me. This is the move-up / move-down rule of the spaced-repetition system.
 * Claude wrote a first version so nothing blocks; the tests in LeitnerPolicyTest describe the contract.
 *
 * Boxes 1..5. An item in box b comes back after intervalMillis(b).
 */
object LeitnerPolicy {
    const val MIN_BOX = 1
    const val MAX_BOX = 5
    const val DAY_MS = 24L * 60 * 60 * 1000
    private val intervalDays = intArrayOf(1, 2, 4, 8, 16)

    fun nextBox(box: Int, outcome: Outcome, cueLevel: Int?): Int = when (outcome) {
        Outcome.CORRECT -> if ((cueLevel ?: 0) <= 1) (box + 1).coerceAtMost(MAX_BOX) else box
        Outcome.ASSISTED -> box
        Outcome.SKIPPED -> (box - 1).coerceAtLeast(MIN_BOX)
    }.coerceIn(MIN_BOX, MAX_BOX)

    fun intervalMillis(box: Int): Long = intervalDays[(box - MIN_BOX).coerceIn(0, intervalDays.lastIndex)] * DAY_MS
}
```

`core/scheduler/Scheduler.kt`:
```kotlin
package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.ScheduleDao
import gr.dimitris.app.core.data.now

/** Keeps one Schedule row per (item, module) and moves it through the Leitner boxes. */
class Scheduler(private val schedules: ScheduleDao, private val clock: () -> Long = ::now) {

    suspend fun due(module: ModuleId): List<Schedule> = schedules.due(module, clock())

    suspend fun record(itemId: String, module: ModuleId, outcome: Outcome, cueLevel: Int?): Schedule {
        val t = clock()
        val current = schedules.get(itemId, module)
            ?: Schedule(itemId = itemId, module = module, box = LeitnerPolicy.MIN_BOX, nextDueAt = t, createdAt = t, updatedAt = t)
        val box = LeitnerPolicy.nextBox(current.box, outcome, cueLevel)
        val next = current.copy(
            box = box,
            nextDueAt = t + LeitnerPolicy.intervalMillis(box),
            lastSeenAt = t,
            streak = if (outcome == Outcome.CORRECT) current.streak + 1 else 0,
            updatedAt = t,
        )
        schedules.upsert(next)
        return next
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.core.scheduler.*'` — Expected: 10 pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/gr/dimitris/app/core/scheduler app/src/test
git commit -m "feat(phase2): Leitner policy and scheduler

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: SessionBuilder

**Files:**
- Create: `core/scheduler/SessionBuilder.kt`
- Test: `core/scheduler/SessionBuilderTest.kt`

**Interfaces:**
- Consumes: `ItemDao.activeOfKinds`, `ScheduleDao.all`, `Item`, `Schedule`, `Source`.
- Produces: `SessionBuilder(items, schedules, clock, newPerDay = 8, maxItems = 12)` with `suspend fun plan(module, kinds): List<Item>`, `internal fun sandwich(items, boxOf): List<Item>`, `startOfDay(epochMs): Long`.

- [ ] **Step 1: Failing tests**

`core/scheduler/SessionBuilderTest.kt`:
```kotlin
package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.FakeItemDao
import gr.dimitris.app.core.data.FakeScheduleDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.data.Source
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionBuilderTest {
    private val items = FakeItemDao()
    private val schedules = FakeScheduleDao()
    private val noon = 1_700_000_000_000L  // arbitrary fixed instant
    private val builder = SessionBuilder(items, schedules, clock = { noon }, newPerDay = 3, maxItems = 5)
    private val m = ModuleId.WORDCOACH

    private suspend fun word(text: String, source: Source = Source.SEED, createdAt: Long = 1): Item =
        Item(text = text, kind = ItemKind.WORD, source = source, createdAt = createdAt).also { items.upsert(it) }

    @Test fun `due items come first, then new ones up to the daily cap`() = runTest {
        val due = word("due"); val later = word("later")
        val n1 = word("n1", createdAt = 1); val n2 = word("n2", createdAt = 2); val n3 = word("n3", createdAt = 3); val n4 = word("n4", createdAt = 4)
        schedules.upsert(Schedule(due.id, m, box = 2, nextDueAt = noon - 1))
        schedules.upsert(Schedule(later.id, m, box = 3, nextDueAt = noon + 1))
        val plan = builder.plan(m, listOf(ItemKind.WORD))
        assertEquals(setOf(due.id, n1.id, n2.id, n3.id), plan.map { it.id }.toSet())
        assert(n4.id !in plan.map { it.id })
    }

    @Test fun `new items introduced today count against the cap`() = runTest {
        word("old"); val a = word("a"); val b = word("b")
        val old = items.rows.value.values.first { it.text == "old" }
        schedules.upsert(Schedule(old.id, m, box = 1, nextDueAt = noon + LeitnerPolicy.DAY_MS, createdAt = noon - 1000))  // introduced today
        schedules.upsert(Schedule(a.id, m, box = 1, nextDueAt = noon + LeitnerPolicy.DAY_MS, createdAt = noon - 2000))
        val plan = builder.plan(m, listOf(ItemKind.WORD))
        assertEquals(listOf(b.id), plan.map { it.id })   // cap 3, two already introduced today, one slot left
    }

    @Test fun `personal items are introduced before seed items`() = runTest {
        word("seed", Source.SEED, createdAt = 1)
        val mine = word("mine", Source.CAREGIVER, createdAt = 2)
        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 1, maxItems = 5).plan(m, listOf(ItemKind.WORD))
        assertEquals(listOf(mine.id), plan.map { it.id })
    }

    @Test fun `sandwich puts easy at both ends and hard in the middle`() {
        val a = Item(text = "a"); val b = Item(text = "b"); val c = Item(text = "c"); val d = Item(text = "d"); val e = Item(text = "e")
        val box = mapOf(a.id to 5, b.id to 4, c.id to 3, d.id to 2, e.id to 0)
        val out = builder.sandwich(listOf(e, d, c, b, a)) { box.getValue(it.id) }
        assertEquals(listOf("a", "c", "e", "d", "b"), out.map { it.text })
    }

    @Test fun `startOfDay is midnight local time`() {
        val start = startOfDay(noon)
        assert(start <= noon && noon - start < LeitnerPolicy.DAY_MS)
        assertEquals(start, startOfDay(start))
    }
}
```

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.core.scheduler.SessionBuilderTest'` — Expected: compilation error.

- [ ] **Step 2: Implement**

`core/scheduler/SessionBuilder.kt`:
```kotlin
package gr.dimitris.app.core.scheduler

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemDao
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.ScheduleDao
import gr.dimitris.app.core.data.Source
import gr.dimitris.app.core.data.now
import java.time.Instant
import java.time.ZoneId

fun startOfDay(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
    Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()

/**
 * Picks today's items for one module: everything due, then new items (personal before seed)
 * up to [newPerDay] introduced per day, capped at [maxItems], ordered easy–hard–easy.
 */
class SessionBuilder(
    private val items: ItemDao,
    private val schedules: ScheduleDao,
    private val clock: () -> Long = ::now,
    private val newPerDay: Int = 8,
    private val maxItems: Int = 12,
) {
    suspend fun plan(module: ModuleId, kinds: List<ItemKind>): List<Item> {
        val t = clock()
        val pool = items.activeOfKinds(kinds)
        val byId = pool.associateBy { it.id }
        val rows = schedules.all(module).filter { it.itemId in byId }
        val boxOf = rows.associate { it.itemId to it.box }

        val due = rows.filter { it.nextDueAt <= t }.sortedBy { it.nextDueAt }.mapNotNull { byId[it.itemId] }
        val introducedToday = rows.count { it.createdAt >= startOfDay(t) }
        val scheduledIds = rows.map { it.itemId }.toSet()
        val fresh = pool.filter { it.id !in scheduledIds }
            .sortedWith(compareBy<Item> { it.source != Source.CAREGIVER }.thenBy { it.createdAt })
            .take((newPerDay - introducedToday).coerceAtLeast(0))

        val chosen = (due + fresh).take(maxItems)
        return sandwich(chosen) { boxOf[it.id] ?: 0 }
    }

    /** Highest box first and last, lowest in the middle. */
    internal fun sandwich(items: List<Item>, boxOf: (Item) -> Int): List<Item> {
        val sorted = items.sortedByDescending(boxOf)
        val front = sorted.filterIndexed { i, _ -> i % 2 == 0 }
        val back = sorted.filterIndexed { i, _ -> i % 2 == 1 }.reversed()
        return front + back
    }
}
```

- [ ] **Step 3: Run tests**

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.core.scheduler.*'` — Expected: all pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/gr/dimitris/app/core/scheduler/SessionBuilder.kt app/src/test
git commit -m "feat(phase2): session builder with daily new-item cap and easy-hard-easy order

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Cue ladder state and speech match (pure)

**Files:**
- Create: `modules/wordcoach/CueLadder.kt`, `core/speech/SpeechMatch.kt`
- Test: `modules/wordcoach/CueLadderTest.kt`, `core/speech/SpeechMatchTest.kt`

**Interfaces:**
- Produces: `CueLadder(item)` with `levels: List<Int>`, `level: Int`, `canHint: Boolean`, `hint(): Int`, `reset()`, `outcomeFor(confirmed: Boolean): Outcome`, `cueText(): String?`, `showsWord: Boolean`; `SpeechMatch.matches(said, target): Boolean`, `SpeechMatch.key(s)`.

- [ ] **Step 1: Failing tests**

`modules/wordcoach/CueLadderTest.kt`:
```kotlin
package gr.dimitris.app.modules.wordcoach

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CueLadderTest {
    private val full = Item(text = "καφές", firstSound = "κ", firstSyllable = "κα")
    private val noSyllable = Item(text = "καφές", firstSound = "κ", firstSyllable = null)

    @Test fun `walks 0 1 2 3 4 when a syllable exists`() {
        val l = CueLadder(full)
        assertEquals(listOf(0, 1, 2, 3, 4), l.levels)
        assertNull(l.cueText())
        assertEquals("κ", l.hint().let { l.cueText() })
        assertEquals("κα", l.hint().let { l.cueText() })
        assertEquals("καφές", l.hint().let { l.cueText() })
        assertFalse(l.showsWord)
        l.hint()
        assertTrue(l.showsWord)
        assertFalse(l.canHint)
        assertEquals(4, l.hint())   // stays at the top
    }

    @Test fun `skips level 2 without a syllable`() {
        val l = CueLadder(noSyllable)
        assertEquals(listOf(0, 1, 3, 4), l.levels)
        l.hint(); l.hint()
        assertEquals(3, l.level)
    }

    @Test fun `outcome depends on the level reached`() {
        val l = CueLadder(full)
        assertEquals(Outcome.CORRECT, l.outcomeFor(confirmed = true))
        l.hint(); l.hint()
        assertEquals(Outcome.CORRECT, l.outcomeFor(true))
        l.hint()
        assertEquals(Outcome.ASSISTED, l.outcomeFor(true))
        assertEquals(Outcome.SKIPPED, l.outcomeFor(false))
    }

    @Test fun `reset returns to picture only`() {
        val l = CueLadder(full).apply { hint(); hint() }
        l.reset()
        assertEquals(0, l.level)
    }
}
```

`core/speech/SpeechMatchTest.kt`:
```kotlin
package gr.dimitris.app.core.speech

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechMatchTest {
    @Test fun `exact after normalisation`() = assertTrue(SpeechMatch.matches("Καφές", "καφές"))
    @Test fun `accents and case ignored`() = assertTrue(SpeechMatch.matches("ΚΑΦΕΣ", "καφές"))
    @Test fun `target inside a longer utterance`() = assertTrue(SpeechMatch.matches("θέλω καφέ τώρα", "καφέ"))
    @Test fun `one letter off is accepted for words of four or more letters`() = assertTrue(SpeechMatch.matches("καφε", "καφές"))
    @Test fun `different word is rejected`() = assertFalse(SpeechMatch.matches("νερό", "καφές"))
    @Test fun `short words must match exactly`() = assertFalse(SpeechMatch.matches("να", "ναι"))
}
```

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.modules.wordcoach.CueLadderTest' --tests 'gr.dimitris.app.core.speech.SpeechMatchTest'` — Expected: compilation error.

- [ ] **Step 2: Implement**

`modules/wordcoach/CueLadder.kt`:
```kotlin
package gr.dimitris.app.modules.wordcoach

import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Outcome

/**
 * The five cue levels for one item. Level 2 disappears when no first syllable is known.
 * 0 picture · 1 first sound · 2 first syllable · 3 word spoken · 4 word spoken and written.
 */
class CueLadder(private val item: Item) {
    val levels: List<Int> = listOfNotNull(0, 1, if (item.firstSyllable != null) 2 else null, 3, 4)
    private var index = 0

    val level: Int get() = levels[index]
    val canHint: Boolean get() = index < levels.lastIndex
    val showsWord: Boolean get() = level == 4

    fun hint(): Int { if (canHint) index++; return level }
    fun reset() { index = 0 }

    /** What to show and say at the current level; null at level 0. */
    fun cueText(): String? = when (level) {
        1 -> item.firstSound
        2 -> item.firstSyllable
        3, 4 -> item.text
        else -> null
    }

    fun outcomeFor(confirmed: Boolean): Outcome = when {
        !confirmed -> Outcome.SKIPPED
        level <= 2 -> Outcome.CORRECT
        else -> Outcome.ASSISTED
    }
}
```

`core/speech/SpeechMatch.kt`:
```kotlin
package gr.dimitris.app.core.speech

import gr.dimitris.app.core.greek.Greek

/** Lenient comparison of what the recognizer heard with the target word. Encouragement only, never a gate. */
object SpeechMatch {
    fun key(s: String): String = Greek.stripAccents(Greek.normalize(s)).replace(Regex("[^\\p{L}\\p{N} ]"), "")

    fun matches(said: String, target: String): Boolean {
        val s = key(said); val t = key(target)
        if (t.isEmpty()) return false
        if (s == t || s.split(' ').any { it == t } || (t.length >= 4 && s.contains(t))) return true
        if (t.length < 4) return false
        return s.split(' ').any { levenshtein(it, t) <= 1 }
    }

    private fun levenshtein(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            cur.copyInto(prev)
        }
        return prev[b.length]
    }
}
```

- [ ] **Step 3: Run tests, commit**

Run: `./gradlew -q testDebugUnitTest` — Expected: all pass.
```bash
git add app/src/main/java/gr/dimitris/app/modules/wordcoach/CueLadder.kt app/src/main/java/gr/dimitris/app/core/speech/SpeechMatch.kt app/src/test
git commit -m "feat(phase2): cue ladder state and lenient speech match

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: SpeechToText provider and new settings

**Files:**
- Create: `core/speech/SpeechToText.kt`, `core/speech/AndroidSpeechToText.kt`
- Modify: `core/settings/Settings.kt`, `AppGraph.kt` (add `stt`, `scheduler`)
- Test: `core/settings/SettingsTest.kt` (+2 cases)

**Interfaces:**
- Produces: `data class Transcript(text, confidence)`, `interface SpeechToText { val isAvailable: Boolean; suspend fun listen(maxSeconds: Int = 5): Result<Transcript> }`, `AndroidSpeechToText(context)`; `Settings.sttEnabled: Flow<Boolean>` (default false) + `setSttEnabled`; `Settings.enabledModules: Flow<Set<ModuleId>>` (default all) + `setModuleEnabled(id, on)`; `AppGraph.stt`, `AppGraph.scheduler`.

- [ ] **Step 1: Failing settings tests**

Add to `SettingsTest.kt`:
```kotlin
    @Test fun `speech recognition is off by default`() = runBlocking {
        val s = newSettings()
        assertEquals(false, s.sttEnabled.first())
        s.setSttEnabled(true)
        assertEquals(true, s.sttEnabled.first())
    }

    @Test fun `all modules enabled by default and can be switched off one at a time`() = runBlocking {
        val s = newSettings()
        assertEquals(gr.dimitris.app.core.data.ModuleId.entries.toSet(), s.enabledModules.first())
        s.setModuleEnabled(gr.dimitris.app.core.data.ModuleId.WORDCOACH, false)
        assertEquals(gr.dimitris.app.core.data.ModuleId.entries.toSet() - gr.dimitris.app.core.data.ModuleId.WORDCOACH, s.enabledModules.first())
        s.setModuleEnabled(gr.dimitris.app.core.data.ModuleId.WORDCOACH, true)
        assertEquals(gr.dimitris.app.core.data.ModuleId.entries.toSet(), s.enabledModules.first())
    }
```

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.core.settings.SettingsTest'` — Expected: compilation error.

- [ ] **Step 2: Settings**

In `Settings.kt` add (imports `stringSetPreferencesKey`, `gr.dimitris.app.core.data.ModuleId`):
```kotlin
    val sttEnabled: Flow<Boolean> = store.data.map { it[STT_ENABLED] ?: false }
    suspend fun setSttEnabled(on: Boolean) { store.edit { it[STT_ENABLED] = on } }

    /** Modules switched off by caregivers; everything is on unless listed here. */
    val enabledModules: Flow<Set<ModuleId>> = store.data.map { p ->
        val off = p[DISABLED_MODULES].orEmpty()
        ModuleId.entries.filter { it.name !in off }.toSet()
    }
    suspend fun setModuleEnabled(id: ModuleId, on: Boolean) {
        store.edit { p ->
            val off = p[DISABLED_MODULES].orEmpty().toMutableSet()
            if (on) off.remove(id.name) else off.add(id.name)
            p[DISABLED_MODULES] = off
        }
    }
```
and in the companion: `private val STT_ENABLED = booleanPreferencesKey("stt_enabled")`, `private val DISABLED_MODULES = stringSetPreferencesKey("disabled_modules")`.

- [ ] **Step 3: SpeechToText**

`core/speech/SpeechToText.kt`:
```kotlin
package gr.dimitris.app.core.speech

data class Transcript(val text: String, val confidence: Float)

/** Hears Greek. Android's recognizer first; a Whisper-class cloud provider can replace it later. */
interface SpeechToText {
    val isAvailable: Boolean
    suspend fun listen(maxSeconds: Int = 5): Result<Transcript>
}
```

`core/speech/AndroidSpeechToText.kt`:
```kotlin
package gr.dimitris.app.core.speech

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class AndroidSpeechToText(private val context: Context) : SpeechToText {
    override val isAvailable: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun listen(maxSeconds: Int): Result<Transcript> = withContext(Dispatchers.Main) {
        if (!isAvailable) return@withContext Result.failure(IllegalStateException("Δεν υπάρχει αναγνώριση ομιλίας"))
        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        try {
            suspendCancellableCoroutine { cont ->
                recognizer.setRecognitionListener(object : RecognitionListener {
                    override fun onResults(results: Bundle) {
                        val texts = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                        val scores = results.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                        val best = texts.firstOrNull()
                        if (cont.isActive) cont.resume(
                            if (best == null) Result.failure(IllegalStateException("Δεν άκουσα τίποτα"))
                            else Result.success(Transcript(best, scores?.firstOrNull() ?: 0f))
                        )
                    }
                    override fun onError(error: Int) { if (cont.isActive) cont.resume(Result.failure(IllegalStateException("Σφάλμα αναγνώρισης $error"))) }
                    override fun onReadyForSpeech(params: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onPartialResults(partialResults: Bundle?) {}
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "el-GR")
                    .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                    .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, maxSeconds * 1000L)
                recognizer.startListening(intent)
                cont.invokeOnCancellation { recognizer.cancel() }
            }
        } finally {
            recognizer.destroy()
        }
    }
}
```

- [ ] **Step 4: AppGraph**

Add to `AppGraph` (imports `gr.dimitris.app.core.scheduler.Scheduler`, `gr.dimitris.app.core.speech.AndroidSpeechToText`, `gr.dimitris.app.core.speech.SpeechToText`):
```kotlin
    val stt: SpeechToText = AndroidSpeechToText(app)
    val scheduler: Scheduler get() = Scheduler(db.schedules())
```

- [ ] **Step 5: Tests, build, commit**

Run: `./gradlew -q testDebugUnitTest assembleDebug` — Expected: pass.
```bash
git add app/src/main app/src/test
git commit -m "feat(phase2): speech-to-text provider, module and recognition settings

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Word coach module (ViewModel + screen) and module registration

**Files:**
- Modify: `modules/Module.kt` (add `practiceFor` default), `AppGraph.kt` (`modules = listOf(WordCoachModule)`)
- Create: `modules/wordcoach/WordCoachModule.kt`, `WordCoachViewModel.kt`, `WordCoachScreen.kt`

**Interfaces:**
- Consumes: `CueLadder`, `Scheduler`, `SessionBuilder`, `ItemSpeaker` (`graph.speaker`), `SpeechToText` (`graph.stt`), `SpeechMatch`, `Recorder`, `Player`, `ItemRepository.addRecording`, `AttemptDao.insert`, `Settings.sttEnabled`, `Feedback`, `PictureCard`, `BigButton`, `QuietButton`, `SuccessMark`, `DimitrisScreen`.
- Produces: `Module.practiceFor(graph)` (default = `planFor`), `WordCoachModule` object, `WordCoachScreen(items, sessionId, onDone)`, `WordCoachViewModel(graph, items, sessionId)` with `state: StateFlow<WordCoachState>`.

- [ ] **Step 1: Module interface default**

In `modules/Module.kt` add after `planFor`:
```kotlin
    /** Items for free practice from the Today grid. Defaults to the session plan; modules may fall back to random items. */
    suspend fun practiceFor(graph: AppGraph): List<Item> = planFor(graph)
```

- [ ] **Step 2: Module object**

`modules/wordcoach/WordCoachModule.kt`:
```kotlin
package gr.dimitris.app.modules.wordcoach

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.RecordVoiceOver
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.scheduler.SessionBuilder
import gr.dimitris.app.modules.Module

object WordCoachModule : Module {
    override val id = ModuleId.WORDCOACH
    override val titleGreek = "Λέξεις"
    override val icon: ImageVector = Icons.Rounded.RecordVoiceOver
    private val kinds = listOf(ItemKind.WORD, ItemKind.PHRASE)

    override suspend fun planFor(graph: AppGraph): List<Item> =
        SessionBuilder(graph.db.items(), graph.db.schedules()).plan(id, kinds)

    override suspend fun practiceFor(graph: AppGraph): List<Item> =
        planFor(graph).ifEmpty { graph.db.items().activeOfKinds(kinds).shuffled().take(8) }

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit) = WordCoachScreen(items, sessionId, onDone)
}
```

In `AppGraph.kt` change `val modules: List<Module> = emptyList()` to `val modules: List<Module> = listOf(WordCoachModule)` (import `gr.dimitris.app.modules.wordcoach.WordCoachModule`).

- [ ] **Step 3: ViewModel**

`modules/wordcoach/WordCoachViewModel.kt`:
```kotlin
package gr.dimitris.app.modules.wordcoach

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Who
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.speech.SpeechMatch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class WordCoachState(
    val index: Int = 0,
    val total: Int,
    val item: Item,
    val level: Int = 0,
    val cueText: String? = null,
    val showsWord: Boolean = false,
    val canHint: Boolean = true,
    val isRecording: Boolean = false,
    val selfRecordingPath: String? = null,
    val sttOn: Boolean = false,
    val listening: Boolean = false,
    val heard: String? = null,
    val heardMatched: Boolean = false,
    /** True after "Το είπα!": success mark shown, only "Επόμενο" remains. */
    val confirmed: Boolean = false,
    val done: Boolean = false,
    val error: String? = null,
)

class WordCoachViewModel(private val graph: AppGraph, private val items: List<Item>, private val sessionId: String?) : ViewModel() {
    private var ladder = CueLadder(items.first())
    private var startedAt = now()
    private var selfRecordingId: String? = null

    private val _state = MutableStateFlow(WordCoachState(total = items.size, item = items.first()))
    val state: StateFlow<WordCoachState> = _state.asStateFlow()

    init {
        viewModelScope.launch { _state.update { it.copy(sttOn = graph.settings.sttEnabled.first() && graph.stt.isAvailable) } }
    }

    private fun publishLadder() = _state.update {
        it.copy(level = ladder.level, cueText = ladder.cueText(), showsWord = ladder.showsWord, canHint = ladder.canHint)
    }

    /** One more hint. Levels 1–2 are spoken by TTS; 3–4 use the model voice. */
    fun hint() {
        if (!ladder.canHint) return
        ladder.hint()
        publishLadder()
        speakCue()
    }

    fun repeatCue() = speakCue()

    private fun speakCue() {
        val s = _state.value
        viewModelScope.launch {
            when (s.level) {
                1, 2 -> ladder.cueText()?.let { graph.speaker.speakText(it) }
                3, 4 -> graph.speaker.speak(s.item)
                else -> Unit
            }
        }
    }

    fun toggleRecording() {
        if (_state.value.isRecording) {
            runCatching { graph.recorder.stop() }
                .onSuccess { rec ->
                    _state.update { it.copy(isRecording = false, selfRecordingPath = rec.file.absolutePath) }
                    viewModelScope.launch {
                        selfRecordingId = graph.items.addRecording(_state.value.item.id, rec.file, rec.durationMs, Who.DIMITRIS).id
                    }
                }
                .onFailure { e -> graph.errors.record("wordcoach record stop", e); _state.update { it.copy(isRecording = false, error = "Πολύ σύντομη ηχογράφηση") } }
        } else {
            runCatching { graph.recorder.start() }
                .onSuccess { _state.update { it.copy(isRecording = true, error = null) } }
                .onFailure { e -> graph.errors.record("wordcoach record start", e); _state.update { it.copy(error = "Δεν ξεκίνησε η ηχογράφηση") } }
        }
    }

    /** Model voice, then his own recording. */
    fun playComparison() {
        val path = _state.value.selfRecordingPath ?: return
        viewModelScope.launch {
            graph.speaker.speak(_state.value.item)
            graph.player.play(File(path)).onFailure { graph.errors.record("wordcoach compare", it) }
        }
    }

    /** Optional soft recognition: encouragement only, never a gate. */
    fun listen() {
        if (!_state.value.sttOn || _state.value.listening) return
        _state.update { it.copy(listening = true, heard = null, heardMatched = false) }
        viewModelScope.launch {
            val result = graph.stt.listen(5)
            val text = result.getOrNull()?.text
            val matched = text != null && SpeechMatch.matches(text, _state.value.item.text)
            if (matched) graph.feedback.success()
            _state.update { it.copy(listening = false, heard = text, heardMatched = matched) }
        }
    }

    fun confirm() = finish(confirmed = true)
    fun skip() = finish(confirmed = false)

    private fun finish(confirmed: Boolean) {
        if (_state.value.isRecording) toggleRecording()
        val s = _state.value
        val outcome = ladder.outcomeFor(confirmed)
        val detail = s.heard?.let { """{"heard":${jsonString(it)},"matched":${s.heardMatched}}""" } ?: "{}"
        viewModelScope.launch {
            runCatching {
                graph.db.attempts().insert(
                    Attempt(itemId = s.item.id, module = ModuleId.WORDCOACH, sessionId = sessionId, startedAt = startedAt,
                        durationMs = now() - startedAt, outcome = outcome, cueLevel = ladder.level, selfRecordingId = selfRecordingId, detail = detail)
                )
                graph.scheduler.record(s.item.id, ModuleId.WORDCOACH, outcome, ladder.level)
            }.onFailure { graph.errors.record("wordcoach finish", it) }
        }
        if (confirmed) {
            graph.feedback.success()
            _state.update { it.copy(confirmed = true) }
        } else {
            graph.feedback.nudge()
            next()
        }
    }

    fun next() {
        val i = _state.value.index + 1
        if (i >= items.size) { _state.update { it.copy(done = true) }; return }
        ladder = CueLadder(items[i])
        startedAt = now()
        selfRecordingId = null
        _state.value = WordCoachState(index = i, total = items.size, item = items[i], sttOn = _state.value.sttOn)
    }

    override fun onCleared() {
        if (graph.recorder.isRecording) graph.recorder.cancel()
    }

    private fun jsonString(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
```

- [ ] **Step 4: Screen**

`modules/wordcoach/WordCoachScreen.kt`:
```kotlin
package gr.dimitris.app.modules.wordcoach

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Compare
import androidx.compose.material.icons.rounded.Hearing
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.PictureCard
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun WordCoachScreen(items: List<Item>, sessionId: String?, onDone: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: WordCoachViewModel = viewModel(key = "wordcoach-${sessionId ?: "practice"}-${items.size}") { WordCoachViewModel(graph, items, sessionId) }
    val s by vm.state.collectAsStateWithLifecycle()
    val askMic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.toggleRecording() }

    LaunchedEffect(s.done) { if (s.done) onDone() }

    DimitrisScreen(
        title = "Λέξεις ${s.index + 1}/${s.total}",
        onBack = onDone,
        bottom = {
            if (s.confirmed) {
                BigButton("Επόμενο", onClick = vm::next, tone = ButtonTone.Success)
            } else {
                Row {
                    BigButton("Βοήθεια", onClick = vm::hint, tone = ButtonTone.Secondary, enabled = s.canHint, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(Sizes.gapSmall))
                    BigButton("Το είπα!", onClick = vm::confirm, tone = ButtonTone.Success, modifier = Modifier.weight(1f))
                }
                Spacer(Modifier.height(Sizes.gapSmall))
                QuietButton("Παράλειψη", onClick = vm::skip)
            }
        },
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            PictureCard(imagePath = s.item.imagePath, label = if (s.showsWord) s.item.text else null, onClick = vm::repeatCue,
                modifier = Modifier.fillMaxWidth(0.7f))
            SuccessMark(visible = s.confirmed)
        }
        Spacer(Modifier.height(Sizes.gapSmall))
        if (s.level in 1..2) {
            Text(s.cueText ?: "", style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(Sizes.gapSmall))
        Row {
            QuietButton("Άκου", onClick = vm::repeatCue, icon = Icons.Rounded.VolumeUp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(Sizes.gapSmall))
            QuietButton(
                if (s.isRecording) "Στοπ" else "Πες το",
                onClick = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                icon = if (s.isRecording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                modifier = Modifier.weight(1f),
            )
        }
        if (s.selfRecordingPath != null && !s.isRecording) {
            Spacer(Modifier.height(Sizes.gapSmall))
            QuietButton("Σύγκριση", onClick = vm::playComparison, icon = Icons.Rounded.Compare)
        }
        if (s.sttOn) {
            Spacer(Modifier.height(Sizes.gapSmall))
            QuietButton(if (s.listening) "Ακούω..." else "Άκουσέ με", onClick = vm::listen, icon = Icons.Rounded.Hearing)
            if (s.heard != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        if (s.heardMatched) "Άκουσα «${s.heard}». Μπράβο!" else "Άκουσα «${s.heard}». Δοκίμασε ξανά αν θέλεις.",
                        style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center,
                    )
                }
            }
        }
        if (s.error != null) {
            Spacer(Modifier.height(Sizes.gapSmall))
            Text(s.error!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
```

- [ ] **Step 5: Build**

Run: `./gradlew -q assembleDebug testDebugUnitTest` — Expected: passes (nothing reaches the screen until Task 6).

- [ ] **Step 6: Commit**

```bash
git add app/src/main
git commit -m "feat(phase2): word coach module with cue ladder, recording and soft recognition

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 6: Session runner, free practice, Today module grid

**Files:**
- Create: `today/SessionViewModel.kt`, `today/PracticeScreen.kt`, `today/ModuleGrid.kt`
- Modify: `today/SessionScreen.kt` (rewrite), `today/TodayScreen.kt` (grid), `Nav.kt` (PRACTICE route)
- Test: `app/src/androidTest/java/gr/dimitris/app/today/SessionFlowTest.kt`

**Interfaces:**
- Consumes: `AppGraph.modules`, `Module.planFor/practiceFor/Screen`, `Settings.enabledModules`, `SessionDao.upsert/get`, `TextToSpeech`, `Feedback`.
- Produces: `Routes.PRACTICE = "practice/{moduleId}"`, `Routes.practice(id)`, `SessionViewModel(graph)` with `state: StateFlow<SessionStep>`, `SessionStep` (`Loading`, `Run`, `Summary`, `Empty`), `PracticeScreen(moduleId, onDone)`, `ModuleGrid(modules, onOpen)`.

- [ ] **Step 1: ViewModel**

`today/SessionViewModel.kt`:
```kotlin
package gr.dimitris.app.today

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.Session
import gr.dimitris.app.core.data.now
import gr.dimitris.app.modules.Module
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class SessionStep {
    object Loading : SessionStep()
    object Empty : SessionStep()
    data class Run(val index: Int, val module: Module, val items: List<Item>, val sessionId: String) : SessionStep()
    data class Summary(val completed: Int, val planned: Int) : SessionStep()
}

/** Builds today's mixed session from the enabled modules and walks through them one by one. */
class SessionViewModel(private val graph: AppGraph) : ViewModel() {
    private val _state = MutableStateFlow<SessionStep>(SessionStep.Loading)
    val state: StateFlow<SessionStep> = _state.asStateFlow()

    private var plans: List<Pair<Module, List<Item>>> = emptyList()
    private var session: Session? = null
    private var completed = 0

    init {
        viewModelScope.launch {
            val enabled = graph.settings.enabledModules.first()
            plans = graph.modules.filter { it.id in enabled }
                .mapNotNull { m -> runCatching { m.planFor(graph) }.getOrElse { graph.errors.record("plan ${m.id}", it); emptyList() }.takeIf { it.isNotEmpty() }?.let { m to it } }
            if (plans.isEmpty()) {
                _state.value = SessionStep.Empty
                graph.tts.speak("Τίποτα για σήμερα. Τα λέμε αύριο!", graph.settings.speechRate.first())
                return@launch
            }
            val s = Session(startedAt = now(), plannedModules = plans.joinToString(",") { it.first.id.name }, plannedItemCount = plans.sumOf { it.second.size })
            runCatching { graph.db.sessions().upsert(s) }.onFailure { graph.errors.record("session start", it) }
            session = s
            _state.value = SessionStep.Run(0, plans[0].first, plans[0].second, s.id)
        }
    }

    fun moduleDone() {
        val step = _state.value as? SessionStep.Run ?: return
        completed += step.items.size
        val next = step.index + 1
        if (next < plans.size) {
            _state.value = SessionStep.Run(next, plans[next].first, plans[next].second, step.sessionId)
            return
        }
        val s = session
        val planned = plans.sumOf { it.second.size }
        viewModelScope.launch {
            if (s != null) runCatching { graph.db.sessions().upsert(s.copy(endedAt = now(), completedItemCount = completed, updatedAt = now())) }
                .onFailure { graph.errors.record("session end", it) }
            _state.value = SessionStep.Summary(completed, planned)
            graph.feedback.success()
            graph.tts.speak("Μπράβο Δημήτρη! Έκανες $completed λέξεις σήμερα.", graph.settings.speechRate.first())
        }
    }
}
```

- [ ] **Step 2: Session, practice and grid screens**

`today/SessionScreen.kt` (replace the whole file):
```kotlin
package gr.dimitris.app.today

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun SessionScreen(onDone: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: SessionViewModel = viewModel { SessionViewModel(graph) }
    val step by vm.state.collectAsStateWithLifecycle()

    when (val s = step) {
        SessionStep.Loading -> DimitrisScreen { Text("Ετοιμάζω τη σημερινή άσκηση...", style = MaterialTheme.typography.headlineMedium) }
        SessionStep.Empty -> DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            Text("Τίποτα για σήμερα. Τα λέμε αύριο!", style = MaterialTheme.typography.headlineMedium)
        }
        is SessionStep.Run -> key(s.index) { s.module.Screen(items = s.items, sessionId = s.sessionId, onDone = vm::moduleDone) }
        is SessionStep.Summary -> DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            SuccessMark(visible = true)
            Spacer(Modifier.height(Sizes.gap))
            Text("Μπράβο Δημήτρη!", style = MaterialTheme.typography.displayLarge)
            Spacer(Modifier.height(Sizes.gapSmall))
            Text("Έκανες ${s.completed} από ${s.planned} λέξεις σήμερα.", style = MaterialTheme.typography.bodyLarge)
        }
    }
}
```

`today/PracticeScreen.kt`:
```kotlin
package gr.dimitris.app.today

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.DimitrisScreen

/** Free practice of one module from the Today grid: same screen, no session row. */
@Composable
fun PracticeScreen(moduleId: ModuleId, onDone: () -> Unit) {
    val graph = LocalAppGraph.current
    val module = remember(moduleId) { graph.modules.first { it.id == moduleId } }
    var items by remember { mutableStateOf<List<Item>?>(null) }

    LaunchedEffect(moduleId) {
        items = runCatching { module.practiceFor(graph) }.getOrElse { graph.errors.record("practice ${module.id}", it); emptyList() }
    }

    when (val list = items) {
        null -> DimitrisScreen { Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium) }
        else -> if (list.isEmpty()) DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            Text("Δεν υπάρχουν λέξεις ακόμα. Ζήτα από κάποιον να προσθέσει.", style = MaterialTheme.typography.headlineMedium)
        } else module.Screen(items = list, sessionId = null, onDone = onDone)
    }
}
```

`today/ModuleGrid.kt`:
```kotlin
package gr.dimitris.app.today

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import gr.dimitris.app.modules.Module
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun ModuleGrid(modules: List<Module>, onOpen: (Module) -> Unit, modifier: Modifier = Modifier) {
    val feedback = LocalFeedback.current
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        horizontalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
        verticalArrangement = Arrangement.spacedBy(Sizes.gapSmall),
        modifier = modifier.fillMaxWidth(),
    ) {
        items(modules, key = { it.id.name }) { m ->
            Card(
                onClick = { feedback.tap(); onOpen(m) },
                shape = RoundedCornerShape(Sizes.corner),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                modifier = Modifier.heightIn(min = 120.dp),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                    Icon(m.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                    Text(m.titleGreek, style = MaterialTheme.typography.titleLarge)
                }
            }
        }
    }
}
```

- [ ] **Step 3: Today grid and route**

In `TodayScreen.kt`: collect `val enabled by graph.settings.enabledModules.collectAsStateWithLifecycle(initialValue = emptySet())`, add a parameter `onPractice: (ModuleId) -> Unit`, and after the TTS card block add:
```kotlin
        val modules = graph.modules.filter { it.id in enabled }
        if (modules.isNotEmpty()) {
            Spacer(Modifier.height(Sizes.gap))
            Text("Εξάσκηση", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(Sizes.gapSmall))
            ModuleGrid(modules, onOpen = { onPractice(it.id) })
        }
```
In `Nav.kt`: add `const val PRACTICE = "practice/{moduleId}"` and `fun practice(id: ModuleId) = "practice/${id.name}"` to `Routes`; pass `onPractice = { nav.navigate(Routes.practice(it)) }` to `TodayScreen`; register:
```kotlin
        composable(Routes.PRACTICE) { entry ->
            val id = ModuleId.valueOf(entry.arguments?.getString("moduleId") ?: ModuleId.WORDCOACH.name)
            PracticeScreen(moduleId = id, onDone = { nav.popBackStack(Routes.TODAY, inclusive = false) })
        }
```

- [ ] **Step 4: Instrumented flow test**

`app/src/androidTest/java/gr/dimitris/app/today/SessionFlowTest.kt`:
```kotlin
package gr.dimitris.app.today

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodes
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import gr.dimitris.app.DimitrisApp
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class SessionFlowTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Before fun seedOneWord() = runBlocking {
        val graph = ApplicationProvider.getApplicationContext<DimitrisApp>().graph
        graph.items.save(Item(text = "νερό", category = Category.FOOD))
    }

    @Test fun startRunsWordCoachAndConfirmAdvances() {
        compose.onNodeWithText("Ξεκίνα").performClick()
        compose.waitUntil(15_000) { compose.onAllNodes(hasText("Το είπα!")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Βοήθεια").assertIsDisplayed()
        compose.onNodeWithText("Το είπα!").performClick()
        compose.onNodeWithText("Επόμενο").assertIsDisplayed()
    }
}
```

- [ ] **Step 5: Build, run everything, use it**

Run: `./gradlew -q testDebugUnitTest && ANDROID_SERIAL=emulator-5554 ./gradlew -q installDebug connectedDebugAndroidTest`
Expected: all green. On the emulator: "Ξεκίνα" runs through up to 12 words; "Βοήθεια" climbs the ladder (letter, syllable if known, spoken word, written word); "Το είπα!" shows the check and "Επόμενο"; the end screen says Μπράβο and speaks. Today shows an "Εξάσκηση" grid with "Λέξεις".

- [ ] **Step 6: Commit**

```bash
git add app/src/main app/src/androidTest
git commit -m "feat(phase2): daily session runner, free practice and Today module grid

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 7: Caregiver toggles for modules and speech recognition

**Files:**
- Modify: `caregiver/SettingsScreen.kt`

- [ ] **Step 1: Add two sections**

After the "Κλείδωμα φροντιστή" block and before "Σχετικά", add:
```kotlin
            Text("Ασκήσεις", style = MaterialTheme.typography.titleLarge)
            val enabled by graph.settings.enabledModules.collectAsStateWithLifecycle(initialValue = emptySet())
            graph.modules.forEach { m ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(m.titleGreek, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                    Switch(checked = m.id in enabled, onCheckedChange = { on -> scope.launch { graph.settings.setModuleEnabled(m.id, on) } })
                }
            }
            Spacer(Modifier.height(Sizes.gap))

            Text("Αναγνώριση ομιλίας (δοκιμαστικό)", style = MaterialTheme.typography.titleLarge)
            val stt by graph.settings.sttEnabled.collectAsStateWithLifecycle(initialValue = false)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (graph.stt.isAvailable) "Δείχνει τι άκουσε το τηλέφωνο. Ποτέ δεν τον κόβει." else "Η συσκευή δεν έχει αναγνώριση ομιλίας.",
                    style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f),
                )
                Switch(checked = stt && graph.stt.isAvailable, enabled = graph.stt.isAvailable, onCheckedChange = { on -> scope.launch { graph.settings.setSttEnabled(on) } })
            }
            Spacer(Modifier.height(Sizes.gap))
```

- [ ] **Step 2: Build, install, check, commit**

Run: `ANDROID_SERIAL=emulator-5554 ./gradlew -q installDebug` — switching "Λέξεις" off removes it from the Today grid and from sessions; switching recognition on shows "Άκουσέ με" in the word coach.
```bash
git add app/src/main
git commit -m "feat(phase2): caregiver toggles for modules and speech recognition

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 8: Phase 2 verification

- [ ] `./gradlew -q testDebugUnitTest && ANDROID_SERIAL=emulator-5554 ./gradlew -q connectedDebugAndroidTest` — all green.
- [ ] `ANDROID_SERIAL=R5CWC2C1KSJ ./gradlew -q installDebug`; on the phone: run a full session, use every button once (Βοήθεια ×4, Άκου, Πες το/Στοπ, Σύγκριση, Το είπα!, Παράλειψη, Επόμενο), enable recognition and say a word, finish and hear the summary. Caregiver → Σφάλματα must stay empty.
- [ ] Append "Phase 2 verified on <date>, <device>" with problems under this task; commit `docs(phase2): verification notes`.
