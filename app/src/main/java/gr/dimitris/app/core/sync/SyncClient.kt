package gr.dimitris.app.core.sync

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A failure worth showing a caregiver and worth writing down. Deliberately carries **no cause**:
 * an exception from the connection would drag the request into `error_logs`, where a caregiver can
 * read it and a backup could carry it, and the sync token travels in that request's headers. The
 * message is already Greek and already safe; [status] is the one detail worth keeping, because 401
 * is a mistyped token and a caregiver who is told that fixes it in a minute.
 */
class SyncException(message: String, val status: Int = 0) : Exception(message)

data class PushResult(val accepted: Int, val ignored: Int, val seq: Long)
data class PulledRow(val seq: Long, val table: String, val row: Map<String, Any?>)
data class PullPage(val rows: List<PulledRow>, val seq: Long)

/** What the engine needs from the server. The real one is [HttpSyncClient]; a test hands in a fake. */
interface SyncClient {
    /** The server's current sequence number. The one call that needs no token. */
    suspend fun health(): Long
    suspend fun push(rows: List<SyncRow>): PushResult
    suspend fun pull(since: Long, limit: Int): PullPage
    suspend fun hasMedia(sha: String): Boolean
    suspend fun putMedia(sha: String, file: File)
    suspend fun getMedia(sha: String, dest: File)
}

/**
 * `HttpURLConnection` and Gson — no client library for four routes. Everything runs on
 * [Dispatchers.IO] and gives up after thirty seconds, because a caregiver who tapped
 * «Συγχρόνισε τώρα» on a train must get an answer rather than a spinner.
 *
 * The address and the token are read per call, so changing either in the settings takes effect on
 * the next tap without anything being rebuilt.
 */
class HttpSyncClient(
    private val baseUrl: suspend () -> String,
    private val token: suspend () -> String?,
) : SyncClient {

    override suspend fun health(): Long = withContext(Dispatchers.IO) {
        val body = text(open("/v1/health", "GET", auth = false))
        json(body).get("seq")?.asLong ?: 0L
    }

    override suspend fun push(rows: List<SyncRow>): PushResult = withContext(Dispatchers.IO) {
        val array = JsonArray()
        for (row in rows) {
            val entry = JsonObject()
            entry.addProperty("table", row.table)
            entry.add("row", Rows.toJson(row.row))
            array.add(entry)
        }
        val body = JsonObject().apply { add("rows", array) }.toString().toByteArray(Charsets.UTF_8)
        val connection = open("/v1/push", "POST", auth = true)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setFixedLengthStreamingMode(body.size)
        val answer = json(sendAndRead(connection, body))
        PushResult(
            accepted = answer.get("accepted")?.asInt ?: 0,
            ignored = answer.get("ignored")?.asInt ?: 0,
            seq = answer.get("seq")?.asLong ?: 0L,
        )
    }

    override suspend fun pull(since: Long, limit: Int): PullPage = withContext(Dispatchers.IO) {
        val answer = json(text(open("/v1/pull?since=$since&limit=$limit", "GET", auth = true)))
        val rows = answer.getAsJsonArray("rows")?.map { element ->
            val entry = element.asJsonObject
            PulledRow(
                seq = entry.get("seq")?.asLong ?: 0L,
                table = entry.get("table")?.asString.orEmpty(),
                row = Rows.fromJson(entry.getAsJsonObject("row") ?: JsonObject()),
            )
        }.orEmpty()
        PullPage(rows, answer.get("seq")?.asLong ?: 0L)
    }

    override suspend fun hasMedia(sha: String): Boolean = withContext(Dispatchers.IO) {
        val connection = open("/v1/media/$sha", "HEAD", auth = true)
        val code = status(connection)
        connection.disconnect()
        when (code) {
            HttpURLConnection.HTTP_OK -> true
            HttpURLConnection.HTTP_NOT_FOUND -> false
            else -> throw SyncException(message(code), code)
        }
    }

    override suspend fun putMedia(sha: String, file: File) = withContext(Dispatchers.IO) {
        val connection = open("/v1/media/$sha", "PUT", auth = true)
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/octet-stream")
        connection.setFixedLengthStreamingMode(file.length())
        val code = try {
            connection.outputStream.use { out -> file.inputStream().use { it.copyTo(out) } }
            connection.responseCode
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            // A 401 or a 413 is answered and then hung up on, so the write is what fails first; the
            // status was already flushed, and asking for it is how we learn which one it was.
            runCatching { connection.responseCode }.getOrElse { throw SyncException(OFFLINE) }
        }
        try {
            if (code !in 200..299) throw SyncException(message(code), code)
        } finally {
            connection.disconnect()
        }
    }

    override suspend fun getMedia(sha: String, dest: File) = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        // Written aside and renamed, so an interrupted download never leaves half a photo behind
        // under a name that says the bytes are complete.
        val staged = File(dest.path + ".part")
        val connection = open("/v1/media/$sha", "GET", auth = true)
        try {
            val code = status(connection)
            if (code !in 200..299) throw SyncException(message(code), code)
            connection.inputStream.use { input -> staged.outputStream().use { input.copyTo(it) } }
            // The file is about to be named after this hash, and everything downstream — the row's
            // path, the repair pass, «Άκου» — trusts the name for ever after. A truncated download
            // or a proxy's error page would otherwise become a permanent silent photo.
            if (MediaRefs.sha256(staged) != sha) throw SyncException(BAD_BYTES)
            if (!staged.renameTo(dest)) throw SyncException(NOT_SAVED)
        } finally {
            staged.delete()
            connection.disconnect()
        }
    }

    private suspend fun open(path: String, method: String, auth: Boolean): HttpURLConnection {
        val base = baseUrl().trim().trimEnd('/')
        if (base.isEmpty()) throw SyncException(NOT_CONFIGURED)
        val url = runCatching { URL(base + path) }.getOrElse { throw SyncException(BAD_URL) }
        if (url.protocol !in PROTOCOLS) throw SyncException(BAD_URL)
        val connection = runCatching { url.openConnection() as HttpURLConnection }
            .getOrElse { throw SyncException(BAD_URL) }
        connection.requestMethod = method
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.useCaches = false
        connection.setRequestProperty("Accept", "application/json")
        if (auth) {
            val secret = token()?.takeIf { it.isNotBlank() } ?: throw SyncException(NO_TOKEN)
            connection.setRequestProperty("Authorization", "Bearer $secret")
        }
        return connection
    }

    /** The response code, with every connection failure turned into one Greek sentence. */
    private fun status(connection: HttpURLConnection): Int = try {
        connection.responseCode
    } catch (ce: CancellationException) {
        throw ce
    } catch (_: Throwable) {
        throw SyncException(OFFLINE)
    }

    private fun sendAndRead(connection: HttpURLConnection, body: ByteArray): String {
        val code = try {
            connection.outputStream.use { it.write(body) }
            connection.responseCode
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: IOException) {
            runCatching { connection.responseCode }.getOrElse { throw SyncException(OFFLINE) }
        }
        try {
            if (code !in 200..299) throw SyncException(message(code), code)
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: SyncException) {
            throw e
        } catch (_: Throwable) {
            throw SyncException(OFFLINE)
        } finally {
            connection.disconnect()
        }
    }

    private fun text(connection: HttpURLConnection): String {
        try {
            val code = status(connection)
            if (code !in 200..299) throw SyncException(message(code), code)
            return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: SyncException) {
            throw e
        } catch (_: Throwable) {
            throw SyncException(OFFLINE)
        } finally {
            connection.disconnect()
        }
    }

    private fun json(body: String): JsonObject = runCatching { JsonParser.parseString(body).asJsonObject }
        .getOrElse { throw SyncException(NOT_A_SERVER) }

    companion object {
        /** Thirty seconds each for connecting and for reading, as the phase's rulings ask. */
        const val TIMEOUT_MS = 30_000

        private val PROTOCOLS = setOf("http", "https")

        const val NOT_CONFIGURED = "Δεν έχει οριστεί διεύθυνση διακομιστή."
        const val NO_TOKEN = "Δεν έχει οριστεί κλειδί διακομιστή."
        const val BAD_URL = "Η διεύθυνση του διακομιστή δεν είναι σωστή."
        const val OFFLINE = "Δεν βρήκα τον διακομιστή. Έλεγξε τη σύνδεση και τη διεύθυνση."
        const val NOT_A_SERVER = "Ο διακομιστής απάντησε κάτι που δεν κατάλαβα."
        const val NOT_SAVED = "Δεν μπόρεσα να αποθηκεύσω το αρχείο."
        const val BAD_BYTES = "Το αρχείο ήρθε χαλασμένο. Θα ξαναδοκιμάσω."
        const val BAD_TOKEN = "Το κλειδί δεν έγινε δεκτό. Έλεγξε το κλειδί στις ρυθμίσεις."
        const val NOT_FOUND = "Ο διακομιστής δεν έχει αυτή τη διεύθυνση. Έλεγξε τη διεύθυνση."
        const val TOO_BIG = "Το πακέτο ήταν πολύ μεγάλο για τον διακομιστή."
        const val REFUSED = "Ο διακομιστής δεν δέχτηκε τα δεδομένα."

        /** One Greek sentence per status. Never the token, never the body the server sent back. */
        fun message(status: Int): String = when (status) {
            HttpURLConnection.HTTP_UNAUTHORIZED, HttpURLConnection.HTTP_FORBIDDEN -> BAD_TOKEN
            HttpURLConnection.HTTP_NOT_FOUND -> NOT_FOUND
            HttpURLConnection.HTTP_BAD_REQUEST -> REFUSED
            HttpURLConnection.HTTP_ENTITY_TOO_LARGE -> TOO_BIG
            else -> "Ο διακομιστής απάντησε με σφάλμα $status."
        }
    }
}
