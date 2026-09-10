package gr.dimitris.app.core.scheduler

import gr.dimitris.app.caregiver.insights.Focus
import gr.dimitris.app.core.data.FakeItemDao
import gr.dimitris.app.core.data.FakeScheduleDao
import gr.dimitris.app.core.data.Item
import gr.dimitris.app.core.data.ItemKind
import gr.dimitris.app.core.data.ModuleId
import gr.dimitris.app.core.data.Schedule
import gr.dimitris.app.core.difficulty.Difficulty
import gr.dimitris.app.core.data.Source
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        schedules.upsert(Schedule(due.id, m, box = 2, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        schedules.upsert(Schedule(later.id, m, box = 3, nextDueAt = noon + 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
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

    /**
     * Chris' own report, in one plan: he adds a word in caregiver mode and then has "to use the app
     * for hours until it randomly appears".
     *
     * Personal items already came before seed ones, but among *them* the oldest went first — so a
     * word written last night queued behind every word he had ever written, and behind a shipped
     * vocabulary of hundreds waiting its turn at eight a day. The word somebody has just sat down
     * and typed is the one that matters today, so it goes in first; the seed list keeps its own
     * curated easiest-first order behind them both.
     */
    @Test fun `the word she added yesterday is introduced before the seed words, newest of hers first`() = runTest {
        // A pile of fresh seed words, every one of them written *after* hers.
        repeat(10) { i -> word("seed$i", Source.SEED, createdAt = noon - 1000 + i) }
        val lastWeek = word("περσινή", Source.CAREGIVER, createdAt = noon - 7 * LeitnerPolicy.DAY_MS)
        val yesterday = word("χθεσινή", Source.CAREGIVER, createdAt = noon - LeitnerPolicy.DAY_MS)

        val oneSlot = SessionBuilder(items, schedules, { noon }, newPerDay = 1, maxItems = 5).plan(m, listOf(ItemKind.WORD))
        assertEquals("the one place goes to the word she wrote last", listOf(yesterday.id), oneSlot.map { it.id })

        val twoSlots = SessionBuilder(items, schedules, { noon }, newPerDay = 2, maxItems = 5).plan(m, listOf(ItemKind.WORD))
        assertEquals(
            "and both of hers still come in ahead of ten fresher seed words",
            setOf(yesterday.id, lastWeek.id), twoSlots.map { it.id }.toSet(),
        )
    }

    /**
     * The one thing «Άκου» being on every screen could have broken, and the only place it shows.
     *
     * A word he asks to hear is ASSISTED, and ASSISTED holds its Leitner box instead of promoting
     * it — right, but it means a man who listens before every word keeps every item at a one-day
     * interval. The due list then saturates, and without reserved places new vocabulary would stop
     * arriving for ever, silently. Twenty overdue words must still leave room for two new ones.
     */
    @Test fun `a saturated due list still lets new words in`() = runTest {
        val big = SessionBuilder(items, schedules, { noon }, newPerDay = 8, maxItems = 12)
        repeat(20) { i ->
            val w = word("due$i", createdAt = i.toLong())
            schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        }
        val newWords = (0 until 5).map { word("new$it", createdAt = 100L + it) }

        val plan = big.plan(m, listOf(ItemKind.WORD))

        assertEquals("the sitting is still full", 12, plan.size)
        val fresh = plan.filter { it.id in newWords.map { n -> n.id } }
        assertEquals("two places are held for words he has never seen: ${fresh.map { it.text }}", 2, fresh.size)
        assertEquals("and they are the oldest new ones, in order", listOf("new0", "new1"), fresh.map { it.text }.sorted())
    }

    /** With room to spare the reservation changes nothing: every new word still comes in. */
    @Test fun `a short due list still fills up with new words`() = runTest {
        val big = SessionBuilder(items, schedules, { noon }, newPerDay = 8, maxItems = 12)
        repeat(3) { i ->
            val w = word("due$i", createdAt = i.toLong())
            schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        }
        repeat(5) { word("new$it", createdAt = 100L + it) }

        val plan = big.plan(m, listOf(ItemKind.WORD))

        assertEquals("three due and all five new, exactly as before", 8, plan.size)
        assertEquals(5, plan.count { it.text.startsWith("new") })
    }

    // ---- the focus (phase 11) ----------------------------------------------------------------

    private fun focus(items: List<String> = emptyList(), sounds: List<String> = emptyList()) =
        Focus(items = items, sounds = sounds, at = noon)

    /**
     * The loop phase 11 closes: Claude reads his whole journey, names a word, and that word is in
     * the next sitting — even though nothing else would have brought it round today. Getting *in*
     * is what a focus does; opening the sitting is not (see the sandwich test below).
     */
    @Test fun `a focused word gets a place it would not otherwise have had`() = runTest {
        repeat(6) { i ->
            val w = word("due$i", createdAt = i.toLong())
            schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 10 + i, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        }
        val wanted = word("καφές", createdAt = 50)
        schedules.upsert(Schedule(wanted.id, m, box = 1, nextDueAt = noon + 9 * LeitnerPolicy.DAY_MS, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))

        val plain = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 5)
            .plan(m, listOf(ItemKind.WORD)).map { it.text }
        val focused = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 5, focus = focus(listOf("Καφές")))
            .plan(m, listOf(ItemKind.WORD)).map { it.text }

        assertFalse("without a focus it is nine days away", plain.contains("καφές"))
        assertTrue("matched unaccented and case-insensitively: $focused", focused.contains("καφές"))
        assertEquals("and the sitting is still full", 5, focused.size)
    }

    /** «Δούλεψε τα «π»» is speech-therapy advice, and it reaches every word that starts with π. */
    @Test fun `a word that starts with a focused sound gets a place too`() = runTest {
        repeat(6) { i ->
            val w = word("due$i", createdAt = i.toLong())
            schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 10 + i, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        }
        val pi = Item(text = "πόρτα", kind = ItemKind.WORD, firstSound = "π", createdAt = 60).also { items.upsert(it) }
        schedules.upsert(Schedule(pi.id, m, box = 1, nextDueAt = noon + 9 * LeitnerPolicy.DAY_MS, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))

        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 5, focus = focus(sounds = listOf("π")))
            .plan(m, listOf(ItemKind.WORD)).map { it.text }

        assertTrue(plan.toString(), plan.contains("πόρτα"))
    }

    /**
     * The reservation is guaranteed, not "whatever room is left" — a reservation a full due list
     * can eat is not a reservation, and this is the only way an advice reaches his morning at all.
     * It comes off the *due* end, exactly as [SessionBuilder.NEW_SLOTS] does, so it costs the
     * most-overdue nothing it would not have lost to the cap anyway.
     */
    @Test fun `the focus is guaranteed its places even when the due list is full`() = runTest {
        repeat(20) { i ->
            val w = word("due$i", createdAt = i.toLong())
            schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 100 + i, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        }
        val wanted = (0 until 3).map { i ->
            word("εστίαση$i", createdAt = 100L + i).also { w ->
                schedules.upsert(Schedule(w.id, m, box = 3, nextDueAt = noon + 9 * LeitnerPolicy.DAY_MS, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
            }
        }
        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 12,
            focus = focus(wanted.map { it.text })).plan(m, listOf(ItemKind.WORD))

        assertEquals("the sitting is still full", 12, plan.size)
        assertEquals(
            "exactly the two reserved places, no more: Claude advises on his morning, it does not plan it",
            SessionBuilder.FOCUS_SLOTS, plan.count { it.text.startsWith("εστίαση") },
        )
        assertEquals("and the rest are still the most overdue words", 10, plan.count { it.text.startsWith("due") })
    }

    /**
     * The order of the sitting is the sandwich's and nobody else's.
     *
     * Easy–hard–easy is how he is handed a session — start on something he has, meet the hard ones
     * in the middle, end on something he has. A word Claude flagged as difficult, or a word typed
     * last night that he has never seen, is precisely the hardest thing in the list; putting it
     * first because it is important is putting it first because it is hard.
     */
    @Test fun `the focus and the new words do not jump the queue - the sandwich orders the sitting`() = runTest {
        val boxes = mutableMapOf<String, Int>()
        (0 until 4).forEach { i ->
            val w = word("due$i", createdAt = i.toLong())
            boxes[w.id] = 5 - i
            schedules.upsert(Schedule(w.id, m, box = 5 - i, nextDueAt = noon - 10 + i, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        }
        val hers = word("χθεσινή", Source.CAREGIVER, createdAt = noon - LeitnerPolicy.DAY_MS)
        val wanted = word("καφές", createdAt = 50)
        schedules.upsert(Schedule(wanted.id, m, box = 1, nextDueAt = noon + 9 * LeitnerPolicy.DAY_MS, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        boxes[wanted.id] = 1
        boxes[hers.id] = 0

        val builder = SessionBuilder(items, schedules, { noon }, newPerDay = 2, maxItems = 6, focus = focus(listOf("καφές")))
        val plan = builder.plan(m, listOf(ItemKind.WORD))

        assertTrue("both got in: ${plan.map { it.text }}", plan.map { it.text }.containsAll(listOf("καφές", "χθεσινή")))
        assertEquals(
            "the order is exactly what the sandwich makes of the same set",
            builder.sandwich(plan) { boxes[it.id] ?: 0 }.map { it.text }, plan.map { it.text },
        )
        // And concretely: the easiest word he has is first, and the two hardest are in the middle.
        assertEquals("due0", plan.first().text)
    }

    /** The focus adds words; it never shortens the list of words that are really due. */
    @Test fun `a short due list keeps every due word when a focus is active`() = runTest {
        val due = (0 until 3).map { i ->
            word("due$i", createdAt = i.toLong()).also { w ->
                schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 10 + i, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
            }
        }
        repeat(3) { i ->
            word("μακρινή$i", createdAt = 100L + i).also { w ->
                schedules.upsert(Schedule(w.id, m, box = 4, nextDueAt = noon + 9 * LeitnerPolicy.DAY_MS, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
            }
        }

        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 5,
            focus = focus(listOf("μακρινή0", "μακρινή1", "μακρινή2"))).plan(m, listOf(ItemKind.WORD))

        assertTrue("every due word is still planned", plan.map { it.id }.containsAll(due.map { it.id }))
        assertEquals("plus the two reserved focus places", 5, plan.size)
    }

    /**
     * A short sitting keeps at least half of itself for what he is due.
     *
     * «Τραγούδα και πες το» plans three items. Two held places for new phrases and one for a
     * focused word took all three, and a module whose whole job is the Leitner schedule planned
     * nothing that was actually due — the reservation eating the thing it was carved out of. Both
     * reservations together now stop at half the sitting, rounded down.
     */
    @Test fun `a three item sitting still plans what is due`() = runTest {
        val due = (0 until 3).map { i ->
            word("due$i", createdAt = i.toLong()).also { w ->
                schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 10 + i, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
            }
        }
        // Two phrases he has never seen, and one the focus named that is nine days away.
        word("καινούργια0", createdAt = 100)
        word("καινούργια1", createdAt = 101)
        val wanted = word("εστίαση", createdAt = 102)
        schedules.upsert(Schedule(wanted.id, m, box = 3, nextDueAt = noon + 9 * LeitnerPolicy.DAY_MS, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))

        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 8, maxItems = 3, focus = focus(listOf("εστίαση")))
            .plan(m, listOf(ItemKind.WORD))

        assertEquals("a three-item sitting is still three items", 3, plan.size)
        assertEquals(
            "the two reservations took the whole sitting and nothing due was planned: ${plan.map { it.text }}",
            2, plan.count { it.id in due.map { d -> d.id } },
        )
    }

    /**
     * The case the guarantee was written for, and the one it used to miss: a focused word that *is*
     * due, but so far down a saturated due list that the cap would have cut it. It was excluded
     * from the candidates for being "already coming" when it was not coming at all.
     */
    @Test fun `a focused word buried in a long due list still gets its place`() = runTest {
        val due = (0 until 20).map { i ->
            word("due$i", createdAt = i.toLong()).also { w ->
                schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 100 + i, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
            }
        }
        // The eighteenth of twenty: due, and a dozen words past the cap.
        val buried = due[17]

        val plain = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 12)
            .plan(m, listOf(ItemKind.WORD))
        val focused = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 12, focus = focus(listOf(buried.text)))
            .plan(m, listOf(ItemKind.WORD))

        assertFalse("without a focus the cap cuts it: ${plain.map { it.text }}", plain.any { it.id == buried.id })
        assertTrue("«δούλεψε τα «π»» did nothing at all: ${focused.map { it.text }}", focused.any { it.id == buried.id })
        assertEquals("the sitting is still full", 12, focused.size)
        assertEquals("and he is asked for it once", 1, focused.count { it.id == buried.id })
    }

    /**
     * A held place that turns out not to be needed costs the sitting nothing.
     *
     * The reservation is counted before the due list is cut, so a focused word standing between the
     * front of that list and the cut is already inside the sitting — and the place held for it was
     * never spent. Without a backfill the due word it displaced was simply lost, and a man with a
     * live advice was handed an eleven-word sitting for having one at all.
     */
    @Test fun `a focus never shortens the sitting`() = runTest {
        val due = (0 until 12).map { i ->
            word("due$i", createdAt = i.toLong()).also { w ->
                schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 100 + i, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
            }
        }
        suspend fun planWith(vararg on: String) =
            SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 12, focus = focus(on.toList()))
                .plan(m, listOf(ItemKind.WORD))

        // The ruled case: twelve due words and a focus naming the fourth of them.
        val early = planWith(due[3].text)
        assertEquals("a focus on a word already in the sitting cost it a place", 12, early.size)
        assertTrue("the focused word is not in it", early.any { it.id == due[3].id })

        // And the case that actually lost places: a word past the front of the list but inside the
        // cut, so a place is held for it and then never used.
        val middle = planWith(due[8].text)
        assertEquals("an unused reservation shortened the sitting", 12, middle.size)
        val both = planWith(due[8].text, due[9].text)
        assertEquals("two unused reservations shortened it twice", 12, both.size)
        assertEquals("and every place is still a different word", 12, both.map { it.id }.distinct().size)
    }

    /** No focus: the sitting is chosen and ordered exactly as it was before phase 11. */
    @Test fun `without a focus nothing about the sitting changes`() = runTest {
        val words = (0 until 5).map { i ->
            word("due$i", createdAt = i.toLong()).also { w ->
                schedules.upsert(Schedule(w.id, m, box = i, nextDueAt = noon - 10 + i, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
            }
        }
        val plan = SessionBuilder(items, schedules, { noon }, newPerDay = 0, maxItems = 5).plan(m, listOf(ItemKind.WORD))

        val boxes = words.associate { it.id to words.indexOf(it) }
        assertEquals(builder.sandwich(plan) { boxes.getValue(it.id) }.map { it.id }, plan.map { it.id })
    }

    @Test fun `sandwich puts easy at both ends and hard in the middle`() {
        val a = Item(text = "a"); val b = Item(text = "b"); val c = Item(text = "c"); val d = Item(text = "d"); val e = Item(text = "e")
        val box = mapOf(a.id to 5, b.id to 4, c.id to 3, d.id to 2, e.id to 0)
        val out = builder.sandwich(listOf(e, d, c, b, a)) { box.getValue(it.id) }
        assertEquals(listOf("a", "c", "e", "d", "b"), out.map { it.text })
    }

    /**
     * The difficulty he set reaches a plan as a narrower pool and nothing else: what is due is still
     * what is due, in the order the sandwich puts it in. This is the whole of spec §13 as far as the
     * scheduler is concerned — the dots never overrule the Leitner boxes, they only decide which words
     * the boxes are allowed to offer.
     */
    @Test fun `a difficulty filter narrows the pool and changes nothing else`() = runTest {
        val short = word("νερό"); val long = word("ψυγείο")
        listOf(short, long).forEach { w ->
            schedules.upsert(Schedule(w.id, m, box = 1, nextDueAt = noon - 1, createdAt = noon - 10 * LeitnerPolicy.DAY_MS))
        }
        val narrowed = SessionBuilder(items, schedules, { noon }, newPerDay = 3, maxItems = 5, filter = { it.text.length <= 4 })
        assertEquals(listOf(short.id), narrowed.plan(m, listOf(ItemKind.WORD)).map { it.id })
        // And with no filter, both of them, so the narrowing is the filter's doing and not the cap's.
        assertEquals(setOf(short.id, long.id), builder.plan(m, listOf(ItemKind.WORD)).map { it.id }.toSet())
    }

    /**
     * The word coach's own mapping, end to end: its tier is a list of [ItemKind], so dot 1 asks for a
     * plan of single words and dot 2 for the words-and-phrases plan it always had.
     */
    @Test fun `the word coach's tier one leaves the phrases out`() = runTest {
        val w = word("νερό")
        val phrase = Item(text = "θέλω νερό", kind = ItemKind.PHRASE, source = Source.SEED, createdAt = 2).also { items.upsert(it) }
        assertEquals(listOf(w.id), builder.plan(m, Difficulty.wordCoachKinds(1)).map { it.id })
        assertEquals(setOf(w.id, phrase.id), builder.plan(m, Difficulty.wordCoachKinds(2)).map { it.id }.toSet())
    }

    @Test fun `startOfDay is midnight local time`() {
        val start = startOfDay(noon)
        assert(start <= noon && noon - start < LeitnerPolicy.DAY_MS)
        assertEquals(start, startOfDay(start))
    }
}
