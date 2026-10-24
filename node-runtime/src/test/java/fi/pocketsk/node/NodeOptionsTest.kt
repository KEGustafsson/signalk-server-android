package fi.pocketsk.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** NODE_OPTIONS as every node gets it, and the preloads it names where the APK takes them from. */
class NodeOptionsTest {
    @Test
    fun theHeapCapAloneWithoutAPreload() {
        assertEquals("--max-old-space-size=512", NodeRuntime.nodeOptions(512, emptyList()))
    }

    @Test
    fun eachPreloadIsRequiredByItsQuotedPathInOrder() {
        val dir = "/data/user/0/fi.pocketsk/files/runtime"
        assertEquals(
            "--max-old-space-size=1024 --require=\"$dir/mdns-lookup.js\" --require=\"$dir/node-ble.js\"",
            NodeRuntime.nodeOptions(1024, NodeRuntime.PRELOAD_ASSETS.map { File(dir, it) }),
        )
    }

    @Test
    fun theAssetsAreWhereTheBuildTakesThem() {
        val dir = File(System.getProperty("pocketsk.preloadDir") ?: error("pocketsk.preloadDir not set by the build"))
        for (name in NodeRuntime.PRELOAD_ASSETS) assertTrue(name, File(dir, name).isFile)
        // The directory is an asset source as it stands: nothing else in it may ride into the APK.
        assertEquals(NodeRuntime.PRELOAD_ASSETS.sorted(), dir.list()!!.sorted())
    }
}
