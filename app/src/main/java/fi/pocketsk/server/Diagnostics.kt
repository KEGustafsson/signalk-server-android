package fi.pocketsk.server

import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The diagnostics bundle the Dock's Logs card shares (docs/PLAN.md §8.1, Logs): the server's log,
 * the app's own settings and the phone's description, as one zip a person can send to whoever
 * helps them. [DiagnosticsBundle] gathers them on the phone; this writes them, and holds the two
 * rules that keep secrets out (CLAUDE.md, Rules of the house):
 *
 * - nothing under the server's configuration directory (`files/home/.signalk`: security.json,
 *   every plugin's settings and credentials) is ever written: [write] refuses such a file;
 * - in the app's settings, a key that names a secret is left out;
 * - everywhere, the log included, a Bluetooth address keeps its last two bytes only, enough to
 *   tell two devices apart and too little to find one.
 *
 * The log itself is the server's output as it was printed: what a plugin chose to print is in it,
 * which the card says before anything is shared.
 *
 * Pure, so the rules are unit-tested.
 */
internal object Diagnostics {

    /** A file of the bundle that is a text made here. */
    data class Text(val name: String, val text: String)

    /** A text file of the bundle copied from the phone, as [name], its Bluetooth addresses masked. */
    data class Copy(val name: String, val file: File)

    /**
     * The app's preferences files as text, one `[file]` heading and `key = value` per setting,
     * keys in order. Secret-looking keys are left out and Bluetooth addresses shortened.
     */
    fun settings(files: Map<String, Map<String, *>>): String = buildString {
        for ((file, values) in files.toSortedMap()) {
            appendLine("[$file]")
            for ((key, value) in values.toSortedMap()) {
                val shown = if (SECRET.containsMatchIn(key)) LEFT_OUT else masked(value)
                appendLine("$key = $shown")
            }
            appendLine()
        }
    }

    /** [text] with every Bluetooth (MAC) address cut to its last two bytes: `XX:XX:XX:XX:1B:2C`. */
    fun maskAddresses(text: String): String = MAC.replace(text) { "XX:XX:XX:XX:${it.groupValues[1]}" }

    /**
     * Writes [texts] and [copies] to [out] as a zip, in that order. A copy that is missing is
     * left out (the log has no `.3` until it has turned over three times); a copy under
     * [secretDir] is refused with [IllegalArgumentException] before anything is written, since a
     * caller that asks for one is wrong and must not get away with it.
     */
    @Throws(IOException::class)
    fun write(out: OutputStream, texts: List<Text>, copies: List<Copy>, secretDir: File, buffer: Int = BUFFER) {
        val secret = secretDir.canonicalFile
        for (c in copies) {
            require(!isUnder(c.file, secret)) { "${c.file} is under ${secretDir.name} and never leaves the phone" }
        }
        ZipOutputStream(out).use { zip ->
            for (t in texts) {
                zip.putNextEntry(ZipEntry(t.name))
                zip.write(t.text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            for (c in copies) {
                if (!c.file.isFile) continue
                zip.putNextEntry(ZipEntry(c.name))
                copyMasked(c.file, zip, buffer)
                zip.closeEntry()
            }
        }
    }

    /**
     * Copies [file] to [out] with its addresses masked, [buffer] characters at a time rather than
     * whole: a log file is about a megabyte, but [LogFiles] takes a line of any length, and one
     * plugin printing a huge one must not make the share run out of memory. Each round writes up
     * to a cut that no address can straddle and keeps the rest for the next round, so an address
     * split between two reads is still found.
     */
    private fun copyMasked(file: File, out: OutputStream, buffer: Int) {
        val w = out.writer(Charsets.UTF_8)
        file.reader(Charsets.UTF_8).use { r ->
            val chars = CharArray(buffer)
            val pending = StringBuilder()
            while (true) {
                val n = r.read(chars)
                if (n < 0) break
                pending.append(chars, 0, n)
                if (pending.length >= buffer + CARRY) pending.delete(0, emitMasked(pending, w, last = false))
            }
            emitMasked(pending, w, last = true)
        }
        // Flushed, not closed: closing would close the zip under it.
        w.flush()
    }

    /**
     * Writes [s] up to a cut, its addresses masked, and returns the cut: all of it when [last],
     * otherwise [CARRY] characters short of the end, or earlier where an address crosses that
     * point, since what follows may finish an address or decide its boundary.
     */
    private fun emitMasked(s: CharSequence, w: java.io.Writer, last: Boolean): Int {
        var cut = if (last) s.length else s.length - CARRY
        var at = 0
        for (m in MAC.findAll(s)) {
            if (m.range.first >= cut) break
            if (m.range.last >= cut) {
                cut = m.range.first
                break
            }
            w.append(s, at, m.range.first).append("XX:XX:XX:XX:").append(m.groupValues[1])
            at = m.range.last + 1
        }
        w.append(s, at, cut)
        return cut
    }

    private fun isUnder(file: File, dir: File): Boolean {
        var f: File? = file.canonicalFile
        while (f != null) {
            if (f == dir) return true
            f = f.parentFile
        }
        return false
    }

    private fun masked(value: Any?): String = when (value) {
        is Set<*> -> value.map { maskAddresses("$it") }.sorted().joinToString(", ", "[", "]")
        else -> maskAddresses("$value")
    }

    private const val LEFT_OUT = "(left out)"

    /** Characters read at a time from a copied file. */
    private const val BUFFER = 64 * 1024

    /** Kept back for the next round: an address is 17 characters, and the one after decides its end. */
    private const val CARRY = 18

    /** A key that names a secret, in any case: token, password, secret, key, auth, cookie, credential. */
    private val SECRET = Regex("(?i)(token|passw|secret|key|auth|cookie|credential)")

    private val MAC = Regex("(?i)\\b(?:[0-9a-f]{2}:){4}([0-9a-f]{2}:[0-9a-f]{2})\\b")
}
