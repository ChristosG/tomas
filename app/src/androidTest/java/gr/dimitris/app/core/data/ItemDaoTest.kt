package gr.dimitris.app.core.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ItemDaoTest {
    private lateinit var db: AppDatabase

    @Before fun open() { db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext()) }
    @After fun close() { db.close() }

    @Test fun upsertThenGet() = runTest {
        val item = Item(text = "καφές", category = Category.FOOD, firstSound = "κ")
        db.items().upsert(item)
        assertEquals(item, db.items().get(item.id))
    }

    @Test fun softDeletedItemsLeaveObserveActive() = runTest {
        val keep = Item(text = "νερό", category = Category.FOOD)
        val drop = Item(text = "ψωμί", category = Category.FOOD)
        db.items().upsertAll(listOf(keep, drop))
        db.items().softDelete(drop.id, now())
        assertEquals(listOf(keep), db.items().observeActive().first())
        assertEquals(1, db.items().countActive())
    }

    @Test fun errorLogRoundTrip() = runTest {
        val log = ErrorLog.from("test", RuntimeException("boom"))
        db.errorLogs().insert(log)
        assertEquals("boom", db.errorLogs().all().single().message)
        assertNull(db.recordings().get("missing"))
    }

    @Test fun observePinnedReturnsOnlyPinnedActiveItems() = runTest {
        val pinned = Item(text = "νερό", category = Category.FOOD, pinned = true)
        val plain = Item(text = "ψωμί", category = Category.FOOD)
        val gone = Item(text = "τυρί", category = Category.FOOD, pinned = true, deleted = true)
        db.items().upsertAll(listOf(pinned, plain, gone))
        assertEquals(listOf(pinned), db.items().observePinned().first())
        assertEquals(setOf(pinned.id, plain.id), db.items().byIds(listOf(pinned.id, plain.id, gone.id)).map { it.id }.toSet())
    }

    /**
     * The seed importers' one query that must *not* filter deletions. A word or a dialogue the
     * caregiver removed has to keep counting as "already on this device", or the next version bump
     * hands it back to her — for ever, and with nothing said anywhere.
     */
    @Test fun deletedRowsAreStillOnTheDevice() = runTest {
        val kept = Item(text = "νερό", category = Category.FOOD)
        val removed = Item(text = "ψωμί", category = Category.FOOD, deleted = true)
        db.items().upsertAll(listOf(kept, removed))
        assertEquals(setOf("νερό", "ψωμί"), db.items().all().map { it.text }.toSet())
        assertEquals(listOf("νερό"), db.items().allActive().map { it.text })

        val live = Script(title = "Στην καφετέρια")
        val deleted = Script(title = "Με έναν φίλο", deleted = true)
        db.scripts().upsertScript(live)
        db.scripts().upsertScript(deleted)
        assertEquals(setOf("Στην καφετέρια", "Με έναν φίλο"), db.scripts().allScripts().map { it.title }.toSet())
        assertEquals(listOf("Στην καφετέρια"), db.scripts().activeScripts().map { it.title })
    }
}
