package gr.dimitris.app.core.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Item::class, Recording::class, Attempt::class, Schedule::class, Session::class, ErrorLog::class,
        Script::class, ScriptLine::class, Advice::class, Note::class],
    version = 8,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3), AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5), AutoMigration(from = 5, to = 6), AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8)],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun recordings(): RecordingDao
    abstract fun attempts(): AttemptDao
    abstract fun schedules(): ScheduleDao
    abstract fun sessions(): SessionDao
    abstract fun errorLogs(): ErrorLogDao
    abstract fun scripts(): ScriptDao

    /** Phase 11: what Claude said, and what the caregivers noticed. Both sync. */
    abstract fun advice(): AdviceDao
    abstract fun notes(): NoteDao

    companion object {
        const val NAME = "dimitris.db"

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()
    }
}
