package gr.dimitris.app.core.log

import gr.dimitris.app.core.data.ErrorLog
import gr.dimitris.app.core.data.ErrorLogDao
import gr.dimitris.app.core.data.RowStamp
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

open class FakeErrorLogDao : ErrorLogDao {
    val rows = mutableListOf<ErrorLog>()
    override suspend fun insert(log: ErrorLog) { rows += log }
    override fun observeRecent(limit: Int): Flow<List<ErrorLog>> = flowOf(rows.take(limit))
    override suspend fun all(): List<ErrorLog> = rows
    override suspend fun clearAll(now: Long) { rows.clear() }

    override suspend fun changedSince(since: Long): List<ErrorLog> =
        rows.filter { it.updatedAt > since }.sortedBy { it.updatedAt }

    override suspend fun stamps(ids: List<String>): List<RowStamp> =
        rows.filter { it.id in ids }.map { RowStamp(it.id, it.updatedAt) }

    /** Append-only, like the real `@Insert(IGNORE)`. */
    override suspend fun upsertFromSync(rows: List<ErrorLog>) {
        val known = this.rows.mapTo(mutableSetOf()) { it.id }
        this.rows += rows.filter { known.add(it.id) }
    }
}

class CrashHandlerTest {
    @Test fun `writes the crash then hands over to the previous handler`() {
        val dao = FakeErrorLogDao()
        var handedOver: Throwable? = null
        val previous = Thread.UncaughtExceptionHandler { _, e -> handedOver = e }
        val boom = IllegalStateException("boom")

        CrashHandler({ dao }, previous).uncaughtException(Thread.currentThread(), boom)

        assertEquals("boom", dao.rows.single().message)
        assertTrue(dao.rows.single().where_.startsWith("crash:"))
        assertSame(boom, handedOver)
    }

    @Test fun `a failing dao never hides the crash from the previous handler`() {
        val failing = object : FakeErrorLogDao() { override suspend fun insert(log: ErrorLog) = throw RuntimeException("db closed") }
        var handedOver = false
        CrashHandler({ failing }, Thread.UncaughtExceptionHandler { _, _ -> handedOver = true })
            .uncaughtException(Thread.currentThread(), RuntimeException("x"))
        assertTrue(handedOver)
    }
}
