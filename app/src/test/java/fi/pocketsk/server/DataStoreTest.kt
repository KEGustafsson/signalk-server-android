package fi.pocketsk.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** The server's data folders on a card, linked from `.signalk`, and back. */
class DataStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private lateinit var config: File
    private lateinit var cardA: File
    private lateinit var cardB: File
    private val notes = mutableListOf<String>()

    @Before
    fun setUp() {
        config = tmp.newFolder("home", ".signalk")
        cardA = File(tmp.newFolder("cardA", "Android", "data", "fi.pocketsk", "files"), DataStore.CARD_DIR)
        cardB = File(tmp.newFolder("cardB", "Android", "data", "fi.pocketsk", "files"), DataStore.CARD_DIR)
    }

    private fun store(free: Long = Long.MAX_VALUE / 2) = DataStore(config, { notes += it }, { free })

    private fun write(f: File, text: String) {
        f.parentFile.mkdirs()
        f.writeText(text)
    }

    private fun isLink(f: File) = Files.isSymbolicLink(f.toPath())

    private fun linkOf(f: File) = Files.readSymbolicLink(f.toPath()).toFile()

    /** A server's `.signalk` with a bit of everything. */
    private fun server() {
        write(File(config, "settings.json"), "{}")
        write(File(config, "security.json"), "secret")
        write(File(config, "node_modules/some-plugin/index.js"), "code")
        write(File(config, "plugin-config-data/some-plugin.json"), "password")
        write(File(config, "plugin-config-data/some-plugin/history.db"), "rows")
        write(File(config, "charts/bay.mbtiles"), "tiles")
        write(File(config, "serverstate/course/settings.json"), "course")
    }

    @Test
    fun aServerWithoutChartsGetsTheFolderOnTheCard() {
        write(File(config, "settings.json"), "{}")
        store().place(cardA)
        assertTrue(isLink(File(config, "charts")))
        assertEquals(File(cardA, "charts").absolutePath, linkOf(File(config, "charts")).absolutePath)
        assertTrue(File(cardA, "charts").isDirectory)
    }

    @Test
    fun aChartsFolderTheCardCannotHoldIsAnError() {
        write(File(cardA, "charts"), "a file where the folder goes")
        try {
            store().place(cardA)
            fail("placed without charts on the card")
        } catch (e: IOException) {
            assertTrue(e.message!!.startsWith("cannot make"))
        }
        assertFalse(File(config, "charts").exists())
    }

    @Test
    fun aLinkedFolderTheCardCannotHoldIsAnError() {
        server()
        store().place(cardA)
        File(cardA, "charts").deleteRecursively()
        write(File(cardA, "charts"), "a file where the folder goes")
        try {
            store().place(cardA)
            fail("placed with charts unusable on the card")
        } catch (_: IOException) {
        }
    }

    @Test
    fun theDataFoldersGoToTheCardAndTheRestStays() {
        server()
        val moved = mutableListOf<String>()
        store().place(cardA) { moved += it }
        assertEquals(listOf("charts", "plugin-config-data/some-plugin", "serverstate"), moved)
        for (rel in moved) {
            assertTrue(rel, isLink(File(config, rel)))
            assertEquals(File(cardA, rel), linkOf(File(config, rel)))
        }
        assertEquals("tiles", File(config, "charts/bay.mbtiles").readText())
        assertEquals("tiles", File(cardA, "charts/bay.mbtiles").readText())
        assertEquals("rows", File(cardA, "plugin-config-data/some-plugin/history.db").readText())
        assertEquals("course", File(cardA, "serverstate/course/settings.json").readText())
        // What stays on the phone, for real: the secrets, the plugins and their settings.
        for (rel in listOf("settings.json", "security.json", "plugin-config-data/some-plugin.json")) {
            assertTrue(rel, Files.isRegularFile(File(config, rel).toPath()))
            assertFalse(rel, File(cardA, rel).exists())
        }
        assertFalse(isLink(File(config, "node_modules")))
        assertFalse(isLink(File(config, "plugin-config-data")))
        assertFalse(File(cardA, "node_modules").exists())
        assertTrue(config.list()!!.none { it.endsWith(".moved") || it.endsWith(".partial") })
    }

    @Test
    fun aLinkToASecretIsNotCopiedToTheCard() {
        server()
        Files.createSymbolicLink(File(config, "charts/key").toPath(), File(config, "security.json").toPath())
        Files.createSymbolicLink(File(config, "charts/config").toPath(), File(config, "plugin-config-data/some-plugin.json").toPath())
        write(File(config, "elsewhere/notes.txt"), "notes")
        Files.createSymbolicLink(File(config, "charts/notes").toPath(), File(config, "elsewhere/notes.txt").toPath())
        store().place(cardA)
        assertFalse(File(cardA, "charts/key").exists())
        assertFalse(File(cardA, "charts/config").exists())
        assertEquals("notes", File(cardA, "charts/notes").readText())
        assertTrue(notes.any { it.contains("a link to a file that stays on the phone") })
    }

    @Test
    fun placingAgainChangesNothing() {
        server()
        store().place(cardA)
        val moved = mutableListOf<String>()
        store().place(cardA) { moved += it }
        assertEquals(emptyList<String>(), moved)
        assertEquals("tiles", File(config, "charts/bay.mbtiles").readText())
    }

    @Test
    fun aFolderMadeDuringARunFollowsOnTheNext() {
        server()
        store().place(cardA)
        write(File(config, "applicationData/users/x.json"), "app")
        store().place(cardA)
        assertTrue(isLink(File(config, "applicationData")))
        assertEquals("app", File(cardA, "applicationData/users/x.json").readText())
    }

    @Test
    fun theDataComesBackToThePhone() {
        server()
        store().place(cardA)
        store().place(null)
        for (rel in listOf("charts", "plugin-config-data/some-plugin", "serverstate")) {
            assertFalse(rel, isLink(File(config, rel)))
            assertTrue(rel, File(config, rel).isDirectory)
            assertFalse(rel, File(cardA, rel).exists())
        }
        assertEquals("tiles", File(config, "charts/bay.mbtiles").readText())
        assertEquals("rows", File(config, "plugin-config-data/some-plugin/history.db").readText())
    }

    @Test
    fun aFolderOnlyTheCardHasIsLinked() {
        // Charts copied onto the card with a PC, the phone never having had any.
        write(File(cardA, "charts/coast.mbtiles"), "coast")
        store().place(cardA)
        assertTrue(isLink(File(config, "charts")))
        assertEquals("coast", File(config, "charts/coast.mbtiles").readText())
    }

    @Test
    fun aFolderOnBothIsMergedAndNothingIsLost() {
        write(File(cardA, "charts/coast.mbtiles"), "card's coast")
        write(File(config, "charts/coast.mbtiles"), "phone's coast")
        write(File(config, "charts/bay.mbtiles"), "bay")
        store().place(cardA)
        assertEquals("card's coast", File(config, "charts/coast.mbtiles").readText())
        assertEquals("phone's coast", File(config, "charts/coast.mbtiles.from-phone").readText())
        assertEquals("bay", File(config, "charts/bay.mbtiles").readText())
    }

    @Test
    fun aMoveFromOneCardToAnotherCarriesTheData() {
        server()
        store().place(cardA)
        store().place(cardB)
        assertEquals(File(cardB, "charts"), linkOf(File(config, "charts")))
        assertEquals("tiles", File(cardB, "charts/bay.mbtiles").readText())
        assertFalse(File(cardA, "charts").exists())
    }

    @Test
    fun aFolderWhoseCardIsGoneStartsEmptyOnThePhone() {
        server()
        store().place(cardA)
        cardA.deleteRecursively()
        store().place(null)
        assertTrue(File(config, "charts").isDirectory)
        assertFalse(isLink(File(config, "charts")))
        assertEquals(0, File(config, "charts").list()!!.size)
        assertTrue(notes.any { it.startsWith("charts was on a card that is not there") })
    }

    @Test
    fun aCopyForTheSmokeTestGetsEmptyFoldersForTheLinks() {
        server()
        store().place(cardA)
        val copy = tmp.newFolder("verify")
        // What `cp -a` makes: the links copied as links.
        Files.createSymbolicLink(File(copy, "charts").toPath(), File(cardA, "charts").toPath())
        write(File(copy, "settings.json"), "{}")
        DataStore(copy, { notes += it }, { Long.MAX_VALUE / 2 }).detach()
        assertFalse(isLink(File(copy, "charts")))
        assertTrue(File(copy, "charts").isDirectory)
        assertEquals(0, File(copy, "charts").list()!!.size)
        assertEquals("tiles", File(cardA, "charts/bay.mbtiles").readText())
        assertEquals("{}", File(copy, "settings.json").readText())
    }

    @Test
    fun aLinkOfSomeoneElseIsLeftAlone() {
        val elsewhere = tmp.newFolder("elsewhere")
        Files.createSymbolicLink(File(config, "mine").toPath(), elsewhere.toPath())
        store().place(cardA)
        assertEquals(elsewhere, linkOf(File(config, "mine")))
        assertFalse(File(cardA, "mine").exists())
        store().place(null)
        assertEquals(elsewhere, linkOf(File(config, "mine")))
    }

    @Test
    fun aCardWithoutRoomIsRefusedBeforeAnythingIsWritten() {
        server()
        try {
            store(free = 1024).place(cardA)
            fail("placed on a full card")
        } catch (e: IOException) {
            assertTrue(e.message!!, e.message!!.contains("MB free"))
        }
        assertFalse(isLink(File(config, "charts")))
        assertEquals("tiles", File(config, "charts/bay.mbtiles").readText())
        assertFalse(File(cardA, "charts").exists())
    }

    @Test
    fun aCopyCutShortIsDoneAgain() {
        server()
        // Killed while copying charts to the card: half a staging folder there.
        write(File(cardA, "charts.partial/bay.mbtiles"), "ti")
        store().place(cardA)
        assertEquals("tiles", File(cardA, "charts/bay.mbtiles").readText())
        assertFalse(File(cardA, "charts.partial").exists())
    }

    @Test
    fun aMoveKilledAfterItsCopyEndsWhereItStopped() {
        // Killed after charts was renamed aside on the phone, before it was linked.
        write(File(cardA, "charts/bay.mbtiles"), "tiles")
        write(File(config, "charts.moved/bay.mbtiles"), "tiles")
        store().place(cardA)
        assertTrue(isLink(File(config, "charts")))
        assertFalse(File(config, "charts.moved").exists())
        assertEquals("tiles", File(config, "charts/bay.mbtiles").readText())
        assertFalse(File(cardA, "charts/bay.mbtiles.from-phone").exists())
    }

    @Test
    fun aReturnKilledAfterTheLinkWentIsPutInPlace() {
        // Killed coming back to the phone, between removing the link and renaming the copy.
        write(File(config, "charts.partial/bay.mbtiles"), "tiles")
        store().place(null)
        assertEquals("tiles", File(config, "charts/bay.mbtiles").readText())
        assertFalse(File(config, "charts.partial").exists())
    }

    @Test
    fun aReturnKilledMidCopyIsDoneAgain() {
        server()
        store().place(cardA)
        write(File(config, "charts.partial/bay.mbtiles"), "ti")
        store().place(null)
        assertEquals("tiles", File(config, "charts/bay.mbtiles").readText())
        assertFalse(File(config, "charts.partial").exists())
    }
}
