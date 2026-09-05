package gr.dimitris.app.core.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.platform.app.InstrumentationRegistry
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
                assert(c.getInt(0) == 0) { "pinned should default to 0" }
            }
        }
    }
}
