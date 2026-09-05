package gr.dimitris.app.core.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Item::class, Recording::class, Attempt::class, Schedule::class, Session::class, ErrorLog::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun items(): ItemDao
    abstract fun recordings(): RecordingDao
    abstract fun attempts(): AttemptDao
    abstract fun schedules(): ScheduleDao
    abstract fun sessions(): SessionDao
    abstract fun errorLogs(): ErrorLogDao

    companion object {
        const val NAME = "dimitris.db"

        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME).build()

        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()
    }
}
