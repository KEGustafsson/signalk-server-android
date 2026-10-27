package fi.pocketsk.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

class BackupTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun signalk(): File = tmp.newFolder("home", ".signalk").also { dir ->
        File(dir, "settings.json").writeText("{\"interfaces\":{}}")
        File(dir, "security.json").writeText("{\"users\":[]}")
        File(dir, "package.json").writeText("{\"dependencies\":{}}")
        File(dir, "server.log").writeText("a log")
        File(dir, "settings.json.tmp").writeText("half a save")
        File(dir, ".npmrc").writeText("//registry/:_authToken=x")
        File(dir, "node_modules/some-plugin").mkdirs()
        File(dir, "plugin-config-data").mkdirs()
        File(dir, "plugin-config-data/foo.json").writeText("{\"enabled\":true}")
        File(dir, "plugin-config-data/foo").mkdirs()
        File(dir, "plugin-config-data/foo/data.db").writeText("the plugin's data")
        File(dir, "charts").mkdirs()
        File(dir, "charts/chart.mbtiles").writeText("tiles")
    }

    private fun marker() = File(tmp.root, "home/.pocketsk-restored")

    private fun entry(name: String, text: String) = AesZip.Entry(name, text.toByteArray(), 1_761_500_000_000)

    @Test
    fun theSettingsFilesAndNothingElse() {
        val names = Backup.collect(signalk()).map { it.name }
        assertEquals(listOf("package.json", "security.json", "settings.json", "plugin-config-data/foo.json"), names)
    }

    @Test
    fun aLinkIsNotFollowed() {
        val dir = signalk()
        val outside = tmp.newFile("elsewhere.json").also { it.writeText("not ours") }
        Files.createSymbolicLink(File(dir, "linked.json").toPath(), outside.toPath())
        assertFalse(Backup.collect(dir).any { it.name == "linked.json" })
    }

    @Test
    fun anEmptyFolderHasNothingToBackUp() {
        assertThrows(IOException::class.java) { Backup.collect(tmp.newFolder("empty")) }
    }

    @Test
    fun aRestoreWritesTheFilesOwnerOnlyAndLeavesTheRest() {
        val dir = signalk()
        val staging = File(tmp.root, "home/.pocketsk-restore")
        val written = Backup.restore(
            dir,
            staging,
            listOf(entry("settings.json", "{\"restored\":true}"), entry("plugin-config-data/bar.json", "{}"), entry("baseDeltas.json", "[]")),
            marker(),
        )
        assertEquals(listOf("settings.json", "plugin-config-data/bar.json", "baseDeltas.json"), written)
        assertEquals("{\"restored\":true}", File(dir, "settings.json").readText())
        assertEquals("{}", File(dir, "plugin-config-data/bar.json").readText())
        assertEquals("{\"users\":[]}", File(dir, "security.json").readText())
        assertEquals("tiles", File(dir, "charts/chart.mbtiles").readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(File(dir, "settings.json").toPath())))
        assertFalse(staging.exists())
    }

    @Test
    fun aRestoreMarksItsFilesForTheUsbFolderAndABadOneMarksNothing() {
        val dir = signalk()
        Backup.restore(dir, File(tmp.root, "s"), listOf(entry("settings.json", "{}")), marker())
        Backup.restore(dir, File(tmp.root, "s"), listOf(entry("plugin-config-data/foo.json", "{}")), marker())
        assertEquals(setOf("settings.json", "plugin-config-data/foo.json"), Backup.restored(marker()))
        marker().delete()
        assertThrows(IOException::class.java) { Backup.restore(dir, File(tmp.root, "s"), listOf(entry("../x", "{}")), marker()) }
        assertEquals(emptySet<String>(), Backup.restored(marker()))
    }

    @Test
    fun aBadNameWritesNothing() {
        val dir = signalk()
        for (bad in listOf("../escape.json", "plugin-config-data/../../x", "charts/chart.mbtiles", "node_modules/p/index.js", ".npmrc", "a\\b", "", "server.log")) {
            assertThrows(bad, IOException::class.java) {
                Backup.restore(dir, File(tmp.root, "staging"), listOf(entry("settings.json", "{\"changed\":1}"), entry(bad, "x")), marker())
            }
        }
        assertEquals("{\"interfaces\":{}}", File(dir, "settings.json").readText())
        assertFalse(File(tmp.root, "home/escape.json").exists())
    }

    @Test
    fun aFolderOrALinkIsNotRestoredOver() {
        val dir = signalk()
        // A data folder DataStore moved to the card is a link in .signalk.
        Files.createSymbolicLink(File(dir, "serverstate").toPath(), tmp.newFolder("card-serverstate").toPath())
        assertThrows(IOException::class.java) { Backup.restore(dir, File(tmp.root, "staging"), listOf(entry("charts", "x")), marker()) }
        assertThrows(IOException::class.java) { Backup.restore(dir, File(tmp.root, "staging"), listOf(entry("serverstate", "x")), marker()) }
        assertTrue(File(dir, "charts").isDirectory)
        assertTrue(Files.isSymbolicLink(File(dir, "serverstate").toPath()))
    }

    @Test
    fun aNameTwiceOrNoneIsRefused() {
        val dir = signalk()
        assertThrows(IOException::class.java) { Backup.restore(dir, File(tmp.root, "s"), listOf(entry("a.json", "1"), entry("a.json", "2")), marker()) }
        assertThrows(IOException::class.java) { Backup.restore(dir, File(tmp.root, "s"), emptyList(), marker()) }
    }

    @Test
    fun aBackupGoesBackWhole() {
        val from = signalk()
        val bytes = java.io.ByteArrayOutputStream().also { AesZip.write(it, Backup.collect(from), "twelve chars!".toCharArray()) }.toByteArray()
        val to = tmp.newFolder("other", ".signalk")
        Backup.restore(to, File(tmp.root, "other/.pocketsk-restore"), AesZip.read(bytes, "twelve chars!".toCharArray()), marker())
        for (name in listOf("package.json", "security.json", "settings.json", "plugin-config-data/foo.json")) {
            assertEquals(name, File(from, name).readText(), File(to, name).readText())
        }
    }

    @Test
    fun passphrases() {
        assertTrue(Backup.passphraseOk("twelve chars".toCharArray()))
        assertTrue(Backup.passphraseOk(CharArray(64) { '~' }))
        assertFalse(Backup.passphraseOk("eleven char".toCharArray()))
        assertFalse(Backup.passphraseOk(CharArray(65) { 'a' }))
        assertFalse(Backup.passphraseOk("tab\there and more".toCharArray()))
        assertFalse(Backup.passphraseOk("ääkköset ovat kivoja".toCharArray()))
    }

    @Test
    fun theFileName() {
        val name = Backup.fileName(System.currentTimeMillis())
        assertTrue(name, Regex("pocketsk-backup-\\d{4}-\\d{2}-\\d{2}-\\d{4}\\.zip").matches(name))
    }
}
