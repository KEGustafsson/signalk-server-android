package fi.pocketsk.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions

/** The copies of `.signalk`'s files in the folder a computer sees over USB, and the computer's changes coming back. */
class ConfigMirrorTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var config: File
    private lateinit var shown: File
    private val notes = mutableListOf<String>()

    @Before
    fun setUp() {
        config = tmp.newFolder("home", ".signalk")
        shown = File(tmp.newFolder("Android", "data", "fi.pocketsk", "files"), DataStore.CARD_DIR)
    }

    private fun mirror() = ConfigMirror(config, shown, "what this folder is\n") { notes += it }

    private fun write(f: File, text: String) {
        f.parentFile.mkdirs()
        f.writeText(text)
    }

    @Test
    fun theFilesInSignalkAndThePluginsSettingsAreCopiedOutButNotTheFolders() {
        write(File(config, "settings.json"), "{\"port\":3000}")
        write(File(config, "security.json"), "{\"users\":[]}")
        write(File(config, "settings.json.tmp"), "half a save")
        write(File(config, "plugin-config-data/anchoralarm.json"), "{\"enabled\":true}")
        write(File(config, "node_modules/kip/package.json"), "{}")
        write(File(config, "serverstate/course/settings.json"), "{}")
        val result = mirror().sync(running = true)
        assertEquals(ConfigMirror.Result(emptyList(), emptyList()), result)
        assertEquals("{\"port\":3000}", File(shown, "settings.json").readText())
        assertEquals("{\"users\":[]}", File(shown, "security.json").readText())
        assertEquals("{\"enabled\":true}", File(shown, "plugin-config-data/anchoralarm.json").readText())
        assertFalse(File(shown, "settings.json.tmp").exists())
        assertFalse(File(shown, "node_modules").exists())
        assertFalse(File(shown, "serverstate").exists())
        assertEquals("what this folder is\n", File(shown, ConfigMirror.README).readText())
    }

    @Test
    fun theServersOwnChangesFollowWhileItRuns() {
        write(File(config, "settings.json"), "one")
        mirror().sync(running = true)
        write(File(config, "settings.json"), "two")
        mirror().sync(running = true)
        assertEquals("two", File(shown, "settings.json").readText())
        File(config, "settings.json").delete()
        mirror().sync(running = true)
        assertFalse(File(shown, "settings.json").exists())
    }

    @Test
    fun anEditOnTheComputerWaitsWhileTheServerRunsAndGoesInAtTheNextStart() {
        write(File(config, "settings.json"), "the server's")
        mirror().sync(running = true)
        write(File(shown, "settings.json"), "the owner's")
        // The server saves meanwhile: the owner's copy is not overwritten.
        write(File(config, "settings.json"), "the server's, later")
        assertEquals(ConfigMirror.Result(emptyList(), listOf("settings.json")), mirror().sync(running = true))
        assertEquals("the owner's", File(shown, "settings.json").readText())
        assertEquals(ConfigMirror.Result(listOf("settings.json"), emptyList()), mirror().sync(running = false))
        assertEquals("the owner's", File(config, "settings.json").readText())
        // Taken in, it is the phone's file again, and the server's next save follows out.
        write(File(config, "settings.json"), "saved again")
        mirror().sync(running = true)
        assertEquals("saved again", File(shown, "settings.json").readText())
    }

    @Test
    fun aFileAddedOnTheComputerGoesInReadableByTheAppAlone() {
        mirror().sync(running = false)
        write(File(shown, "ssl-key.pem"), "KEY")
        write(File(shown, "plugin-config-data/signalk-to-nmea0183.json"), "{}")
        write(File(shown, ".DS_Store"), "Finder")
        write(File(shown, "._ssl-key.pem"), "Finder")
        val result = mirror().sync(running = false)
        assertEquals(listOf("plugin-config-data/signalk-to-nmea0183.json", "ssl-key.pem"), result.taken)
        val key = File(config, "ssl-key.pem")
        assertEquals("KEY", key.readText())
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(key.toPath())))
        assertFalse(File(config, ".DS_Store").exists())
        assertFalse(File(config, "._ssl-key.pem").exists())
        assertFalse(File(config, ConfigMirror.README).exists())
    }

    @Test
    fun deletingACopyOnTheComputerDeletesTheFileAtTheNextStart() {
        write(File(config, "security.json"), "{\"users\":[{\"username\":\"admin\"}]}")
        mirror().sync(running = true)
        File(shown, "security.json").delete()
        assertEquals(listOf("security.json"), mirror().sync(running = true).waiting)
        assertTrue(File(config, "security.json").exists())
        assertEquals(listOf("security.json"), mirror().sync(running = false).taken)
        assertFalse(File(config, "security.json").exists())
        assertEquals(ConfigMirror.Result(emptyList(), emptyList()), mirror().sync(running = false))
    }

    @Test
    fun aFolderEmptiedOnTheComputerDeletesNothingAndIsFilledAgain() {
        write(File(config, "settings.json"), "kept")
        write(File(config, "security.json"), "kept too")
        mirror().sync(running = true)
        shown.deleteRecursively()
        assertEquals(ConfigMirror.Result(emptyList(), emptyList()), mirror().sync(running = false))
        assertEquals("kept", File(config, "settings.json").readText())
        assertEquals("kept", File(shown, "settings.json").readText())
        assertEquals("kept too", File(shown, "security.json").readText())
    }

    @Test
    fun aFolderOrLinkOfTheSameNameIsLeftAlone() {
        write(File(config, "charts"), "a file on the phone")
        Files.createDirectories(File(shown, "charts").toPath())
        write(File(shown, "logs"), "a file on the computer")
        Files.createDirectories(File(config, "real-logs").toPath())
        Files.createSymbolicLink(File(config, "logs").toPath(), File(config, "real-logs").toPath())
        assertEquals(ConfigMirror.Result(emptyList(), emptyList()), mirror().sync(running = false))
        assertEquals("a file on the phone", File(config, "charts").readText())
        assertTrue(File(shown, "charts").isDirectory)
        assertTrue(Files.isSymbolicLink(File(config, "logs").toPath()))
        assertEquals("a file on the computer", File(shown, "logs").readText())
    }

    @Test
    fun halfACopyLeftByAKilledProcessIsSweptAway() {
        write(File(config, "settings.json"), "whole")
        write(File(shown, "settings.json.pocketsk-part"), "half")
        write(File(config, "security.json.pocketsk-part"), "half")
        mirror().sync(running = false)
        assertFalse(File(shown, "settings.json.pocketsk-part").exists())
        assertFalse(File(config, "security.json.pocketsk-part").exists())
        assertFalse(File(config, "settings.json.pocketsk-part").exists())
        assertEquals("whole", File(shown, "settings.json").readText())
    }

    @Test
    fun aManifestLineThatLeavesSignalkIsIgnored() {
        val outside = File(tmp.root, "home/files-secret.txt").apply { writeText("kept") }
        val sum = "0".repeat(64)
        write(File(shown, ConfigMirror.MANIFEST), "$sum\t../files-secret.txt\n$sum\t../../home/files-secret.txt\n$sum\tplugin-config-data/../../x\n")
        write(File(shown, "../files-secret.txt"), "the computer's")
        assertEquals(ConfigMirror.Result(emptyList(), emptyList()), mirror().sync(running = false))
        assertEquals("kept", outside.readText())
        assertFalse(File(shown, ConfigMirror.MANIFEST).readText().contains(".."))
    }

    @Test
    fun aFileThatGrewPastTheLimitIsLeftAloneAndNotedOnce() {
        write(File(config, "settings.json"), "small")
        mirror().sync(running = true)
        File(config, "settings.json").writeBytes(ByteArray(16 * 1024 * 1024 + 1))
        mirror().sync(running = true)
        mirror().sync(running = false)
        assertEquals("small", File(shown, "settings.json").readText())
        assertEquals(16L * 1024 * 1024 + 1, File(config, "settings.json").length())
        assertTrue(File(shown, ConfigMirror.MANIFEST).readText().contains("settings.json"))
    }
}
