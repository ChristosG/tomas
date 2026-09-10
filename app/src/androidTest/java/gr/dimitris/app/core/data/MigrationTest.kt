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

    /**
     * Phase 11 adds two tables and nothing else: `advice`, which is how the advisor remembers what
     * it said, and `notes`, which is what the people around him noticed. His whole history has to
     * come through untouched — this migration runs on a phone with years of attempts on it.
     */
    @Test fun migrate6To7CreatesAdviceAndNotesAndKeepsHisHistory() {
        val name = "migration-test-7.db"
        helper.createDatabase(name, 6).use { db ->
            db.execSQL(
                "INSERT INTO items (id, text, kind, category, firstSound, source, pinned, createdAt, updatedAt, deleted) " +
                    "VALUES ('a', 'καφές', 'WORD', 'FOOD', 'κ', 'CAREGIVER', 1, 1, 1, 0)"
            )
            db.execSQL(
                "INSERT INTO attempts (id, itemId, module, startedAt, durationMs, outcome, cueLevel, detail, createdAt, updatedAt, deleted) " +
                    "VALUES ('at1', 'a', 'WORDCOACH', 1000, 900, 'ASSISTED', 2, '{}', 1, 1, 0)"
            )
        }
        helper.runMigrationsAndValidate(name, 7, true).use { db ->
            db.query("SELECT name FROM sqlite_master WHERE type='table' AND name IN ('advice','notes')").use { c ->
                assertEquals("advice and notes should both exist after 6 to 7", 2, c.count)
            }
            // Both are pulled by "rows changed since X", so both must be indexed on updatedAt.
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='advice' AND name LIKE '%updatedAt%'").use { c ->
                assertEquals("advice.updatedAt should be indexed", 1, c.count)
            }
            db.query("SELECT name FROM sqlite_master WHERE type='index' AND tbl_name='notes' AND name LIKE '%updatedAt%'").use { c ->
                assertEquals("notes.updatedAt should be indexed", 1, c.count)
            }
            db.query("SELECT text, pinned FROM items WHERE id = 'a'").use { c ->
                c.moveToFirst()
                assertEquals("καφές", c.getString(0))
                assertEquals("a pinned item should stay pinned", 1, c.getInt(1))
            }
            db.query("SELECT outcome, cueLevel FROM attempts WHERE id = 'at1'").use { c ->
                c.moveToFirst()
                assertEquals("ASSISTED", c.getString(0))
                assertEquals("an attempt is a fact; it must come through as it was", 2, c.getInt(1))
            }
            // Writable straight away, which is the only thing that makes the new tables real.
            db.execSQL(
                "INSERT INTO notes (id, at, text, author, createdAt, updatedAt, deleted) " +
                    "VALUES ('n1', 5, 'Είπε «καλημέρα» μόνος του.', 'CAREGIVER', 5, 5, 0)"
            )
            db.query("SELECT text FROM notes WHERE id = 'n1'").use { c ->
                c.moveToFirst()
                assertEquals("Είπε «καλημέρα» μόνος του.", c.getString(0))
            }
        }
    }

    /**
     * Phase 12 adds two columns to `script_lines` and nothing else: `tier`, which is how hard a
     * dialogue is against his own dot row, and `intent`, what a good answer to one of his turns has
     * to convey.
     *
     * Every dialogue a caregiver has written predates both, so what this really tests is that her
     * work survives the upgrade and lands where it belongs: tier 1, the easiest, which is in reach
     * from every dot; and no intent, which is a turn nobody has said anything about yet — not a turn
     * with an empty demand on it.
     */
    @Test fun migrate7To8AddsTierAndIntentAndKeepsHerDialogue() {
        val name = "migration-test-8.db"
        helper.createDatabase(name, 7).use { db ->
            db.execSQL(
                "INSERT INTO scripts (id, title, source, createdAt, updatedAt, deleted) " +
                    "VALUES ('s1', 'Στην καφετέρια', 'CAREGIVER', 1, 1, 0)"
            )
            db.execSQL(
                "INSERT INTO items (id, text, kind, category, firstSound, source, pinned, createdAt, updatedAt, deleted) " +
                    "VALUES ('i1', 'Έναν καφέ, παρακαλώ.', 'SCRIPT_LINE', 'CUSTOM', 'Έ', 'CAREGIVER', 0, 1, 1, 0)"
            )
            // Every NOT NULL column v7 had, which is the whole of a line before this phase.
            db.execSQL(
                "INSERT INTO script_lines (id, scriptId, position, speaker, itemId, createdAt, updatedAt, deleted) " +
                    "VALUES ('l1', 's1', 1, 'DIMITRIS', 'i1', 1, 1, 0)"
            )
        }
        helper.runMigrationsAndValidate(name, 8, true).use { db ->
            db.query("SELECT scriptId, position, speaker, itemId, tier, intent FROM script_lines WHERE id = 'l1'").use { c ->
                c.moveToFirst()
                assertEquals("s1", c.getString(0))
                assertEquals("her turn keeps its place", 1, c.getInt(1))
                assertEquals("DIMITRIS", c.getString(2))
                assertEquals("i1", c.getString(3))
                assertEquals("a line written before tiers is the easiest tier", 1, c.getInt(4))
                assertTrue("nobody has said what this turn is after", c.isNull(5))
            }
            // Writable straight away with both new columns, which is what makes them real.
            db.execSQL(
                "INSERT INTO script_lines (id, scriptId, position, speaker, itemId, tier, intent, createdAt, updatedAt, deleted) " +
                    "VALUES ('l2', 's1', 2, 'DIMITRIS', 'i1', 4, 'λέει τι θέλει και πόσο', 2, 2, 0)"
            )
            db.query("SELECT tier, intent FROM script_lines WHERE id = 'l2'").use { c ->
                c.moveToFirst()
                assertEquals(4, c.getInt(0))
                assertEquals("λέει τι θέλει και πόσο", c.getString(1))
            }
            db.query("SELECT title FROM scripts WHERE id = 's1'").use { c ->
                c.moveToFirst()
                assertEquals("Στην καφετέρια", c.getString(0))
            }
        }
    }

    /**
     * Phase 13 adds two columns to `items` and nothing else: `tier`, how hard the word is against
     * his own dot row, and `gender`, what the sentence builder needs to put an article in front of a
     * noun.
     *
     * Every word on every phone predates both — two hundred bundled ones and whatever the family has
     * typed since — so what this really tests is that they all survive the upgrade and land where
     * they belong: tier 1, the easiest, which is in reach from every dot; and no gender, which is a
     * noun nobody has graded and not a noun asserted to have none. Her photo, her price and her pin
     * come through as they were.
     */
    @Test fun migrate8To9AddsTierAndGenderAndKeepsEveryWord() {
        val name = "migration-test-9.db"
        helper.createDatabase(name, 8).use { db ->
            // Every NOT NULL column v8 had, plus the nullable ones a caregiver fills in: this is the
            // whole of a word before this phase.
            db.execSQL(
                "INSERT INTO items (id, text, kind, category, imagePath, modelRecordingId, firstSound, firstSyllable, " +
                    "firstSyllableOverride, source, pinned, priceCents, createdAt, updatedAt, deleted) " +
                    "VALUES ('a', 'ψωμί', 'WORD', 'FOOD', 'photos/p.jpg', 'r1', 'ψ', 'ψω', NULL, 'CAREGIVER', 1, 120, 1, 1, 0)"
            )
        }
        helper.runMigrationsAndValidate(name, 9, true).use { db ->
            db.query("SELECT text, kind, category, imagePath, priceCents, pinned, tier, gender FROM items WHERE id = 'a'").use { c ->
                c.moveToFirst()
                assertEquals("ψωμί", c.getString(0))
                assertEquals("WORD", c.getString(1))
                assertEquals("FOOD", c.getString(2))
                assertEquals("her photograph stays on the word", "photos/p.jpg", c.getString(3))
                assertEquals("her price stays on the word", 120, c.getInt(4))
                assertEquals("a pinned word stays pinned", 1, c.getInt(5))
                assertEquals("a word written before tiers is the easiest tier", 1, c.getInt(6))
                assertTrue("nobody has said what gender this noun is", c.isNull(7))
            }
            // Writable straight away with both new columns, which is what makes them real.
            db.execSQL(
                "INSERT INTO items (id, text, kind, category, firstSound, source, pinned, tier, gender, createdAt, updatedAt, deleted) " +
                    "VALUES ('b', 'ελπίδα', 'WORD', 'FEELINGS', 'ε', 'SEED', 0, 5, 'F', 2, 2, 0)"
            )
            db.query("SELECT tier, gender FROM items WHERE id = 'b'").use { c ->
                c.moveToFirst()
                assertEquals(5, c.getInt(0))
                assertEquals("F", c.getString(1))
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
