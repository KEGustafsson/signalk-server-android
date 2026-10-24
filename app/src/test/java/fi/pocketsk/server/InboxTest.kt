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

/** Files copied over USB into the inbox, taken into the server's data folders. */
class InboxTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var inbox: File
    private lateinit var config: File
    private val notes = mutableListOf<String>()
    private var clock = 1_000_000_000L

    @Before
    fun setUp() {
        inbox = tmp.newFolder("shared", "Android", "data", "fi.pocketsk", "files", Inbox.DIR)
        config = tmp.newFolder("home", ".signalk")
    }

    private fun box(free: Long = Long.MAX_VALUE / 2) = Inbox(inbox, config, { notes += it }, { free }, { clock })

    private fun drop(rel: String, text: String, age: Long = Inbox.SETTLE_MS + 1): File {
        val f = File(inbox, rel)
        f.parentFile.mkdirs()
        f.writeText(text)
        f.setLastModified(clock - age)
        return f
    }

    @Test
    fun theInboxIsMadeWithItsChartsFolder() {
        inbox.deleteRecursively()
        assertTrue(box().prepare())
        assertTrue(File(inbox, "charts").isDirectory)
    }

    @Test
    fun aSettledFileGoesIntoItsDataFolder() {
        val f = drop("charts/bay.mbtiles", "tiles")
        assertEquals(listOf("charts/bay.mbtiles"), box().take())
        assertEquals("tiles", File(config, "charts/bay.mbtiles").readText())
        assertFalse(f.exists())
        assertEquals(Inbox.Waiting(0, 0), box().waiting())
    }

    @Test
    fun aFileStillBeingCopiedWaits() {
        drop("charts/bay.mbtiles", "til", age = 1_000)
        assertEquals(emptyList<String>(), box().take())
        assertEquals(Inbox.Waiting(1, 3), box().waiting())
        clock += Inbox.SETTLE_MS
        assertEquals(listOf("charts/bay.mbtiles"), box().take())
    }

    @Test
    fun aFolderOnACardIsFilledThroughItsLinkAndStagedThere() {
        val card = tmp.newFolder("card", "signalk", "charts")
        Files.createSymbolicLink(File(config, "charts").toPath(), card.toPath())
        drop("charts/bay.mbtiles", "tiles")
        box().take()
        assertEquals("tiles", File(card, "bay.mbtiles").readText())
        assertTrue(File(card.parentFile, ".pocketsk-uploads/charts").isDirectory)
        assertEquals(0, File(card.parentFile, ".pocketsk-uploads/charts").list()!!.size)
    }

    @Test
    fun aFileOfTheSameNameIsReplaced() {
        File(config, "charts").mkdirs()
        File(config, "charts/bay.mbtiles").writeText("old")
        drop("charts/bay.mbtiles", "new")
        box().take()
        assertEquals("new", File(config, "charts/bay.mbtiles").readText())
    }

    @Test
    fun theFoldersAreTheServersDataFoldersOnly() {
        drop("applicationData/x.json", "app")
        drop("node_modules/evil.js", "code")
        drop("plugin-config-data/some-plugin.json", "config")
        drop(".hidden/x", "x")
        drop("charts/.thumbs", "x")
        drop("loose.mbtiles", "x")
        assertEquals(listOf("applicationData/x.json"), box().take())
        assertFalse(File(config, "node_modules").exists())
        assertFalse(File(config, "plugin-config-data").exists())
        assertTrue(File(inbox, "node_modules/evil.js").exists())
    }

    @Test
    fun aFileWithoutRoomStaysAndTheOthersGo() {
        drop("charts/big.mbtiles", "x".repeat(100))
        val inbox = Inbox(inbox, config, { notes += it }, { 64L * 1024 * 1024 + 50 }, { clock })
        drop("applicationData/small.json", "x")
        assertEquals(listOf("applicationData/small.json"), inbox.take())
        assertTrue(File(this.inbox, "charts/big.mbtiles").exists())
        assertTrue(notes.any { it.startsWith("charts/big.mbtiles stays in the inbox") })
    }
}
