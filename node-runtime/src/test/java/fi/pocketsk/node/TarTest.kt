package fi.pocketsk.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/** Unpacking npm-style tarballs, and refusing the archives that would write outside the destination. */
class TarTest {
    @get:Rule val tmp = TemporaryFolder()

    /** Builds a tar the way packers do: one header per entry, contents padded to 512, two zero blocks. */
    private class TarBuilder {
        private val out = ByteArrayOutputStream()

        fun entry(name: String, body: ByteArray = ByteArray(0), type: Char = '0', mode: Int = 420, prefix: String = "") = apply {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0)
            "%07o\u0000".format(mode).toByteArray().copyInto(h, 100)
            "%07o\u0000".format(0).toByteArray().copyInto(h, 108)
            "%07o\u0000".format(0).toByteArray().copyInto(h, 116)
            "%011o\u0000".format(body.size).toByteArray().copyInto(h, 124)
            "%011o\u0000".format(0).toByteArray().copyInto(h, 136)
            h[156] = type.code.toByte()
            "ustar\u000000".toByteArray().copyInto(h, 257)
            prefix.toByteArray().copyInto(h, 345)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            "%06o\u0000 ".format(sum).toByteArray().copyInto(h, 148)
            out.write(h)
            out.write(body)
            out.write(ByteArray((512 - body.size % 512) % 512))
        }

        fun file(name: String, text: String, mode: Int = 420) = entry(name, text.toByteArray(), mode = mode)

        fun pax(path: String) = apply {
            val record = { len: Int -> "$len path=$path\n" }
            var len = record(0).length
            while (record(len).length != len) len = record(len).length
            entry("PaxHeader", record(len).toByteArray(), type = 'x')
        }

        fun tar(): ByteArray = out.toByteArray() + ByteArray(1024)

        fun tgz(): ByteArray = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(tar()) } }.toByteArray()
    }

    private fun extract(bytes: ByteArray, strip: Int = 0, dest: File = tmp.newFolder("out")) =
        dest to Tar.extract(ByteArrayInputStream(bytes), dest, strip)

    @Test
    fun unpacksFilesWithTheirContentAndExecutableBit() {
        val (dest, count) = extract(
            TarBuilder()
                .file("package/bin/npm-cli.js", "#!/usr/bin/env node\n", mode = 493)
                .file("package/lib/npm.js", "module.exports = {}\n")
                .tar(),
            strip = 1,
        )
        assertEquals(2, count)
        assertEquals("#!/usr/bin/env node\n", File(dest, "bin/npm-cli.js").readText())
        assertTrue(File(dest, "bin/npm-cli.js").canExecute())
        assertFalse(File(dest, "lib/npm.js").canExecute())
    }

    @Test
    fun readsTheGzippedTarballNpmPublishes() {
        val dest = tmp.newFolder("gz")
        val n = Tar.extractTgz(ByteArrayInputStream(TarBuilder().file("package/package.json", "{}").tgz()), dest, 1)
        assertEquals(1, n)
        assertEquals("{}", File(dest, "package.json").readText())
    }

    @Test
    fun anEntryNothingIsLeftOfOnceStrippedIsSkipped() {
        val (dest, count) = extract(TarBuilder().file("package", "stray").file("package/a", "x").tar(), strip = 1)
        assertEquals(1, count)
        assertEquals(listOf("a"), dest.list()!!.toList())
    }

    @Test
    fun theUstarPrefixIsPartOfTheName() {
        val (dest, _) = extract(TarBuilder().entry("leaf.js", "x".toByteArray(), prefix = "package/deep/dir").tar(), strip = 1)
        assertEquals("x", File(dest, "deep/dir/leaf.js").readText())
    }

    @Test
    fun aPaxPathRecordNamesTheNextEntry() {
        val long = "package/" + "d".repeat(120) + "/file.js"
        val (dest, _) = extract(TarBuilder().pax(long).file("truncated-name", "x").tar(), strip = 1)
        assertEquals("x", File(dest, long.removePrefix("package/")).readText())
    }

    @Test
    fun aGnuLongNameRecordNamesTheNextEntry() {
        val long = "package/" + "g".repeat(150)
        val (dest, _) = extract(TarBuilder().entry("././@LongLink", long.toByteArray(), type = 'L').file("cut", "y").tar(), strip = 1)
        assertEquals("y", File(dest, "g".repeat(150)).readText())
    }

    @Test
    fun directoriesAreCreated() {
        val (dest, count) = extract(TarBuilder().entry("package/empty/", type = '5', mode = 493).tar(), strip = 1)
        assertEquals(0, count)
        assertTrue(File(dest, "empty").isDirectory)
    }

    @Test
    fun aPathThatClimbsOutIsRefused() {
        val dest = tmp.newFolder("climb")
        assertThrows(Tar.TarException::class.java) { extract(TarBuilder().file("package/../../evil", "x").tar(), 1, dest) }
        assertFalse(File(dest.parentFile, "evil").exists())
    }

    @Test
    fun anAbsolutePathIsRefused() {
        assertThrows(Tar.TarException::class.java) { extract(TarBuilder().file("/etc/passwd", "x").tar()) }
    }

    @Test
    fun linksAreRefusedNotSkipped() {
        assertThrows(Tar.TarException::class.java) { extract(TarBuilder().entry("package/link", type = '2').tar()) }
        assertThrows(Tar.TarException::class.java) { extract(TarBuilder().entry("package/hard", type = '1').tar(), dest = tmp.newFolder("h")) }
    }

    @Test
    fun aDamagedHeaderIsRefused() {
        val bytes = TarBuilder().file("package/a", "x").tar()
        bytes[10] = 'Z'.code.toByte()   // inside the name, so the checksum no longer adds up
        assertThrows(Tar.TarException::class.java) { extract(bytes) }
    }

    @Test
    fun anArchiveCutShortIsRefused() {
        val whole = TarBuilder().file("package/a", "x".repeat(2000)).tar()
        assertThrows(Tar.TarException::class.java) { extract(whole.copyOf(512 + 1000)) }
        assertThrows(Tar.TarException::class.java) { extract(whole.copyOf(512 + 2048), dest = tmp.newFolder("noend")) }
    }

    @Test
    fun resolveKeepsEveryPathInsideTheRoot() {
        val root = tmp.newFolder("root").canonicalFile
        assertEquals(File(root, "a/b"), Tar.resolve(root, "package/./a//b", 1))
        assertNull(Tar.resolve(root, "package/", 1))
        assertThrows(Tar.TarException::class.java) { Tar.resolve(root, "a/../../b", 0) }
    }

    @Test
    fun unpacksTheNpmTheApkCarries() {
        // The tarball the build fetched and checked (node-runtime/build.gradle.kts hands its path over).
        val tarball = File(System.getProperty("pocketsk.npmTarball") ?: error("run through Gradle: pocketsk.npmTarball is not set"))
        val dest = tmp.newFolder("npm")
        val files = tarball.inputStream().use { Tar.extractTgz(it, dest, stripComponents = 1) }
        assertTrue("only $files files", files > 1000)
        assertTrue(File(dest, "bin/npm-cli.js").isFile)
        assertTrue(File(dest, "package.json").readText().contains("\"name\": \"npm\""))
        assertTrue(File(dest, "node_modules/semver/package.json").isFile)   // the bundled dependencies came along
    }

    @Test
    fun paxPathIsFoundAmongOtherRecords() {
        val body = "20 mtime=1700000000\n17 path=a/b/c.js\n".toByteArray()
        assertEquals("a/b/c.js", Tar.paxPath(body))
        assertNull(Tar.paxPath("20 mtime=1700000000\n".toByteArray()))
    }
}
