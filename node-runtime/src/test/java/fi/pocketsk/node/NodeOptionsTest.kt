package fi.pocketsk.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NODE_OPTIONS as every node gets it, and the preload it names where the APK takes it from. */
class NodeOptionsTest {
    @Test
    fun theHeapCapAloneWithoutAPreload() {
        assertEquals("--max-old-space-size=512", NodeRuntime.nodeOptions(512, null))
    }

    @Test
    fun thePreloadIsRequiredByItsQuotedPath() {
        val preload = File("/data/user/0/fi.pocketsk/files/runtime/mdns-lookup.js")
        assertEquals(
            "--max-old-space-size=1024 --require=\"/data/user/0/fi.pocketsk/files/runtime/mdns-lookup.js\"",
            NodeRuntime.nodeOptions(1024, preload),
        )
    }

    @Test
    fun theAssetIsWhereTheBuildTakesIt() {
        val dir = File(System.getProperty("pocketsk.preloadDir") ?: error("pocketsk.preloadDir not set by the build"))
        val preload = File(dir, NodeRuntime.PRELOAD_ASSET)
        assertTrue("$preload", preload.isFile)
        // The directory is an asset source as it stands: nothing else in it may ride into the APK.
        assertEquals(listOf(NodeRuntime.PRELOAD_ASSET), dir.list()!!.sorted())
    }
}
