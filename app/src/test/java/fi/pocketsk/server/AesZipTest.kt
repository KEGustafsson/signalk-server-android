package fi.pocketsk.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Random
import java.util.Base64

class AesZipTest {
    private val pass = "correct horse battery".toCharArray()

    private fun zip(vararg entries: AesZip.Entry, passphrase: CharArray = pass.copyOf()): ByteArray =
        ByteArrayOutputStream().also { AesZip.write(it, entries.toList(), passphrase, Random(42)) }.toByteArray()

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    @Test
    fun pbkdf2MatchesRfc6070() {
        val p = "password".toByteArray()
        val s = "salt".toByteArray()
        assertEquals("0c60c80f961f0e71f3a9b524af6012062fe037a6", hex(AesZip.derive(p, s, 20, 1)))
        assertEquals("ea6c014dc72d6f8ccd1ed92ace1d41f0d8de8957", hex(AesZip.derive(p, s, 20, 2)))
        assertEquals("4b007901b765489abead49d926f721d065a429c1", hex(AesZip.derive(p, s, 20, 4096)))
        assertEquals(
            "3d2eec4fe41c849b80c8d83662c0e44a8b291a964cf2f07038",
            hex(AesZip.derive("passwordPASSWORDpassword".toByteArray(), "saltSALTsaltSALTsaltSALTsaltSALTsalt".toByteArray(), 25, 4096)),
        )
    }

    @Test
    fun whatIsWrittenReadsBack() {
        val big = ByteArray(200_000) { (it % 251).toByte() }
        val bytes = zip(
            AesZip.Entry("settings.json", "{\"a\":1}".toByteArray(), 1_761_500_000_000),
            AesZip.Entry("plugin-config-data/x.json", big, 1_761_500_000_000),
            AesZip.Entry("empty", ByteArray(0), 1_761_500_000_000),
        )
        val back = AesZip.read(bytes, pass.copyOf())
        assertEquals(listOf("settings.json", "plugin-config-data/x.json", "empty"), back.map { it.name })
        assertArrayEquals("{\"a\":1}".toByteArray(), back[0].data)
        assertArrayEquals(big, back[1].data)
        assertEquals(0, back[2].data.size)
        // MS-DOS time keeps two seconds.
        assertEquals(1_761_500_000_000 / 2000, back[0].modified / 2000)
    }

    @Test
    fun nothingOfThePlainTextShows() {
        val secret = "the-servers-secret-key-0123456789".toByteArray()
        val bytes = zip(AesZip.Entry("secret", secret, 0))
        assertEquals(-1, String(bytes, Charsets.ISO_8859_1).indexOf("the-servers-secret"))
    }

    @Test
    fun theWrongPassphraseIsSaidSo() {
        val bytes = zip(AesZip.Entry("settings.json", "{}".toByteArray(), 0))
        // The two-byte check misses one wrong passphrase in 65536; this one it catches.
        assertThrows(AesZip.WrongPassphrase::class.java) { AesZip.read(bytes, "correct horse batterz".toCharArray()) }
    }

    @Test
    fun anAlteredByteIsCaught() {
        val bytes = zip(AesZip.Entry("settings.json", "{\"interfaces\":{}}".toByteArray(), 0))
        // The local header (30) and name (13) and extra (11), the salt (16) and the check (2): then the cipher text.
        val altered = bytes.copyOf().also { it[30 + 13 + 11 + 16 + 2 + 3] = (it[30 + 13 + 11 + 16 + 2 + 3].toInt() xor 1).toByte() }
        assertThrows(AesZip.NotOurs::class.java) { AesZip.read(altered, pass.copyOf()) }
    }

    @Test
    fun aPlainZipOrNoZipIsRefused() {
        val plain = ByteArrayOutputStream().also { out ->
            java.util.zip.ZipOutputStream(out).use { z ->
                z.putNextEntry(java.util.zip.ZipEntry("settings.json"))
                z.write("{}".toByteArray())
                z.closeEntry()
            }
        }.toByteArray()
        assertThrows(AesZip.NotOurs::class.java) { AesZip.read(plain, pass.copyOf()) }
        assertThrows(AesZip.NotOurs::class.java) { AesZip.read("not a zip at all".toByteArray(), pass.copyOf()) }
        assertThrows(AesZip.NotOurs::class.java) { AesZip.read(ByteArray(0), pass.copyOf()) }
    }

    @Test
    fun aTruncatedZipIsRefused() {
        val bytes = zip(AesZip.Entry("settings.json", "{}".toByteArray(), 0))
        assertThrows(AesZip.NotOurs::class.java) { AesZip.read(bytes.copyOf(bytes.size - 30), pass.copyOf()) }
    }

    @Test
    fun onlyPrintableAsciiEncrypts() {
        assertThrows(java.io.IOException::class.java) { zip(AesZip.Entry("a", ByteArray(1), 0), passphrase = "salasana-äiti-1".toCharArray()) }
    }

    /**
     * A zip written by pyzipper 0.4.0, another implementation of WinZip's AES, with
     * "correct horse battery": what 7-Zip or WinZip writes on a computer reads in the app.
     */
    @Test
    fun aZipFromAnotherImplementationReads() {
        val bytes = Base64.getDecoder().decode(
            "UEsDBBQAAQBjAMydWl0AAAAAQwAAACYAAAANAAsAc2V0dGluZ3MuanNvbgGZBwACAEFFAwgAHtwgeAqstpuvLpe/u3eMuA+gOyzJ/elhlTHB8/QcI8SBM5Z+nk1/Rxes" +
                "/MfEvYQQ9+StKfKI7sxQJ2J7tqC3roUJKlBLAwQUAAEAYwDMnVpdAAAAAC4AAAAQAAAAGwALAHBsdWdpbi1jb25maWctZGF0YS9mb28uanNvbgGZBwACAEFFAwgA" +
                "pqlBqh2nDVxayR0RjKPQ5rH+c+0aRepHbIuUlZBdoz8tk2+4brJQ0HBAFO21WFBLAQIUAxQAAQBjAMydWl0AAAAAQwAAACYAAAANAAsAAAAAAAAAAACAAQAAAABz" +
                "ZXR0aW5ncy5qc29uAZkHAAIAQUUDCABQSwECFAMUAAEAYwDMnVpdAAAAAC4AAAAQAAAAGwALAAAAAAAAAAAAgAF5AAAAcGx1Z2luLWNvbmZpZy1kYXRhL2Zvby5q" +
                "c29uAZkHAAIAQUUDCABQSwUGAAAAAAIAAgCaAAAA6wAAAAAA",
        )
        val entries = AesZip.read(bytes, pass.copyOf())
        assertEquals(listOf("settings.json", "plugin-config-data/foo.json"), entries.map { it.name })
        assertEquals("{\"interfaces\":{},\"pipedProviders\":[]}\n", String(entries[0].data))
        assertEquals("{\"enabled\":true}", String(entries[1].data))
        assertThrows(AesZip.WrongPassphrase::class.java) { AesZip.read(bytes, "correct horse batterz".toCharArray()) }
    }
}
