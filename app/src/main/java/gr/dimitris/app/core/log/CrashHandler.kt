package gr.dimitris.app.core.log

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import gr.dimitris.app.MainActivity
import gr.dimitris.app.core.data.ErrorLog
import gr.dimitris.app.core.data.ErrorLogDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Dimitris cannot describe a crash, so the app writes it down before dying, then asks Android to
 * bring it back to Today half a second later. The previous handler (Android's) still runs afterwards
 * so the process ends normally.
 */
class CrashHandler(
    private val dao: () -> ErrorLogDao,
    private val previous: Thread.UncaughtExceptionHandler?,
    /** Filled in by [install]; the JVM test leaves it empty because AlarmManager is not there. */
    private val restart: () -> Unit = {},
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, e: Throwable) {
        try {
            runBlocking {
                withTimeout(2_000) {
                    withContext(Dispatchers.IO) { dao().insert(ErrorLog.from("crash:${thread.name}", e)) }
                }
            }
        } catch (_: Throwable) {
            // Nothing more we can do; the crash itself still propagates.
        }
        runCatching { restart() }
        previous?.uncaughtException(thread, e)
    }

    companion object {
        /** A crash loop must not become a restart loop: at most one restart a minute. */
        const val RESTART_GAP_MS = 60_000L
        private const val PREFS = "crash"
        private const val LAST_RESTART = "last_restart"
        private const val DELAY_MS = 500L

        fun install(context: Context, dao: () -> ErrorLogDao) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is CrashHandler) return
            val app = context.applicationContext
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(dao, previous) { scheduleRestart(app) })
        }

        private fun scheduleRestart(context: Context) {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (now - prefs.getLong(LAST_RESTART, 0L) < RESTART_GAP_MS) return
            prefs.edit().putLong(LAST_RESTART, now).commit()   // the process is about to die: write it now
            val intent = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            val pending = PendingIntent.getActivity(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
            )
            context.getSystemService(AlarmManager::class.java).set(AlarmManager.RTC, now + DELAY_MS, pending)
        }
    }
}
