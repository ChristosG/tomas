package gr.dimitris.app.core.log

import android.util.Log
import gr.dimitris.app.core.data.ErrorLog
import gr.dimitris.app.core.data.ErrorLogDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** For handled failures (TTS refused, file missing): log it for caregivers and carry on. */
class ErrorReporter(private val scope: CoroutineScope, private val dao: () -> ErrorLogDao) {
    fun record(where: String, e: Throwable) {
        Log.e("Dimitris", where, e)
        scope.launch(Dispatchers.IO) { runCatching { dao().insert(ErrorLog.from(where, e)) } }
    }
}
