package fi.pocketsk.server

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Calendar
import java.util.Random
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A zip whose every entry is encrypted with AES-256 the way WinZip does it (AE-2, "AES Encryption
 * Information: Encryption Specification AE-1 and AE-2"), which is what the Dock's backup is
 * (docs/PLAN.md §16 10.8): 7-Zip and WinZip open it on a computer when given the passphrase, and
 * the app reads it back. Written here with the platform's AES and HMAC rather than taken from a
 * library, since the app takes none beyond AndroidX.
 *
 * Each entry has a salt of its own; PBKDF2-HMAC-SHA1 with 1000 rounds (the format's, not ours to
 * raise) turns it and the passphrase into an AES key, an HMAC key and a two-byte check. The data is
 * deflated, then encrypted in CTR mode with a little-endian counter from 1, and the first ten bytes
 * of HMAC-SHA1 over the ciphertext follow it. AE-2 leaves the CRC at zero, so nothing about the
 * plain text shows. The key stretching is weak by today's measure, which is why [Backup] asks for
 * a long passphrase. PBKDF2 is written out too: Android's providers have turned a passphrase into
 * bytes differently between releases, and ASCII alone ([Backup.passphraseOk]) means the same bytes
 * on every phone and in 7-Zip.
 *
 * Small archives only, read and written whole in memory: no Zip64, at most [MAX_BYTES] in and out.
 * [read] accepts only entries encrypted this way and verifies every one before returning any, so a
 * damaged or altered file is refused whole.
 */
internal object AesZip {

    /** An entry: its path in the zip, its bytes and when it was last changed (ms since the epoch). */
    class Entry(val name: String, val data: ByteArray, val modified: Long)

    /** The passphrase does not open the zip. */
    class WrongPassphrase : IOException("wrong passphrase")

    /** Not a zip this class wrote, or one damaged or altered since. */
    class NotOurs(why: String) : IOException(why)

    /** Writes [entries] to [out], each encrypted under [passphrase] (printable ASCII), the salts from [random]. */
    @Throws(IOException::class)
    fun write(out: OutputStream, entries: List<Entry>, passphrase: CharArray, random: Random = SecureRandom()) {
        if (entries.size > 0xffff) throw IOException("too many files for a zip without Zip64")
        val password = ascii(passphrase)
        val zip = ByteArrayOutputStream()
        val central = ByteArrayOutputStream()
        try {
            for (e in entries) {
                val name = e.name.toByteArray(Charsets.UTF_8)
                val salt = ByteArray(SALT).also(random::nextBytes)
                val keys = derive(password, salt)
                val cipherText = ctr(keys, deflate(e.data))
                val tag = mac(keys, cipherText)
                val size = SALT + VERIFIER + cipherText.size + TAG
                if (zip.size().toLong() + size > MAX_BYTES) throw IOException("backup over ${MAX_BYTES / MB} MB")
                val (time, date) = dos(e.modified)
                val offset = zip.size()
                zip.write(
                    Le().int(LOCAL)
                        .short(VERSION_NEEDED).short(FLAGS).short(METHOD_AES).short(time).short(date)
                        .int(0).int(size).int(e.data.size).short(name.size).short(EXTRA_SIZE)
                        .bytes(),
                )
                zip.write(name)
                zip.write(extra())
                zip.write(salt)
                zip.write(keys, KEY * 2, VERIFIER)
                zip.write(cipherText)
                zip.write(tag)
                central.write(
                    Le().int(CENTRAL)
                        .short(VERSION_MADE_BY).short(VERSION_NEEDED).short(FLAGS).short(METHOD_AES).short(time).short(date)
                        .int(0).int(size).int(e.data.size).short(name.size).short(EXTRA_SIZE).short(0)
                        .short(0).short(0).int(UNIX_FILE shl 16).int(offset)
                        .bytes(),
                )
                central.write(name)
                central.write(extra())
                keys.fill(0)
            }
            val cdOffset = zip.size()
            zip.write(central.toByteArray())
            zip.write(
                Le().int(END)
                    .short(0).short(0).short(entries.size).short(entries.size).int(central.size()).int(cdOffset).short(0)
                    .bytes(),
            )
            out.write(zip.toByteArray())
        } finally {
            password.fill(0)
        }
    }

    /**
     * The entries of [zip], decrypted with [passphrase]; [WrongPassphrase] when it does not open
     * them, [NotOurs] when [zip] is not an AES zip whole and unaltered.
     */
    @Throws(IOException::class)
    fun read(zip: ByteArray, passphrase: CharArray): List<Entry> {
        if (zip.size > MAX_BYTES) throw NotOurs("over ${MAX_BYTES / MB} MB")
        val password = ascii(passphrase)
        try {
            val buf = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN)
            val end = findEnd(zip)
            val count = buf.getShort(end + 10).toInt() and 0xffff
            var at = buf.getInt(end + 16).toLong() and 0xffffffffL
            if (count == 0) throw NotOurs("an empty zip")
            var total = 0L
            val entries = mutableListOf<Entry>()
            repeat(count) {
                if (at + 46 > end || buf.getInt(at.toInt()) != CENTRAL) throw NotOurs("a damaged central directory")
                val c = at.toInt()
                val flags = buf.getShort(c + 8).toInt()
                val method = buf.getShort(c + 10).toInt() and 0xffff
                val time = buf.getShort(c + 12).toInt() and 0xffff
                val date = buf.getShort(c + 14).toInt() and 0xffff
                val size = buf.getInt(c + 20).toLong() and 0xffffffffL
                val plain = buf.getInt(c + 24).toLong() and 0xffffffffL
                val nameLen = buf.getShort(c + 28).toInt() and 0xffff
                val extraLen = buf.getShort(c + 30).toInt() and 0xffff
                val commentLen = buf.getShort(c + 32).toInt() and 0xffff
                val local = buf.getInt(c + 42).toLong() and 0xffffffffL
                if (c + 46L + nameLen + extraLen > end) throw NotOurs("a damaged central directory")
                val name = String(zip, c + 46, nameLen, Charsets.UTF_8)
                if ((flags and 1) == 0 || method != METHOD_AES) throw NotOurs("$name is not encrypted with AES")
                val inner = aesMethod(zip, c + 46 + nameLen, extraLen) ?: throw NotOurs("$name is not AES-256 as WinZip writes it")
                total += plain
                if (total > MAX_BYTES) throw NotOurs("over ${MAX_BYTES / MB} MB unpacked")
                if (local + 30 > zip.size || buf.getInt(local.toInt()) != LOCAL) throw NotOurs("a damaged entry, $name")
                val l = local.toInt()
                val start = l + 30L + (buf.getShort(l + 26).toInt() and 0xffff) + (buf.getShort(l + 28).toInt() and 0xffff)
                if (size < SALT + VERIFIER + TAG || start + size > zip.size) throw NotOurs("a damaged entry, $name")
                val s = start.toInt()
                val keys = derive(password, zip.copyOfRange(s, s + SALT))
                try {
                    val verifier = zip.copyOfRange(s + SALT, s + SALT + VERIFIER)
                    if (!MessageDigest.isEqual(verifier, keys.copyOfRange(KEY * 2, KEY * 2 + VERIFIER))) throw WrongPassphrase()
                    val dataEnd = (s + size - TAG).toInt()
                    val cipherText = zip.copyOfRange(s + SALT + VERIFIER, dataEnd)
                    if (!MessageDigest.isEqual(mac(keys, cipherText), zip.copyOfRange(dataEnd, dataEnd + TAG))) {
                        throw NotOurs("$name was damaged or altered")
                    }
                    val packed = ctr(keys, cipherText)
                    val data = if (inner == STORED) packed else inflate(packed, plain, name)
                    if (data.size.toLong() != plain) throw NotOurs("$name is not the size its header says")
                    entries += Entry(name, data, fromDos(time, date))
                } finally {
                    keys.fill(0)
                }
                at += 46L + nameLen + extraLen + commentLen
            }
            return entries
        } finally {
            password.fill(0)
        }
    }

    /** [p] as bytes, refused unless every character is printable ASCII ([Backup.passphraseOk]). */
    private fun ascii(p: CharArray): ByteArray {
        if (p.isEmpty() || p.any { it !in ' '..'~' }) throw IOException("a passphrase must be printable ASCII")
        return ByteArray(p.size) { p[it].code.toByte() }
    }

    /** PBKDF2-HMAC-SHA1 (RFC 8018), [ROUNDS] rounds: the AES key, the HMAC key, then the check. */
    internal fun derive(password: ByteArray, salt: ByteArray, length: Int = KEY * 2 + VERIFIER, rounds: Int = ROUNDS): ByteArray {
        val prf = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(password, "HmacSHA1")) }
        val out = ByteArray(length)
        var block = 1
        var filled = 0
        while (filled < length) {
            prf.update(salt)
            var u = prf.doFinal(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            val t = u.copyOf()
            repeat(rounds - 1) {
                u = prf.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, length - filled)
            t.copyInto(out, filled, 0, n)
            filled += n
            block++
        }
        return out
    }

    /**
     * AES-256 in WinZip's CTR mode: a little-endian block counter from 1. Its own inverse. The
     * platform's AES/CTR counts big-endian, so it is started afresh at every block, the block's
     * number as its counter: each block is then enciphered exactly as WinZip's counter says. The
     * counter starts at 1 under every entry's own key, which the salt makes new each time, so no
     * key ever meets the same counter twice.
     */
    private fun ctr(keys: ByteArray, data: ByteArray): ByteArray {
        val key = SecretKeySpec(keys, 0, KEY, "AES")
        val aes = Cipher.getInstance("AES/CTR/NoPadding")
        val out = ByteArray(data.size)
        var block = 1L
        var pos = 0
        while (pos < data.size) {
            val counter = ByteBuffer.allocate(BLOCK).order(ByteOrder.LITTLE_ENDIAN).putLong(block).array()
            aes.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(counter))
            val n = minOf(BLOCK, data.size - pos)
            aes.doFinal(data, pos, n, out, pos)
            pos += n
            block++
        }
        return out
    }

    private fun mac(keys: ByteArray, cipherText: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA1").run {
            init(SecretKeySpec(keys, KEY, KEY, "HmacSHA1"))
            doFinal(cipherText).copyOf(TAG)
        }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION, true)
        try {
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(data.size / 2 + 64)
            val buf = ByteArray(BUFFER)
            while (!d.finished()) out.write(buf, 0, d.deflate(buf))
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    private fun inflate(packed: ByteArray, plain: Long, name: String): ByteArray {
        val i = Inflater(true)
        try {
            // A byte past the end, as java.util.zip gives a raw stream: zlib may look one byte ahead.
            i.setInput(packed.copyOf(packed.size + 1))
            val out = ByteArrayOutputStream(minOf(plain, MAX_BYTES).toInt())
            val buf = ByteArray(BUFFER)
            while (!i.finished()) {
                val n = i.inflate(buf)
                if (n == 0 && !i.finished() && (i.needsInput() || i.needsDictionary())) throw NotOurs("$name ends early")
                out.write(buf, 0, n)
                if (out.size() > plain) throw NotOurs("$name is bigger than its header says")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw NotOurs("$name does not unpack: ${e.message}")
        } finally {
            i.end()
        }
    }

    /** The AES extra field's inner compression method, when the entry is AES-256 (AE-1 or AE-2); else null. */
    private fun aesMethod(zip: ByteArray, from: Int, length: Int): Int? {
        val buf = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN)
        var at = from
        while (at + 4 <= from + length) {
            val id = buf.getShort(at).toInt() and 0xffff
            val size = buf.getShort(at + 2).toInt() and 0xffff
            if (id == AES_EXTRA && size >= 7 && at + 4 + size <= from + length) {
                val vendor = buf.getShort(at + 4).toInt()
                val strength = zip[at + 8].toInt()
                val method = buf.getShort(at + 9).toInt() and 0xffff
                val ae = zip[at + 6] == 'A'.code.toByte() && zip[at + 7] == 'E'.code.toByte()
                return if (ae && vendor in 1..2 && strength == STRENGTH_256 && (method == STORED || method == DEFLATED)) method else null
            }
            at += 4 + size
        }
        return null
    }

    /** The end of central directory record, searched for from the end past a comment of at most 64 KB. */
    private fun findEnd(zip: ByteArray): Int {
        val buf = ByteBuffer.wrap(zip).order(ByteOrder.LITTLE_ENDIAN)
        var at = zip.size - 22
        val last = maxOf(0, zip.size - 22 - 0xffff)
        while (at >= last) {
            if (buf.getInt(at) == END) return at
            at--
        }
        throw NotOurs("not a zip")
    }

    /** The AES extra field: AE-2, vendor "AE", AES-256, the data deflated inside. */
    private fun extra(): ByteArray =
        Le().short(AES_EXTRA).short(7).short(2).bytes() +
            byteArrayOf('A'.code.toByte(), 'E'.code.toByte(), STRENGTH_256.toByte()) +
            Le().short(DEFLATED).bytes()

    /** [ms] as MS-DOS time and date, local time, two seconds' resolution, from 1980. */
    private fun dos(ms: Long): Pair<Int, Int> {
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        val year = c.get(Calendar.YEAR)
        if (year < 1980) return 0 to ((1 shl 5) or 1)
        val time = (c.get(Calendar.HOUR_OF_DAY) shl 11) or (c.get(Calendar.MINUTE) shl 5) or (c.get(Calendar.SECOND) / 2)
        val date = ((year - 1980) shl 9) or ((c.get(Calendar.MONTH) + 1) shl 5) or c.get(Calendar.DAY_OF_MONTH)
        return time to date
    }

    private fun fromDos(time: Int, date: Int): Long =
        Calendar.getInstance().apply {
            clear()
            set(1980 + (date ushr 9), ((date ushr 5) and 0xf) - 1, date and 0x1f, time ushr 11, (time ushr 5) and 0x3f, (time and 0x1f) * 2)
        }.timeInMillis

    /** A little-endian record, built field by field. */
    private class Le {
        private val out = ByteArrayOutputStream()

        fun short(v: Int) = apply {
            out.write(v and 0xff)
            out.write((v ushr 8) and 0xff)
        }

        fun int(v: Int) = apply {
            short(v and 0xffff)
            short(v ushr 16)
        }

        fun bytes(): ByteArray = out.toByteArray()
    }

    private const val LOCAL = 0x04034b50
    private const val CENTRAL = 0x02014b50
    private const val END = 0x06054b50
    private const val AES_EXTRA = 0x9901
    private const val METHOD_AES = 99
    private const val STORED = 0
    private const val DEFLATED = 8
    private const val STRENGTH_256 = 3

    /** Version 5.1, the first that knows AES; made on Unix, so the mode below is read. */
    private const val VERSION_NEEDED = 51
    private const val VERSION_MADE_BY = (3 shl 8) or 51

    /** Encrypted, and the name in UTF-8. */
    private const val FLAGS = 1 or (1 shl 11)

    /** A regular file readable and writable by its owner alone. */
    private const val UNIX_FILE = 0x8180

    private const val EXTRA_SIZE = 11
    private const val SALT = 16
    private const val KEY = 32
    private const val VERIFIER = 2
    private const val TAG = 10
    private const val BLOCK = 16
    private const val ROUNDS = 1000
    private const val BUFFER = 64 * 1024
    private const val MB = 1024L * 1024

    /** The most a backup may be, packed or unpacked: settings files, not data. */
    const val MAX_BYTES = 64 * MB
}
