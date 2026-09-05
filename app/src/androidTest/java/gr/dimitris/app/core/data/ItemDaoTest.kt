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
}
