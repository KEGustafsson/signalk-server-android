package fi.pocketsk.server

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.Writer

/**
 * The server's output on disk, `files/logs/server.log` and its predecessors `server.log.1` to
 * `server.log.<keep>`, each at most about [maxBytes] (docs/PLAN.md §6): what survives the app's
 * process, so a crash in the night can still be read in the morning. A file is turned over when
 * the next line would take it past [maxBytes], the oldest dropped, so the whole stays bounded.
 *
 * Every line is flushed: Android ends the process without warning, and the last lines before a
 * crash are the ones worth having. Not thread-safe on its own; [ServerLog] serialises it.
 */
internal class LogFiles(
    private val dir: File,
    private val name: String = "server.log",
    private val maxBytes: Long = MAX_BYTES,
    private val keep: Int = KEEP,
) {
    private var out: Writer? = null
    private var written = 0L

    val current: File get() = File(dir, name)

    /** Appends [line] and a newline, turning the file over first if it is full. */
    @Throws(IOException::class)
    fun append(line: String) {
        val bytes = line.toByteArray(Charsets.UTF_8).size + 1L
        val w = out ?: open()
        val writer = if (written > 0 && written + bytes > maxBytes) rotate() else w
        writer.write(line)
        writer.write("\n")
        writer.flush()
        written += bytes
    }

    /** Closes the file; the next [append] opens it again. */
    fun close() {
        try {
            out?.close()
        } catch (_: IOException) {
            // Flushed after every line already.
        }
        out = null
    }

    private fun open(): Writer {
        dir.mkdirs()
        val f = current
        written = if (f.isFile) f.length() else 0L
        return FileOutputStream(f, true).bufferedWriter().also { out = it }
    }

    private fun rotate(): Writer {
        close()
        File(dir, "$name.$keep").delete()
        for (i in keep - 1 downTo 1) File(dir, "$name.$i").let { if (it.exists()) it.renameTo(File(dir, "$name.${i + 1}")) }
        if (keep > 0) current.renameTo(File(dir, "$name.1")) else current.delete()
        return open()
    }

    companion object {
        const val MAX_BYTES = 1024 * 1024L
        const val KEEP = 3
    }
}
