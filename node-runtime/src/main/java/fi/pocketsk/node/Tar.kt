package fi.pocketsk.node

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

/**
 * Unpacks the gzip-compressed tar archives this app ships, npm first of all (`npm pack` output,
 * as published on the registry).
 *
 * Written here rather than taken from a library, because the platform has no tar reader and the
 * format the npm registry serves is small: ustar headers, regular files and directories, with
 * the pax and GNU long-name records some packers emit for paths over 100 bytes. Anything else
 * (links, devices, FIFOs) is refused rather than skipped, so an archive that needs it fails
 * loudly instead of unpacking into something half there. Every path is checked to stay inside
 * the destination, because an entry named `../../shared_prefs/x.xml` would otherwise write over
 * the app's own state.
 */
object Tar {

    /** A malformed or unsupported archive; the message names the entry. */
    class TarException(message: String) : IOException(message)

    /**
     * Unpacks the gzip-compressed tar read from [input] into [dest], dropping the first
     * [stripComponents] path segments of every entry (npm's tarballs put everything under
     * `package/`). Entries that are nothing once stripped are skipped. Returns the number of
     * files written. [dest] is created if missing; the caller decides what to do with a partly
     * written [dest] when this throws.
     */
    fun extractTgz(input: InputStream, dest: File, stripComponents: Int = 0): Int =
        extract(GZIPInputStream(input, BUFFER), dest, stripComponents)

    /** As [extractTgz], for an uncompressed tar stream. */
    fun extract(input: InputStream, dest: File, stripComponents: Int = 0): Int {
        require(stripComponents >= 0) { "stripComponents must not be negative" }
        val root = dest.canonicalFile.apply { mkdirs() }
        val data = DataInputStream(input)
        val header = ByteArray(BLOCK)
        var longName: String? = null
        var files = 0
        while (true) {
            if (!readBlock(data, header)) throw TarException("archive ends without an end-of-archive block")
            if (header.all { it == 0.toByte() }) return files
            verifyChecksum(header)
            val type = header[TYPE_OFFSET].toInt().toChar()
            val size = octal(header, SIZE_OFFSET, SIZE_LENGTH, "size")
            val headerName = nameOf(header)
            when (type) {
                PAX, GNU_LONG_NAME -> {
                    if (size > MAX_METADATA) throw TarException("$headerName: a ${size}-byte name record is not a name")
                    val body = ByteArray(size.toInt()).also { data.readFully(it) }
                    skipPadding(data, size)
                    longName = if (type == PAX) paxPath(body) ?: longName else cString(body, 0, body.size)
                }
                PAX_GLOBAL -> skip(data, size)
                FILE, FILE_OLD, DIRECTORY -> {
                    val name = longName ?: headerName
                    longName = null
                    val target = resolve(root, name, stripComponents)
                    if (type == DIRECTORY) {
                        target?.mkdirs()
                        skip(data, size)
                    } else if (target == null) {
                        skip(data, size)
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out -> copy(data, out, size) }
                        skipPadding(data, size)
                        val mode = octal(header, MODE_OFFSET, MODE_LENGTH, "mode")
                        if ((mode and EXEC_BITS) != 0L) target.setExecutable(true)
                        files++
                    }
                }
                else -> throw TarException("${longName ?: headerName}: entry type '$type' is not supported")
            }
        }
    }

    /**
     * Where [name] goes under [root] once [strip] leading segments are dropped, or null when
     * nothing is left of it. Throws for any name that would land outside [root].
     */
    internal fun resolve(root: File, name: String, strip: Int): File? {
        if (name.startsWith("/") || name.contains('\u0000')) throw TarException("$name: absolute or malformed path")
        val parts = name.split('/').filter { it.isNotEmpty() && it != "." }
        if (parts.any { it == ".." }) throw TarException("$name: path leaves the destination")
        if (parts.size <= strip) return null
        val target = File(root, parts.drop(strip).joinToString("/")).canonicalFile
        if (!target.path.startsWith(root.path + File.separator)) throw TarException("$name: path leaves the destination")
        return target
    }

    /** The ustar name: `prefix/name` when the archive uses the prefix field, as POSIX defines it. */
    private fun nameOf(header: ByteArray): String {
        val name = cString(header, 0, NAME_LENGTH)
        val ustar = String(header, MAGIC_OFFSET, 5, Charsets.US_ASCII) == "ustar"
        val prefix = if (ustar) cString(header, PREFIX_OFFSET, PREFIX_LENGTH) else ""
        return if (prefix.isEmpty()) name else "$prefix/$name"
    }

    /** The `path` record of a pax extended header (`<len> path=<value>\n`), if it has one. */
    internal fun paxPath(body: ByteArray): String? {
        var i = 0
        while (i < body.size) {
            val space = (i until body.size).firstOrNull { body[it] == ' '.code.toByte() } ?: break
            val len = String(body, i, space - i, Charsets.US_ASCII).toIntOrNull() ?: break
            if (len <= 0 || i + len > body.size) break
            val record = String(body, space + 1, i + len - space - 1, Charsets.UTF_8).removeSuffix("\n")
            val eq = record.indexOf('=')
            if (eq > 0 && record.substring(0, eq) == "path") return record.substring(eq + 1)
            i += len
        }
        return null
    }

    /** The header checksum: the byte sum with the checksum field counted as spaces. */
    private fun verifyChecksum(header: ByteArray) {
        val stored = octal(header, CHECKSUM_OFFSET, CHECKSUM_LENGTH, "checksum")
        var sum = 0L
        for (i in header.indices) {
            sum += if (i in CHECKSUM_OFFSET until CHECKSUM_OFFSET + CHECKSUM_LENGTH) ' '.code else header[i].toInt() and 0xff
        }
        if (sum != stored) throw TarException("${nameOf(header)}: header checksum $stored, computed $sum")
    }

    /** An octal number field, NUL- or space-terminated. Base-256 sizes (over 8 GB) are refused. */
    private fun octal(header: ByteArray, offset: Int, length: Int, field: String): Long {
        val text = cString(header, offset, length).trim()
        if (text.isEmpty()) return 0
        return text.toLongOrNull(8) ?: throw TarException("${cString(header, 0, NAME_LENGTH)}: $field '$text' is not octal")
    }

    private fun cString(bytes: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    /** Reads one block; false on a clean end of stream, an exception on a block cut short. */
    private fun readBlock(input: DataInputStream, block: ByteArray): Boolean {
        val first = input.read(block, 0, BLOCK)
        if (first < 0) return false
        try {
            input.readFully(block, first, BLOCK - first)
        } catch (_: EOFException) {
            throw TarException("archive ends inside a header")
        }
        return true
    }

    /** Copies [size] bytes to [out], or reads and drops them when [out] is null. */
    private fun copy(input: DataInputStream, out: OutputStream?, size: Long) {
        val buf = ByteArray(BUFFER)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) throw TarException("archive ends inside a file")
            out?.write(buf, 0, n)
            left -= n
        }
    }

    private fun skip(input: DataInputStream, size: Long) {
        copy(input, null, size)
        skipPadding(input, size)
    }

    private fun skipPadding(input: DataInputStream, size: Long) {
        val pad = ((BLOCK - size % BLOCK) % BLOCK).toInt()
        if (pad > 0) {
            try {
                input.readFully(ByteArray(pad))
            } catch (_: EOFException) {
                throw TarException("archive ends inside a block")
            }
        }
    }

    private const val BLOCK = 512
    private const val BUFFER = 1 shl 16
    /** Larger than any path a pax or GNU name record would hold; anything bigger is not one. */
    private const val MAX_METADATA = 1L shl 16
    private const val EXEC_BITS = 0b001_001_001L
    private const val NAME_LENGTH = 100
    private const val MODE_OFFSET = 100
    private const val MODE_LENGTH = 8
    private const val SIZE_OFFSET = 124
    private const val SIZE_LENGTH = 12
    private const val CHECKSUM_OFFSET = 148
    private const val CHECKSUM_LENGTH = 8
    private const val TYPE_OFFSET = 156
    private const val MAGIC_OFFSET = 257
    private const val PREFIX_OFFSET = 345
    private const val PREFIX_LENGTH = 155
    private const val FILE = '0'
    private const val FILE_OLD = '\u0000'
    private const val DIRECTORY = '5'
    private const val PAX = 'x'
    private const val PAX_GLOBAL = 'g'
    private const val GNU_LONG_NAME = 'L'
}
