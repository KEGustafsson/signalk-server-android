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

/** The server's settings reset: what goes, what stays, and that the USB folder gives nothing back. */
class ServerResetTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var config: File
    private lateinit var shown: File

    @Before
    fun setUp() {
        config = tmp.newFolder("home", ".signalk")
        shown = File(tmp.newFolder("Android", "data", "fi.pocketsk", "files"), DataStore.CARD_DIR)
    }

    private val marker get() = File(config.parentFile, ".pocketsk-restored")

    private fun write(f: File, text: String = "x") {
        f.parentFile.mkdirs()
        f.writeText(text)
    }

    @Test
    fun theSettingsPluginsAndTheirSettingsGoTheDataStays() {
        write(File(config, "settings.json"), "{\"port\":3000}")
        write(File(config, "security.json"), "{\"users\":[{\"username\":\"admin\"}]}")
        write(File(config, "ssl-key.pem"))
        write(File(config, "package.json"))
        write(File(config, "node_modules/signalk-anchoralarm/index.js"))
        write(File(config, "plugin-config-data/anchoralarm.json"))
        write(File(config, "plugin-config-data/resources-provider/routes/r1.json"), "a route")
        write(File(config, "charts/a.mbtiles"), "a chart")
        write(File(config, "serverstate/course/settings.json"), "the course")

        val removed = ServerReset.settings(config, mirror = null, marker = marker)

        assertEquals(
            listOf("node_modules", "package.json", "security.json", "settings.json", "ssl-key.pem", "plugin-config-data/anchoralarm.json"),
            removed,
        )
        assertFalse(File(config, "settings.json").exists())
        assertFalse(File(config, "security.json").exists())
        assertFalse(File(config, "node_modules").exists())
        assertFalse(File(config, "plugin-config-data/anchoralarm.json").exists())
        assertEquals("a route", File(config, "plugin-config-data/resources-provider/routes/r1.json").readText())
        assertEquals("a chart", File(config, "charts/a.mbtiles").readText())
        assertEquals("the course", File(config, "serverstate/course/settings.json").readText())
    }

    @Test
    fun linksAreRemovedAsLinksNeverFollowed() {
        // node_modules links to the bridge's plugin, outside .signalk; a data folder is a link to the card.
        val plugin = tmp.newFolder("plugins", "android-bridge")
        write(File(plugin, "index.js"), "the bridge")
        Files.createDirectories(File(config, "node_modules/@pocketsk").toPath())
        Files.createSymbolicLink(File(config, "node_modules/@pocketsk/android-bridge").toPath(), plugin.toPath())
        val charts = File(shown, "charts").apply { mkdirs() }
        write(File(charts, "a.mbtiles"), "a chart")
        Files.createSymbolicLink(File(config, "charts").toPath(), charts.toPath())

        ServerReset.settings(config, mirror = shown, marker = marker)

        assertEquals("the bridge", File(plugin, "index.js").readText())
        assertTrue(Files.isSymbolicLink(File(config, "charts").toPath()))
        assertEquals("a chart", File(charts, "a.mbtiles").readText())
    }

    @Test
    fun theUsbFoldersCopiesGoSoNothingComesBackAtTheNextStart() {
        write(File(config, "settings.json"), "{\"port\":3000}")
        write(File(config, "security.json"), "{\"users\":[]}")
        write(File(config, "plugin-config-data/anchoralarm.json"), "{\"enabled\":true}")
        val mirror = ConfigMirror(config, shown, "what this folder is\n") {}
        mirror.sync(running = false)
        assertTrue(File(shown, "settings.json").exists())
        write(File(shown, "charts/a.mbtiles"), "a chart")

        ServerReset.settings(config, mirror = shown, marker = marker)
        assertFalse(File(shown, "settings.json").exists())
        assertFalse(File(shown, "plugin-config-data/anchoralarm.json").exists())

        val r = mirror.sync(running = false)
        assertEquals(emptyList<String>(), r.taken)
        assertFalse(File(config, "settings.json").exists())
        assertFalse(File(config, "security.json").exists())
        assertEquals("a chart", File(shown, "charts/a.mbtiles").readText())
    }

    @Test
    fun aCardOutDuringTheResetHasItsUnchangedCopiesDeletedAtTheNextSync() {
        write(File(config, "settings.json"), "{\"port\":3000}")
        val mirror = ConfigMirror(config, shown, "what this folder is\n") {}
        mirror.sync(running = false)

        ServerReset.settings(config, mirror = null, marker = marker)
        mirror.sync(running = false, ours = Backup.restored(marker))

        assertFalse(File(config, "settings.json").exists())
        assertFalse(File(shown, "settings.json").exists())
    }

    @Test
    fun anEmptyOrMissingSignalkIsNothingToDo() {
        assertEquals(emptyList<String>(), ServerReset.settings(config, mirror = shown, marker = marker))
        assertEquals(emptyList<String>(), ServerReset.settings(File(tmp.root, "none"), mirror = null, marker = marker))
        assertFalse(marker.exists())
    }

    @Test
    fun aSettingsFileOnlyTheComputerHasGoesTooItsFolderFilesStay() {
        write(File(config, "settings.json"), "{\"port\":3000}")
        val mirror = ConfigMirror(config, shown, "what this folder is\n") {}
        mirror.sync(running = false)
        // Put there on a computer while the phone had none: it would be taken in at the next start.
        write(File(shown, "security.json"), "{\"users\":[{\"username\":\"someone\"}]}")
        write(File(shown, "plugin-config-data/anchoralarm.json"), "{}")

        ServerReset.settings(config, mirror = shown, marker = marker)
        mirror.sync(running = false, ours = Backup.restored(marker))

        assertFalse(File(config, "security.json").exists())
        assertFalse(File(config, "plugin-config-data/anchoralarm.json").exists())
        assertFalse(File(shown, "security.json").exists())
        assertTrue(File(shown, ConfigMirror.README).exists())
    }

    @Test
    fun aCopyEditedOnACardThatWasOutDuringTheResetIsNotTakenBackIn() {
        write(File(config, "settings.json"), "{\"port\":3000}")
        write(File(config, "security.json"), "{\"users\":[{\"username\":\"admin\"}]}")
        val mirror = ConfigMirror(config, shown, "what this folder is\n") {}
        mirror.sync(running = false)
        // Edited on a computer, then the card went out of the phone before the reset.
        File(shown, "security.json").writeText("{\"users\":[{\"username\":\"edited\"}]}")

        ServerReset.settings(config, mirror = null, marker = marker)
        assertEquals(setOf("security.json", "settings.json"), Backup.restored(marker))
        val r = mirror.sync(running = false, ours = Backup.restored(marker))

        assertEquals(emptyList<String>(), r.taken)
        assertFalse(File(config, "security.json").exists())
        assertFalse(File(shown, "security.json").exists())
        assertFalse(File(shown, "settings.json").exists())
    }

    @Test
    fun aRestoreNotYetSyncedKeepsItsNamesInTheMarker() {
        marker.writeText("settings.json\n")
        write(File(config, "security.json"))
        ServerReset.settings(config, mirror = null, marker = marker)
        assertEquals(setOf("security.json", "settings.json"), Backup.restored(marker))
    }
}
