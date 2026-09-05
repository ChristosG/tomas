package gr.dimitris.app.core.log

import gr.dimitris.app.core.data.ErrorLog
import gr.dimitris.app.core.data.ErrorLogDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Dimitris cannot describe a crash, so the app writes it down before dying. The previous handler
 * (Android's) still runs afterwards so the process ends normally.
 */
class CrashHandler(
    private val dao: () -> ErrorLogDao,
    private val previous: Thread.UncaughtExceptionHandler?,
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, e: Throwable) {
        try {
            runBlocking(Dispatchers.IO) { withTimeout(2_000) { dao().insert(ErrorLog.from("crash:${thread.name}", e)) } }
        } catch (_: Throwable) {
            // Nothing more we can do; the crash itself still propagates.
        }
        previous?.uncaughtException(thread, e)
    }

    companion object {
        fun install(dao: () -> ErrorLogDao) {
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            if (previous is CrashHandler) return
            Thread.setDefaultUncaughtExceptionHandler(CrashHandler(dao, previous))
        }
    }
}
