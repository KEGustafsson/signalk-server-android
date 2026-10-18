package fi.pocketsk.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The rotating log files: bounded, newest in server.log, oldest dropped. */
class LogFilesTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun read(f: File) = f.readText().lines().filter { it.isNotEmpty() }

    @Test
    fun appendsUntilFullThenTurnsOver() {
        val dir = tmp.newFolder()
        val log = LogFiles(dir, maxBytes = 10, keep = 2)
        for (i in 1..7) log.append("l$i") // three bytes each with the newline: three to a file
        log.close()
        assertEquals(listOf("l7"), read(File(dir, "server.log")))
        assertEquals(listOf("l4", "l5", "l6"), read(File(dir, "server.log.1")))
        assertEquals(listOf("l1", "l2", "l3"), read(File(dir, "server.log.2")))
        assertFalse(File(dir, "server.log.3").exists())
    }

    @Test
    fun dropsTheOldest() {
        val dir = tmp.newFolder()
        val log = LogFiles(dir, maxBytes = 3, keep = 2)
        for (i in 1..5) log.append("l$i")
        log.close()
        assertEquals(listOf("l5"), read(File(dir, "server.log")))
        assertEquals(listOf("l4"), read(File(dir, "server.log.1")))
        assertEquals(listOf("l3"), read(File(dir, "server.log.2")))
        assertFalse(File(dir, "server.log.3").exists())
    }

    @Test
    fun carriesOnInTheFileAnEarlierProcessLeft() {
        val dir = tmp.newFolder()
        File(dir, "server.log").writeText("a1\na2\n")
        val log = LogFiles(dir, maxBytes = 9, keep = 1)
        log.append("a3")
        log.append("a4")
        log.close()
        assertEquals(listOf("a1", "a2", "a3"), read(File(dir, "server.log.1")))
        assertEquals(listOf("a4"), read(File(dir, "server.log")))
    }

    @Test
    fun aFullFileThatCannotBeMovedAsideIsStartedAgain() {
        val dir = tmp.newFolder()
        // A directory with something in it: no file can be renamed over it.
        File(dir, "server.log.1").mkdirs()
        File(dir, "server.log.1/x").writeText("x")
        val log = LogFiles(dir, maxBytes = 6, keep = 1)
        for (i in 1..5) log.append("l$i")
        log.close()
        assertEquals(listOf("l5"), read(File(dir, "server.log")))
    }

    @Test
    fun aLineLargerThanAFileStillGoesIn() {
        val dir = tmp.newFolder()
        val log = LogFiles(dir, maxBytes = 4, keep = 1)
        log.append("long line")
        log.close()
        assertTrue(File(dir, "server.log").readText().startsWith("long line"))
    }
}
