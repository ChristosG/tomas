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

    @Test fun migrate3To4AddsRecordingStyleDefaultSpoken() {
        val name = "migration-test-4.db"
        helper.createDatabase(name, 3).use { db ->
            db.execSQL(
                "INSERT INTO recordings (id, itemId, path, who, durationMs, recordedAt, createdAt, updatedAt, deleted) " +
                    "VALUES ('r', 'a', '/x.m4a', 'CAREGIVER', 500, 1, 1, 1, 0)"
            )
        }
        helper.runMigrationsAndValidate(name, 4, true).use { db ->
            db.query("SELECT style FROM recordings WHERE id = 'r'").use { c ->
                c.moveToFirst()
                // Every recording made before phase 4 was a spoken one, and it has to stay the model voice.
                assertEquals("style should default to SPOKEN", "SPOKEN", c.getString(0))
            }
        }
    }

    /**
     * Phase 9 only adds an index on `sessions.startedAt` — the column the progress dashboard reads a
     * window of sittings by. Nothing about a session changes, so every row must come through as it
     * was, and the index must actually be there afterwards.
     */
    @Test fun migrate5To6IndexesSessionStartedAtAndKeepsTheSittings() {
        val name = "migration-test-6.db"
        helper.createDatabase(name, 5).use { db ->
            db.execSQL(
                "INSERT INTO sessions (id, startedAt, endedAt, plannedModules, plannedItemCount, completedItemCount, createdAt, updatedAt, deleted) " +
                    "VALUES ('s', 1000, 2000, 'WORDCOACH', 4, 3, 1, 1, 0)"
            )
        }
        helper.runMigrationsAndValidate(name, 6, true).use { db ->
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='sessions' AND name LIKE '%startedAt%'").use { c ->
                assertEquals("sessions.startedAt should be indexed after 5 to 6", 1, c.count)
            }
            db.query("SELECT startedAt, endedAt, completedItemCount FROM sessions WHERE id = 's'").use { c ->
                c.moveToFirst()
                assertEquals(1000, c.getLong(0))
                assertEquals(2000, c.getLong(1))
                assertEquals("a finished sitting must stay finished", 3, c.getInt(2))
            }
        }
    }

    /** Phase 5 only adds tables, so the rows a caregiver already has must come through untouched. */
    @Test fun migrate4To5CreatesScriptTablesAndKeepsTheOldRows() {
        val name = "migration-test-5.db"
        helper.createDatabase(name, 4).use { db ->
            db.execSQL(
                "INSERT INTO items (id, text, kind, category, firstSound, source, pinned, createdAt, updatedAt, deleted) " +
                    "VALUES ('a', 'καφές', 'PHRASE', 'FOOD', 'κ', 'CAREGIVER', 1, 1, 1, 0)"
            )
            db.execSQL(
                "INSERT INTO recordings (id, itemId, path, who, style, durationMs, recordedAt, createdAt, updatedAt, deleted) " +
                    "VALUES ('r', 'a', 'recordings/x.m4a', 'CAREGIVER', 'SPOKEN', 500, 1, 1, 1, 0)"
            )
        }
        helper.runMigrationsAndValidate(name, 5, true).use { db ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('scripts','script_lines')").use { c ->
                assertEquals("scripts and script_lines should both exist after 4 to 5", 2, c.count)
            }
            db.query("SELECT text, pinned FROM items WHERE id = 'a'").use { c ->
                c.moveToFirst()
                assertEquals("καφές", c.getString(0))
                assertEquals("a pinned item should stay pinned", 1, c.getInt(1))
            }
            db.query("SELECT path FROM recordings WHERE id = 'r'").use { c ->
                c.moveToFirst()
                assertEquals("recordings/x.m4a", c.getString(0))
            }
        }
    }
}
