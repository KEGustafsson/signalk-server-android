package fi.pocketsk.server

import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything the server prints, and the supervisor's own lines among it (marked `[pocketsk]`),
 * each stamped with the time it arrived: in memory ([LogRing]) for the screens, and on disk
 * ([LogFiles], `files/logs/server.log`) for after the process. One per process ([ServerStatus]),
 * since the screens read it while no service exists, after the server gave up.
 *
 * The app writes no secret here: the admin token is never printed by the app, and `files/logs`
 * is kept out of backups and device transfer like the rest (data_extraction_rules.xml). What a
 * plugin chooses to print is its own business, as on any Signal K server.
 */
internal class ServerLog(dir: File) {
    private val ring = LogRing()
    private val files = LogFiles(dir)
    private val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.ROOT)

    /** True once a write to disk has failed (a full disk); said once in the ring, then not again. */
    private var filesFailed = false

    /** Told after every line, on the thread that added it. */
    @Volatile var listener: (() -> Unit)? = null

    /** A line of the server's own output. */
    fun line(text: String) = add(text)

    /** A line from the supervisor. */
    fun note(text: String) = add("[pocketsk] $text")

    /** The last [count] lines, oldest first, one string. */
    fun tail(count: Int): String = ring.tail(count).joinToString("\n")

    /** The last [count] lines, oldest first, for a filter to choose from ([LogFilter]). */
    fun lines(count: Int): List<String> = ring.tail(count)

    private fun add(text: String) {
        synchronized(this) {
            val line = "${stamp.format(Date())}  $text"
            ring.add(line)
            if (!filesFailed) {
                try {
                    files.append(line)
                } catch (e: IOException) {
                    filesFailed = true
                    files.close()
                    ring.add("${stamp.format(Date())}  [pocketsk] the log file cannot be written any more: $e")
                }
            }
        }
        listener?.invoke()
    }
}
