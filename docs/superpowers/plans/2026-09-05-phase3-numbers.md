# Dimitris' App — Phase 3 (Number Sense) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rebuild Dimitris' sense of quantity: seven levels from "which pile is bigger" with dots, through number lines, counting, Greek number words, up to 1000 and real euro prices, with the level adapting to how he does.

**Architecture:** Pure Kotlin first: `GreekNumbers` (number → Greek words), `Euro` (format/parse), an exercise model + generator, and `NumberProgression` (adaptive level, Chris-marked). Then a DB v3 column for prices on items (caregivers enter his real Wolt prices), and the `NumbersModule` with one screen that renders every exercise type with big targets. Number exercises are not items, so attempts use the synthetic item id `numbers:level:N` and carry the exercise in `detail`.

**Tech Stack:** as previous phases. Gson (already a dependency) for `Attempt.detail`.

**Spec:** `docs/superpowers/specs/2026-09-05-dimitris-app-design.md` (§7 phase 3, §10)

## Global Constraints

- Greek-only UI; 72dp minimum targets; primary actions at the bottom; no timers; success = icon + sound + haptic; wrong answers get a nudge and the right answer shown, never a red "X" alone.
- Levels exactly: 1 compare 0–10 with dots; 2 number line 0–10; 3 counting; 4 compare 0–100 digits only; 5 digit ↔ word; 6 compare and number line 0–1000; 7 euro (coin pick, price compare, pay).
- Attempts: `itemId = "numbers:level:N"`, `module = NUMBERS`, `outcome` CORRECT (right first try) / ASSISTED (right after being shown) / SKIPPED, `cueLevel = null`, `detail` JSON with the exercise and the answer given.
- `NumberProgression` implemented by Claude with a `// CHRIS: rewrite me` header and full tests (controller ruling).
- Every Room change keeps sync-ready columns and a committed schema; auto-migration only.
- Build from `/mnt/nvme2TB/tomas/.claude/worktrees/phase0`; instrumented tests with `ANDROID_SERIAL=emulator-5554`. Commits `feat(phase3): ...` ending with `Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>`.

---

## File structure

```
app/src/main/java/gr/dimitris/app/
  core/greek/GreekNumbers.kt, Euro.kt                          NEW
  core/data/Entities.kt (+ Item.priceCents), Daos.kt (+ withPrices), AppDatabase.kt (v3)
  core/settings/Settings.kt                                    + numbersLevel
  modules/numbers/Exercises.kt, ExerciseGenerator.kt, NumberProgression.kt, NumbersModule.kt, NumbersViewModel.kt, NumbersScreen.kt, ExerciseViews.kt   NEW
  caregiver/content/ItemEditViewModel.kt, ItemEditScreen.kt    + price field
  AppGraph.kt                                                  modules += NumbersModule
app/src/test/.../core/greek/GreekNumbersTest.kt, EuroTest.kt
app/src/test/.../modules/numbers/ExerciseGeneratorTest.kt, NumberProgressionTest.kt
app/src/androidTest/.../core/data/MigrationTest.kt (+ 2→3)
```

---

### Task 1: Greek number words and euro formatting

**Files:**
- Create: `core/greek/GreekNumbers.kt`, `core/greek/Euro.kt`
- Test: `core/greek/GreekNumbersTest.kt`, `core/greek/EuroTest.kt`

**Interfaces:**
- Produces: `GreekNumbers.words(n: Int): String` for 0..1000; `Euro.format(cents: Int): String` ("3,50 €"), `Euro.parse(text: String): Int?`, `Euro.denominations: List<Int>` (cents: 50, 100, 200, 500, 1000, 2000, 5000).

- [ ] **Step 1: Failing tests**

`GreekNumbersTest.kt`:
```kotlin
package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GreekNumbersTest {
    private fun w(n: Int) = GreekNumbers.words(n)
    @Test fun `zero to nineteen`() { assertEquals("μηδέν", w(0)); assertEquals("επτά", w(7)); assertEquals("δεκαπέντε", w(15)); assertEquals("δεκαεννέα", w(19)) }
    @Test fun `tens`() { assertEquals("είκοσι", w(20)); assertEquals("είκοσι ένα", w(21)); assertEquals("ενενήντα εννέα", w(99)) }
    @Test fun `hundreds`() { assertEquals("εκατό", w(100)); assertEquals("εκατόν ένα", w(101)); assertEquals("εκατόν είκοσι τρία", w(123)); assertEquals("διακόσια πενήντα", w(250)); assertEquals("εννιακόσια ενενήντα εννέα", w(999)) }
    @Test fun `thousand`() = assertEquals("χίλια", w(1000))
    @Test fun `out of range throws`() { assertThrows(IllegalArgumentException::class.java) { w(-1) }; assertThrows(IllegalArgumentException::class.java) { w(1001) } }
}
```

`EuroTest.kt`:
```kotlin
package gr.dimitris.app.core.greek

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EuroTest {
    @Test fun `formats cents with comma`() { assertEquals("3,50 €", Euro.format(350)); assertEquals("0,50 €", Euro.format(50)); assertEquals("12,00 €", Euro.format(1200)) }
    @Test fun `parses comma dot and whole numbers`() { assertEquals(350, Euro.parse("3,50")); assertEquals(350, Euro.parse("3.5")); assertEquals(300, Euro.parse(" 3 ")); assertEquals(1299, Euro.parse("12,99€")) }
    @Test fun `rejects garbage`() { assertNull(Euro.parse("")); assertNull(Euro.parse("abc")); assertNull(Euro.parse("3,505")) }
    @Test fun `denominations are the notes and the half coin`() = assertEquals(listOf(50, 100, 200, 500, 1000, 2000, 5000), Euro.denominations)
}
```

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.core.greek.*'` — Expected: compilation error.

- [ ] **Step 2: Implement**

`core/greek/GreekNumbers.kt`:
```kotlin
package gr.dimitris.app.core.greek

/** Cardinal numbers 0..1000 in the neuter form used for counting. */
object GreekNumbers {
    private val ones = arrayOf(
        "μηδέν", "ένα", "δύο", "τρία", "τέσσερα", "πέντε", "έξι", "επτά", "οκτώ", "εννέα",
        "δέκα", "έντεκα", "δώδεκα", "δεκατρία", "δεκατέσσερα", "δεκαπέντε", "δεκαέξι", "δεκαεπτά", "δεκαοκτώ", "δεκαεννέα",
    )
    private val tens = arrayOf("", "", "είκοσι", "τριάντα", "σαράντα", "πενήντα", "εξήντα", "εβδομήντα", "ογδόντα", "ενενήντα")
    private val hundreds = arrayOf("", "", "διακόσια", "τριακόσια", "τετρακόσια", "πεντακόσια", "εξακόσια", "επτακόσια", "οκτακόσια", "εννιακόσια")

    fun words(n: Int): String {
        require(n in 0..1000) { "Εκτός ορίων: $n" }
        if (n == 1000) return "χίλια"
        if (n < 20) return ones[n]
        val h = n / 100
        val rest = n % 100
        val restWords = when {
            rest == 0 -> ""
            rest < 20 -> ones[rest]
            rest % 10 == 0 -> tens[rest / 10]
            else -> tens[rest / 10] + " " + ones[rest % 10]
        }
        if (h == 0) return restWords
        val hundredWords = if (h == 1) (if (rest == 0) "εκατό" else "εκατόν") else hundreds[h]
        return if (rest == 0) hundredWords else "$hundredWords $restWords"
    }
}
```

`core/greek/Euro.kt`:
```kotlin
package gr.dimitris.app.core.greek

import java.util.Locale

object Euro {
    /** Coins and notes Dimitris will actually handle: 0,50 € up to 50 €. In cents. */
    val denominations: List<Int> = listOf(50, 100, 200, 500, 1000, 2000, 5000)

    fun format(cents: Int): String = String.format(Locale.US, "%d,%02d €", cents / 100, cents % 100)

    /** "3,50", "3.5", "3", "12,99€" → cents; anything else → null. */
    fun parse(text: String): Int? {
        val m = Regex("""^\s*(\d+)(?:[.,](\d{1,2}))?\s*€?\s*$""").find(text) ?: return null
        val whole = m.groupValues[1].toInt()
        val frac = m.groupValues[2].padEnd(2, '0').ifEmpty { "00" }.toInt()
        return whole * 100 + frac
    }
}
```

- [ ] **Step 3: Run tests, commit**

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.core.greek.*'` — Expected: all pass.
```bash
git add app/src/main/java/gr/dimitris/app/core/greek app/src/test
git commit -m "feat(phase3): Greek number words and euro formatting

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 2: Exercise model, generator, progression

**Files:**
- Create: `modules/numbers/Exercises.kt`, `ExerciseGenerator.kt`, `NumberProgression.kt`
- Test: `modules/numbers/ExerciseGeneratorTest.kt`, `NumberProgressionTest.kt`

**Interfaces:**
- Produces: `sealed class NumberExercise` (`Compare`, `NumberLine`, `Count`, `WordMatch`, `CoinPick`, `PriceCompare`, `Pay`) each with `level`, `prompt`, `answer: Int`, `options: List<Int>`; `data class Price(name, cents)`; `ExerciseGenerator(random).generate(level, prices)`, `.session(level, prices, count = 10)`; `NumberProgression.next(level, results: List<Boolean>): Int`, `MIN_LEVEL = 1`, `MAX_LEVEL = 7`.

- [ ] **Step 1: Failing tests**

`modules/numbers/ExerciseGeneratorTest.kt`:
```kotlin
package gr.dimitris.app.modules.numbers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ExerciseGeneratorTest {
    private val gen = ExerciseGenerator(Random(42))
    private val prices = listOf(Price("καφές", 250), Price("σουβλάκι", 380), Price("νερό", 50))

    private fun many(level: Int, n: Int = 200) = List(n) { gen.generate(level, prices) }

    @Test fun `level 1 compares distinct numbers 0 to 10 with dots`() {
        many(1).forEach { e ->
            e as NumberExercise.Compare
            assertTrue(e.a in 0..10 && e.b in 0..10 && e.a != e.b && e.showDots)
            assertEquals(maxOf(e.a, e.b), e.answer)
            assertEquals(listOf(e.a, e.b), e.options)
        }
    }

    @Test fun `level 2 number line has eleven ticks and the target on it`() {
        many(2).forEach { e -> e as NumberExercise.NumberLine; assertEquals((0..10).toList(), e.ticks); assertTrue(e.target in e.ticks); assertEquals(e.target, e.answer) }
    }

    @Test fun `level 3 counting offers three options including the count`() {
        many(3).forEach { e -> e as NumberExercise.Count; assertTrue(e.n in 1..10); assertEquals(3, e.options.size); assertTrue(e.n in e.options); assertEquals(e.options.toSet().size, 3) }
    }

    @Test fun `level 4 compares 0 to 100 without dots`() {
        many(4).forEach { e -> e as NumberExercise.Compare; assertTrue(e.a in 0..100 && e.b in 0..100 && !e.showDots) }
        assertTrue(many(4).any { (it as NumberExercise.Compare).a > 10 })
    }

    @Test fun `level 5 word match has three distinct options containing the number`() {
        many(5).forEach { e -> e as NumberExercise.WordMatch; assertTrue(e.number in 0..100); assertEquals(3, e.options.toSet().size); assertTrue(e.number in e.options); assertEquals(e.number, e.answer) }
        assertTrue(many(5).any { (it as NumberExercise.WordMatch).showWord } && many(5).any { !(it as NumberExercise.WordMatch).showWord })
    }

    @Test fun `level 6 mixes compare and number line up to 1000`() {
        val all = many(6)
        assertTrue(all.any { it is NumberExercise.Compare } && all.any { it is NumberExercise.NumberLine })
        all.forEach {
            when (it) {
                is NumberExercise.Compare -> assertTrue(it.a in 0..1000 && it.b in 0..1000 && it.a != it.b)
                is NumberExercise.NumberLine -> { assertEquals((0..1000 step 100).toList(), it.ticks); assertTrue(it.target in it.ticks) }
                else -> throw AssertionError("unexpected $it")
            }
        }
    }

    @Test fun `level 7 uses euro exercises and real prices`() {
        val all = many(7)
        assertTrue(all.any { it is NumberExercise.CoinPick } && all.any { it is NumberExercise.PriceCompare } && all.any { it is NumberExercise.Pay })
        all.filterIsInstance<NumberExercise.PriceCompare>().forEach { assertTrue(it.a in prices && it.b in prices && it.a != it.b); assertEquals(maxOf(it.a.cents, it.b.cents), it.answer) }
        all.filterIsInstance<NumberExercise.Pay>().forEach { e -> assertTrue(e.answer in e.options); assertTrue(e.answer >= e.priceCents); assertTrue(e.options.filter { it >= e.priceCents }.min() == e.answer) }
        all.filterIsInstance<NumberExercise.CoinPick>().forEach { assertTrue(it.answer in it.options && it.options.toSet().size == 3) }
    }

    @Test fun `level 7 without prices still works`() {
        many(7).let { list -> assertTrue(list.none { it is NumberExercise.PriceCompare }) }.also { ExerciseGenerator(Random(1)).generate(7, emptyList()) }
        List(50) { ExerciseGenerator(Random(it)).generate(7, emptyList()) }.forEach { assertTrue(it !is NumberExercise.PriceCompare) }
    }

    @Test fun `session has the requested size and only that level`() {
        val s = gen.session(3, prices, 10)
        assertEquals(10, s.size); assertTrue(s.all { it.level == 3 })
    }

    @Test fun `every exercise has a Greek prompt`() {
        (1..7).forEach { level -> assertTrue(gen.generate(level, prices).prompt.isNotBlank()) }
    }
}
```
(The first line of `level 7 without prices still works` is over-complicated; write it simply as: generate 50 level-7 exercises with `emptyList()` and assert none is `PriceCompare`.)

`modules/numbers/NumberProgressionTest.kt`:
```kotlin
package gr.dimitris.app.modules.numbers

import org.junit.Assert.assertEquals
import org.junit.Test

class NumberProgressionTest {
    private fun results(correct: Int, total: Int = 10) = List(total) { it < correct }

    @Test fun `stays until ten results exist`() = assertEquals(3, NumberProgression.next(3, results(5, 5)))
    @Test fun `eight of ten moves up`() = assertEquals(4, NumberProgression.next(3, results(8)))
    @Test fun `fewer than five of ten moves down`() = assertEquals(2, NumberProgression.next(3, results(4)))
    @Test fun `in between stays`() = assertEquals(3, NumberProgression.next(3, results(6)))
    @Test fun `clamped to 1 and 7`() { assertEquals(1, NumberProgression.next(1, results(0))); assertEquals(7, NumberProgression.next(7, results(10))) }
    @Test fun `only the last ten count`() = assertEquals(4, NumberProgression.next(3, List(10) { false } + results(9)))
}
```

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.modules.numbers.*'` — Expected: compilation error.

- [ ] **Step 2: Implement**

`modules/numbers/Exercises.kt`:
```kotlin
package gr.dimitris.app.modules.numbers

import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.GreekNumbers

data class Price(val name: String, val cents: Int)

/** One question. [options] are what he can tap; [answer] is the correct option value. */
sealed class NumberExercise {
    abstract val level: Int
    abstract val prompt: String
    abstract val options: List<Int>
    abstract val answer: Int
    abstract val type: String

    /** Which is more? Two quantities, with dots at low levels. */
    data class Compare(override val level: Int, val a: Int, val b: Int, val showDots: Boolean) : NumberExercise() {
        override val type = "compare"
        override val prompt = "Ποιο είναι περισσότερο;"
        override val options = listOf(a, b)
        override val answer = maxOf(a, b)
    }

    /** Where does the number go on the line? */
    data class NumberLine(override val level: Int, val target: Int, val ticks: List<Int>) : NumberExercise() {
        override val type = "line"
        override val prompt = "Πού πάει το ${GreekNumbers.words(target)};"
        override val options = ticks
        override val answer = target
    }

    /** Tap every object, hear the count, then say how many. */
    data class Count(override val level: Int, val n: Int, override val options: List<Int>) : NumberExercise() {
        override val type = "count"
        override val prompt = "Μέτρα τα. Πόσα είναι;"
        override val answer = n
    }

    /** Digit ↔ word. showWord: the word is shown and he taps the digit; else the digit is shown and he taps the word. */
    data class WordMatch(override val level: Int, val number: Int, override val options: List<Int>, val showWord: Boolean) : NumberExercise() {
        override val type = "word"
        override val prompt = if (showWord) "Ποιος αριθμός είναι;" else "Πώς λέγεται;"
        override val answer = number
    }

    /** Which coin or note is this amount? */
    data class CoinPick(override val level: Int, val targetCents: Int, override val options: List<Int>) : NumberExercise() {
        override val type = "coin"
        override val prompt = "Ποιο είναι το ${Euro.format(targetCents)};"
        override val answer = targetCents
    }

    /** Which of his real things is more expensive? */
    data class PriceCompare(override val level: Int, val a: Price, val b: Price) : NumberExercise() {
        override val type = "price"
        override val prompt = "Ποιο είναι πιο ακριβό;"
        override val options = listOf(a.cents, b.cents)
        override val answer = maxOf(a.cents, b.cents)
    }

    /** It costs X. Which note do you give? (smallest one that is enough) */
    data class Pay(override val level: Int, val priceCents: Int, override val options: List<Int>) : NumberExercise() {
        override val type = "pay"
        override val prompt = "Κοστίζει ${Euro.format(priceCents)}. Με τι πληρώνεις;"
        override val answer = options.filter { it >= priceCents }.min()
    }
}
```

`modules/numbers/ExerciseGenerator.kt`:
```kotlin
package gr.dimitris.app.modules.numbers

import gr.dimitris.app.core.greek.Euro
import kotlin.random.Random

class ExerciseGenerator(private val random: Random = Random.Default) {

    fun session(level: Int, prices: List<Price>, count: Int = 10): List<NumberExercise> = List(count) { generate(level, prices) }

    fun generate(level: Int, prices: List<Price>): NumberExercise = when (level.coerceIn(NumberProgression.MIN_LEVEL, NumberProgression.MAX_LEVEL)) {
        1 -> compare(1, 0..10, showDots = true)
        2 -> NumberExercise.NumberLine(2, random.nextInt(0, 11), (0..10).toList())
        3 -> random.nextInt(1, 11).let { n -> NumberExercise.Count(3, n, optionsAround(n, 1..10)) }
        4 -> compare(4, 0..100, showDots = false)
        5 -> random.nextInt(0, 101).let { n -> NumberExercise.WordMatch(5, n, optionsAround(n, 0..100), showWord = random.nextBoolean()) }
        6 -> if (random.nextBoolean()) compare(6, 0..1000, showDots = false)
             else NumberExercise.NumberLine(6, random.nextInt(0, 11) * 100, (0..1000 step 100).toList())
        else -> euro(prices)
    }

    private fun compare(level: Int, range: IntRange, showDots: Boolean): NumberExercise.Compare {
        val a = random.nextInt(range.first, range.last + 1)
        var b = random.nextInt(range.first, range.last + 1)
        while (b == a) b = random.nextInt(range.first, range.last + 1)
        return NumberExercise.Compare(level, a, b, showDots)
    }

    /** Three distinct options: the answer plus two neighbours within [range]. */
    private fun optionsAround(answer: Int, range: IntRange): List<Int> {
        val pool = (range).filter { it != answer }
        val spread = if (range.last <= 10) 3 else 10
        val near = pool.filter { kotlin.math.abs(it - answer) <= spread }.ifEmpty { pool }
        val picks = near.shuffled(random).take(2).toMutableList()
        while (picks.size < 2) picks += pool.shuffled(random).first { it !in picks }
        return (picks + answer).shuffled(random)
    }

    private fun euro(prices: List<Price>): NumberExercise {
        val kinds = if (prices.size >= 2) listOf("coin", "price", "pay") else listOf("coin", "pay")
        return when (kinds[random.nextInt(kinds.size)]) {
            "coin" -> {
                val target = Euro.denominations[random.nextInt(Euro.denominations.size)]
                val others = Euro.denominations.filter { it != target }.shuffled(random).take(2)
                NumberExercise.CoinPick(7, target, (others + target).shuffled(random))
            }
            "price" -> {
                val two = prices.shuffled(random).let { list -> val a = list[0]; a to list.first { it.cents != a.cents } }
                NumberExercise.PriceCompare(7, two.first, two.second)
            }
            else -> {
                val price = if (prices.isNotEmpty() && random.nextBoolean()) prices[random.nextInt(prices.size)].cents else random.nextInt(1, 40) * 50
                val notes = Euro.denominations.filter { it >= 100 }
                val enough = notes.filter { it >= price }.take(2)
                val tooSmall = notes.filter { it < price }.shuffled(random).take(3 - enough.size)
                val options = (enough + tooSmall).distinct().let { o -> if (o.size < 3) (o + notes.filter { it !in o }.shuffled(random).take(3 - o.size)) else o }
                NumberExercise.Pay(7, price, options.shuffled(random))
            }
        }
    }
}
```
(If `prices` contains only one distinct amount, `PriceCompare` cannot pick two different ones; in `euro`, treat `prices.map { it.cents }.distinct().size >= 2` as the condition for including `"price"`.)

`modules/numbers/NumberProgression.kt`:
```kotlin
package gr.dimitris.app.modules.numbers

/**
 * CHRIS: rewrite me. When does Dimitris move up a level, and when back down?
 * Claude wrote a first version so nothing blocks; NumberProgressionTest describes the contract.
 *
 * Looks at the last WINDOW first-try results at the current level.
 */
object NumberProgression {
    const val MIN_LEVEL = 1
    const val MAX_LEVEL = 7
    const val WINDOW = 10
    const val UP_RATE = 0.8
    const val DOWN_RATE = 0.5

    /** [results]: oldest first, true = correct on the first try. */
    fun next(level: Int, results: List<Boolean>): Int {
        if (results.size < WINDOW) return level
        val recent = results.takeLast(WINDOW)
        val rate = recent.count { it }.toDouble() / WINDOW
        return when {
            rate >= UP_RATE -> (level + 1).coerceAtMost(MAX_LEVEL)
            rate < DOWN_RATE -> (level - 1).coerceAtLeast(MIN_LEVEL)
            else -> level
        }
    }
}
```

- [ ] **Step 3: Run tests, commit**

Run: `./gradlew -q testDebugUnitTest --tests 'gr.dimitris.app.modules.numbers.*'` — Expected: all pass.
```bash
git add app/src/main/java/gr/dimitris/app/modules/numbers app/src/test
git commit -m "feat(phase3): number exercises, generator and adaptive progression

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 3: Prices on items (DB v3) and the caregiver price field

**Files:**
- Modify: `core/data/Entities.kt` (+ `Item.priceCents`), `Daos.kt` (+ `withPrices`), `AppDatabase.kt` (v3, auto-migration 2→3), `core/settings/Settings.kt` (+ `numbersLevel`), `caregiver/content/ItemEditViewModel.kt`, `ItemEditScreen.kt`
- Test: `androidTest/.../core/data/MigrationTest.kt` (+ case), `test/.../core/settings/SettingsTest.kt` (+ case)

**Interfaces:**
- Produces: `Item.priceCents: Int?`, `ItemDao.withPrices(): List<Item>`, `Settings.numbersLevel: Flow<Int>` (default 1) + `setNumbersLevel`, `ItemEditState.priceText`, `ItemEditViewModel.setPriceText`.

- [ ] **Step 1: Failing tests**

Add to `MigrationTest.kt`:
```kotlin
    @Test fun migrate2To3AddsNullablePrice() {
        val name = "migration-test-3.db"
        helper.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO items (id, text, kind, category, firstSound, source, pinned, createdAt, updatedAt, deleted) VALUES ('a', 'καφές', 'WORD', 'FOOD', 'κ', 'SEED', 0, 1, 1, 0)")
        }
        helper.runMigrationsAndValidate(name, 3, true).use { db ->
            db.query("SELECT priceCents FROM items WHERE id = 'a'").use { c -> c.moveToFirst(); assert(c.isNull(0)) }
        }
    }
```
Add to `SettingsTest.kt`:
```kotlin
    @Test fun `numbers level starts at 1 and is clamped to 1..7`() = runBlocking {
        val s = newSettings()
        assertEquals(1, s.numbersLevel.first())
        s.setNumbersLevel(9); assertEquals(7, s.numbersLevel.first())
        s.setNumbersLevel(0); assertEquals(1, s.numbersLevel.first())
    }
```

- [ ] **Step 2: Implement**

`Entities.kt`: add to `Item` after `pinned`:
```kotlin
    /** Real price in cents, for the euro exercises (caregivers copy it from Wolt). */
    val priceCents: Int? = null,
```
`Daos.kt`, `ItemDao`: `@Query("SELECT * FROM items WHERE deleted = 0 AND priceCents IS NOT NULL") suspend fun withPrices(): List<Item>`
`AppDatabase.kt`: `version = 3`, `autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)]`.
`Settings.kt`:
```kotlin
    val numbersLevel: Flow<Int> = store.data.map { it[NUMBERS_LEVEL] ?: 1 }
    suspend fun setNumbersLevel(level: Int) { store.edit { it[NUMBERS_LEVEL] = level.coerceIn(1, 7) } }
```
with `private val NUMBERS_LEVEL = intPreferencesKey("numbers_level")`.

`ItemEditViewModel.kt`: add `val priceText: String = ""` to the state; load `priceText = item.priceCents?.let { Euro.format(it).removeSuffix(" €") } ?: ""`; `fun setPriceText(t: String) = _state.update { it.copy(priceText = t) }`; in `save`, `priceCents = Euro.parse(s.priceText)` in the copy, and if `s.priceText.isNotBlank() && Euro.parse(s.priceText) == null` set `error = "Η τιμή θέλει μορφή 3,50"` and return before saving. Import `gr.dimitris.app.core.greek.Euro`.

`ItemEditScreen.kt`: after the pinned switch row add:
```kotlin
            OutlinedTextField(
                value = s.priceText, onValueChange = vm::setPriceText, singleLine = true,
                label = { Text("Τιμή (€), π.χ. 3,50") }, supportingText = { Text("Για τις ασκήσεις με ευρώ. Άφησέ το κενό αν δεν έχει.") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Sizes.gap))
```
with imports `androidx.compose.foundation.text.KeyboardOptions`, `androidx.compose.ui.text.input.KeyboardType`.

- [ ] **Step 3: Tests, schema, commit**

Run: `./gradlew -q testDebugUnitTest && ANDROID_SERIAL=emulator-5554 ./gradlew -q connectedDebugAndroidTest --tests 'gr.dimitris.app.core.data.*'` — Expected: pass; `app/schemas/.../3.json` exists.
```bash
git add app/schemas app/src/main app/src/test app/src/androidTest
git commit -m "feat(phase3): item prices (db v3), numbers level setting, price field in editor

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 4: Numbers module, ViewModel and screen

**Files:**
- Create: `modules/numbers/NumbersModule.kt`, `NumbersViewModel.kt`, `NumbersScreen.kt`, `ExerciseViews.kt`
- Modify: `AppGraph.kt` (`modules = listOf(WordCoachModule, NumbersModule)`)

**Interfaces:**
- Consumes: `ExerciseGenerator`, `NumberProgression`, `GreekNumbers`, `Euro`, `Settings.numbersLevel`, `ItemDao.withPrices`, `AttemptDao.insert/since`, `TextToSpeech` via `graph.speaker.speakText`, `Feedback`, design components.
- Produces: `NumbersModule` object, `NumbersScreen(items, sessionId, onDone)`, `NumbersViewModel(graph, sessionId)`.

- [ ] **Step 1: Module**

`modules/numbers/NumbersModule.kt`:
```kotlin
package gr.dimitris.app.modules.numbers

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Calculate
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Category
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.modules.Module

object NumbersModule : Module {
    override val id = ModuleId.NUMBERS
    override val titleGreek = "Αριθμοί"
    override val icon: ImageVector = Icons.Rounded.Calculate
    const val EXERCISES_PER_SESSION = 10

    /** Number exercises are generated, not item-based; the returned list only sizes the session. */
    override suspend fun planFor(graph: AppGraph): List<Item> =
        graph.db.items().activeOfKinds(listOf(ItemKind.NUMBER)).take(EXERCISES_PER_SESSION)
            .ifEmpty { List(EXERCISES_PER_SESSION) { Item(text = "Αριθμοί", kind = ItemKind.NUMBER, category = Category.NUMBERS) } }

    @Composable
    override fun Screen(items: List<Item>, sessionId: String?, onDone: () -> Unit) = NumbersScreen(sessionId, onDone)
}
```
In `AppGraph.kt`: `val modules: List<Module> = listOf(WordCoachModule, NumbersModule)` (import).

- [ ] **Step 2: ViewModel**

`modules/numbers/NumbersViewModel.kt`:
```kotlin
package gr.dimitris.app.modules.numbers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import gr.dimitris.app.AppGraph
import gr.dimitris.app.core.data.Attempt
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Outcome
import gr.dimitris.app.core.data.now
import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.GreekNumbers
import gr.dimitris.app.core.scheduler.startOfDay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NumbersState(
    val level: Int = 1,
    val index: Int = 0,
    val total: Int = NumbersModule.EXERCISES_PER_SESSION,
    val exercise: NumberExercise? = null,
    /** Count exercise: how many objects tapped so far. */
    val tapped: Int = 0,
    /** Chosen option, null until he answers. */
    val chosen: Int? = null,
    val correct: Boolean? = null,
    val wrongTries: Int = 0,
    val done: Boolean = false,
    val levelChanged: Int? = null,
)

class NumbersViewModel(private val graph: AppGraph, private val sessionId: String?) : ViewModel() {
    private val _state = MutableStateFlow(NumbersState())
    val state: StateFlow<NumbersState> = _state.asStateFlow()
    private var exercises: List<NumberExercise> = emptyList()
    private var startedAt = now()
    private val results = mutableListOf<Boolean>()
    private val gson = Gson()

    init {
        viewModelScope.launch {
            val level = graph.settings.numbersLevel.first()
            val prices = graph.db.items().withPrices().map { Price(it.text, it.priceCents!!) }
            exercises = ExerciseGenerator().session(level, prices, NumbersModule.EXERCISES_PER_SESSION)
            results += recentResults(level)
            _state.value = NumbersState(level = level, exercise = exercises.first(), total = exercises.size)
            speakPrompt()
        }
    }

    private suspend fun recentResults(level: Int): List<Boolean> =
        runCatching {
            graph.db.attempts().since(startOfDay(now()) - 30L * 24 * 60 * 60 * 1000)
                .filter { it.module == ModuleId.NUMBERS && it.itemId == "numbers:level:$level" }
                .map { it.outcome == Outcome.CORRECT }
        }.getOrDefault(emptyList())

    fun speakPrompt() {
        val e = _state.value.exercise ?: return
        viewModelScope.launch { graph.speaker.speakText(e.prompt) }
    }

    /** Count exercise: one object tapped; says the running count. */
    fun tapObject() {
        val s = _state.value
        val e = s.exercise as? NumberExercise.Count ?: return
        if (s.tapped >= e.n) return
        val n = s.tapped + 1
        _state.update { it.copy(tapped = n) }
        graph.feedback.tap()
        viewModelScope.launch { graph.speaker.speakText(GreekNumbers.words(n)) }
    }

    fun choose(value: Int) {
        val s = _state.value
        val e = s.exercise ?: return
        if (s.correct == true) return
        if (value == e.answer) {
            graph.feedback.success()
            _state.update { it.copy(chosen = value, correct = true) }
            viewModelScope.launch { graph.speaker.speakText(sayAnswer(e) + ". Σωστά!") }
            record(e, firstTry = s.wrongTries == 0, given = value)
        } else {
            graph.feedback.nudge()
            _state.update { it.copy(chosen = value, correct = false, wrongTries = it.wrongTries + 1) }
            viewModelScope.launch { graph.speaker.speakText("Όχι αυτό. Δοκίμασε ξανά.") }
        }
    }

    fun skip() {
        val e = _state.value.exercise ?: return
        graph.feedback.nudge()
        record(e, firstTry = false, given = null, skipped = true)
        next()
    }

    fun next() {
        val i = _state.value.index + 1
        if (i >= exercises.size) { finishSession(); return }
        startedAt = now()
        _state.update { it.copy(index = i, exercise = exercises[i], tapped = 0, chosen = null, correct = null, wrongTries = 0) }
        speakPrompt()
    }

    private fun finishSession() {
        viewModelScope.launch {
            val level = _state.value.level
            val newLevel = NumberProgression.next(level, results)
            if (newLevel != level) graph.settings.setNumbersLevel(newLevel)
            _state.update { it.copy(done = true, levelChanged = newLevel.takeIf { n -> n != level }) }
        }
    }

    private fun record(e: NumberExercise, firstTry: Boolean, given: Int?, skipped: Boolean = false) {
        val outcome = when { skipped -> Outcome.SKIPPED; firstTry -> Outcome.CORRECT; else -> Outcome.ASSISTED }
        if (!skipped) results += firstTry
        val detail = gson.toJson(mapOf("type" to e.type, "exercise" to e, "given" to given, "answer" to e.answer))
        viewModelScope.launch {
            runCatching {
                graph.db.attempts().insert(
                    Attempt(itemId = "numbers:level:${e.level}", module = ModuleId.NUMBERS, sessionId = sessionId, startedAt = startedAt,
                        durationMs = now() - startedAt, outcome = outcome, cueLevel = null, detail = detail)
                )
            }.onFailure { graph.errors.record("numbers record", it) }
        }
    }

    private fun sayAnswer(e: NumberExercise): String = when (e) {
        is NumberExercise.Compare, is NumberExercise.NumberLine, is NumberExercise.Count, is NumberExercise.WordMatch -> GreekNumbers.words(e.answer)
        is NumberExercise.CoinPick, is NumberExercise.Pay -> Euro.format(e.answer)
        is NumberExercise.PriceCompare -> if (e.a.cents >= e.b.cents) e.a.name else e.b.name
    }
}
```

- [ ] **Step 3: Exercise views**

`modules/numbers/ExerciseViews.kt`:
```kotlin
package gr.dimitris.app.modules.numbers

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import gr.dimitris.app.core.greek.Euro
import gr.dimitris.app.core.greek.GreekNumbers
import gr.dimitris.app.ui.theme.LocalFeedback
import gr.dimitris.app.ui.theme.Sizes

/** A big answer button. Green when it is the right answer after answering, muted when it was a wrong tap. */
@Composable
fun OptionButton(label: String, value: Int, chosen: Int?, correct: Boolean?, answer: Int, onClick: (Int) -> Unit, modifier: Modifier = Modifier, big: Boolean = true) {
    val feedback = LocalFeedback.current
    val revealed = correct == true
    val container = when {
        revealed && value == answer -> MaterialTheme.colorScheme.tertiary
        chosen == value && correct == false -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.primary
    }
    val content = if (chosen == value && correct == false) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onPrimary
    Button(
        onClick = { feedback.tap(); onClick(value) },
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content),
        shape = RoundedCornerShape(Sizes.corner),
        modifier = modifier.heightIn(min = if (big) 96.dp else Sizes.touchMin),
    ) {
        Text(label, style = if (big) MaterialTheme.typography.displayLarge else MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DotGrid(n: Int, modifier: Modifier = Modifier) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 5, modifier = modifier) {
        repeat(n) { Box(Modifier.size(22.dp).background(MaterialTheme.colorScheme.secondary, CircleShape)) }
    }
}

@Composable
fun CompareView(e: NumberExercise.Compare, s: NumbersState, onChoose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        listOf(e.a, e.b).forEach { v ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(4.dp)) {
                if (e.showDots) { DotGrid(v, Modifier.heightIn(min = 60.dp)); Spacer(Modifier.height(8.dp)) }
                OptionButton(v.toString(), v, s.chosen, s.correct, e.answer, onChoose, Modifier.fillMaxWidth())
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NumberLineView(e: NumberExercise.NumberLine, s: NumbersState, onChoose: (Int) -> Unit) {
    Text(e.target.toString(), style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Sizes.gap))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), maxItemsInEachRow = 4, modifier = Modifier.fillMaxWidth()) {
        e.ticks.forEach { t -> OptionButton(t.toString(), t, s.chosen, s.correct, e.answer, onChoose, Modifier.weight(1f), big = false) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CountView(e: NumberExercise.Count, s: NumbersState, onTapObject: () -> Unit, onChoose: (Int) -> Unit) {
    val feedback = LocalFeedback.current
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), maxItemsInEachRow = 5, modifier = Modifier.fillMaxWidth()) {
        repeat(e.n) { i ->
            val counted = i < s.tapped
            Button(
                onClick = { if (i == s.tapped) onTapObject() else feedback.nudge() },
                shape = CircleShape,
                colors = ButtonDefaults.buttonColors(containerColor = if (counted) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.secondary),
                modifier = Modifier.size(Sizes.touchMin),
            ) { Text(if (counted) (i + 1).toString() else "", style = MaterialTheme.typography.titleLarge) }
        }
    }
    Spacer(Modifier.height(Sizes.gap))
    if (s.tapped >= e.n) {
        Row(Modifier.fillMaxWidth()) {
            e.options.forEach { v -> OptionButton(v.toString(), v, s.chosen, s.correct, e.answer, onChoose, Modifier.weight(1f).padding(4.dp)) }
        }
    } else {
        Text("Πάτα τα ένα-ένα.", style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun WordMatchView(e: NumberExercise.WordMatch, s: NumbersState, onChoose: (Int) -> Unit) {
    Text(if (e.showWord) GreekNumbers.words(e.number) else e.number.toString(), style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Sizes.gap))
    Column {
        e.options.forEach { v ->
            OptionButton(if (e.showWord) v.toString() else GreekNumbers.words(v), v, s.chosen, s.correct, e.answer, onChoose, Modifier.fillMaxWidth(), big = e.showWord)
            Spacer(Modifier.height(Sizes.gapSmall))
        }
    }
}

@Composable
fun CoinPickView(e: NumberExercise.CoinPick, s: NumbersState, onChoose: (Int) -> Unit) {
    Column {
        e.options.forEach { v -> OptionButton(Euro.format(v), v, s.chosen, s.correct, e.answer, onChoose, Modifier.fillMaxWidth(), big = false); Spacer(Modifier.height(Sizes.gapSmall)) }
    }
}

@Composable
fun PriceCompareView(e: NumberExercise.PriceCompare, s: NumbersState, onChoose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        listOf(e.a, e.b).forEach { p ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(4.dp)) {
                Text(p.name, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                Spacer(Modifier.height(8.dp))
                OptionButton(Euro.format(p.cents), p.cents, s.chosen, s.correct, e.answer, onChoose, Modifier.fillMaxWidth(), big = false)
            }
        }
    }
}

@Composable
fun PayView(e: NumberExercise.Pay, s: NumbersState, onChoose: (Int) -> Unit) {
    Text(Euro.format(e.priceCents), style = MaterialTheme.typography.displayLarge, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(Sizes.gap))
    Column {
        e.options.forEach { v -> OptionButton(Euro.format(v), v, s.chosen, s.correct, e.answer, onChoose, Modifier.fillMaxWidth(), big = false); Spacer(Modifier.height(Sizes.gapSmall)) }
    }
}
```

- [ ] **Step 4: Screen**

`modules/numbers/NumbersScreen.kt`:
```kotlin
package gr.dimitris.app.modules.numbers

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.VolumeUp
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import gr.dimitris.app.LocalAppGraph
import gr.dimitris.app.ui.components.BigButton
import gr.dimitris.app.ui.components.ButtonTone
import gr.dimitris.app.ui.components.DimitrisScreen
import gr.dimitris.app.ui.components.QuietButton
import gr.dimitris.app.ui.components.SuccessMark
import gr.dimitris.app.ui.theme.Sizes

@Composable
fun NumbersScreen(sessionId: String?, onDone: () -> Unit) {
    val graph = LocalAppGraph.current
    val vm: NumbersViewModel = viewModel(key = "numbers-${sessionId ?: "practice"}") { NumbersViewModel(graph, sessionId) }
    val s by vm.state.collectAsStateWithLifecycle()

    if (s.done) {
        DimitrisScreen(bottom = { BigButton("Εντάξει", onClick = onDone) }) {
            SuccessMark(visible = true)
            Spacer(Modifier.height(Sizes.gap))
            Text("Τέλος με τους αριθμούς!", style = MaterialTheme.typography.headlineMedium)
            if (s.levelChanged != null) {
                Spacer(Modifier.height(Sizes.gapSmall))
                Text(if (s.levelChanged!! > s.level) "Ανεβαίνεις στο επίπεδο ${s.levelChanged}. Μπράβο!" else "Πάμε λίγο πιο εύκολα: επίπεδο ${s.levelChanged}.", style = MaterialTheme.typography.bodyLarge)
            }
        }
        return
    }

    val e = s.exercise
    DimitrisScreen(
        title = "Αριθμοί ${s.index + 1}/${s.total}",
        onBack = onDone,
        bottom = {
            if (s.correct == true) BigButton("Επόμενο", onClick = vm::next, tone = ButtonTone.Success)
            else QuietButton("Παράλειψη", onClick = vm::skip)
        },
    ) {
        if (e == null) { Text("Ετοιμάζω...", style = MaterialTheme.typography.headlineMedium); return@DimitrisScreen }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(e.prompt, style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(Sizes.gapSmall))
            QuietButton("Άκου ξανά", onClick = vm::speakPrompt, icon = Icons.Rounded.VolumeUp)
            Spacer(Modifier.height(Sizes.gap))
            when (e) {
                is NumberExercise.Compare -> CompareView(e, s, vm::choose)
                is NumberExercise.NumberLine -> NumberLineView(e, s, vm::choose)
                is NumberExercise.Count -> CountView(e, s, vm::tapObject, vm::choose)
                is NumberExercise.WordMatch -> WordMatchView(e, s, vm::choose)
                is NumberExercise.CoinPick -> CoinPickView(e, s, vm::choose)
                is NumberExercise.PriceCompare -> PriceCompareView(e, s, vm::choose)
                is NumberExercise.Pay -> PayView(e, s, vm::choose)
            }
            Spacer(Modifier.height(Sizes.gap))
            SuccessMark(visible = s.correct == true, modifier = Modifier.fillMaxWidth())
        }
    }
}
```

- [ ] **Step 5: Build, install, play every level**

Run: `./gradlew -q testDebugUnitTest && ANDROID_SERIAL=emulator-5554 ./gradlew -q installDebug connectedDebugAndroidTest`
Expected: green. Today grid shows "Αριθμοί"; free practice runs 10 exercises; wrong taps nudge and keep the question open, the right tap turns green and speaks the number; the end screen mentions a level change after ten first-try results at ≥ 80%. To test level 7, set prices on two items in the caregiver editor and temporarily set the level to 7 via the same editor (caregiver settings get a level control in phase 9's dashboard).

- [ ] **Step 6: Commit**

```bash
git add app/src/main
git commit -m "feat(phase3): number sense module with seven adaptive levels

Co-Authored-By: Claude Fable 5.1 <noreply@anthropic.com>"
```

---

### Task 5: Phase 3 verification

- [ ] Full unit + instrumented suites green.
- [ ] Install on `R5CWC2C1KSJ`; play a full numbers session; add a price to "καφές" (2,50) and "σουβλάκι" (3,80); confirm the Σφάλματα screen stays empty.
- [ ] Append "Phase 3 verified on <date>, <device>" here; commit `docs(phase3): verification notes`.
