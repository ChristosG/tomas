package gr.dimitris.app.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class MigrationTest {
    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), AppDatabase::class.java)

    @Test fun migrate1To2AddsPinnedWithDefaultFalse() {
        val name = "migration-test.db"
        helper.createDatabase(name, 1).use { db ->
            db.execSQL(
                "INSERT INTO items (id, text, kind, category, firstSound, source, createdAt, updatedAt, deleted) " +
                    "VALUES ('a', 'καφές', 'WORD', 'FOOD', 'κ', 'SEED', 1, 1, 0)"
            )
        }
        helper.runMigrationsAndValidate(name, 2, true).use { db ->
            db.query("SELECT pinned FROM items WHERE id = 'a'").use { c ->
                c.moveToFirst()
                assertEquals("pinned should default to 0", 0, c.getInt(0))
            }
        }
    }

    @Test fun migrate2To3AddsNullablePrice() {
        val name = "migration-test-3.db"
        helper.createDatabase(name, 2).use { db ->
            db.execSQL("INSERT INTO items (id, text, kind, category, firstSound, source, pinned, createdAt, updatedAt, deleted) VALUES ('a', 'καφές', 'WORD', 'FOOD', 'κ', 'SEED', 0, 1, 1, 0)")
        }
        helper.runMigrationsAndValidate(name, 3, true).use { db ->
            db.query("SELECT priceCents FROM items WHERE id = 'a'").use { c ->
                c.moveToFirst()
                // assertTrue, not kotlin.assert: ART runs with assertions off, so assert(...) is a no-op there.
                assertTrue("priceCents should be null after 2 to 3", c.isNull(0))
            }
        }
    }
}
