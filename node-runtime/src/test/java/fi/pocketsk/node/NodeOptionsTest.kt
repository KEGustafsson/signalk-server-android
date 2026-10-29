package fi.pocketsk.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * NODE_OPTIONS as every node gets it, and the preloads it names and npm's launcher where the APK
 * takes them from.
 */
class NodeOptionsTest {
    @Test
    fun theHeapCapAloneWithoutAPreload() {
        assertEquals("--max-old-space-size=512", NodeRuntime.nodeOptions(512, emptyList()))
    }

    @Test
    fun eachPreloadIsRequiredByItsQuotedPathInOrder() {
        val dir = "/data/user/0/fi.pocketsk/files/runtime"
        assertEquals(
            "--max-old-space-size=1024 --require=\"$dir/loopback.js\" --require=\"$dir/mdns-lookup.js\" --require=\"$dir/node-ble.js\"",
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

    @Test
    fun npmsLauncherIsWhereTheBuildTakesIt() {
        val dir = File(System.getProperty("pocketsk.npmToolsDir") ?: error("pocketsk.npmToolsDir not set by the build"))
        assertEquals(NodeRuntime.NPM_TOOL_ASSETS.sorted(), dir.list()!!.sorted())
        // The two directories share the assets' root and files/runtime: no name may be in both.
        assertTrue(NodeRuntime.PRELOAD_ASSETS.none { it in NodeRuntime.NPM_TOOL_ASSETS })
    }

    @Test
    fun aLoopbackSpawnTakesOnlyTheRuntimesOwnNodeOptions() {
        val loopback = mapOf(NodeRuntime.LOOPBACK_ENV to NodeRuntime.LOOPBACK_ON)
        assertFalse(NodeRuntime.loopbackRefused(loopback, preloadInPlace = true))
        assertTrue("loopback.js missing", NodeRuntime.loopbackRefused(loopback, preloadInPlace = false))
        // Even one that names the file: a tab or a --title can hide it from Node.
        assertTrue(NodeRuntime.loopbackRefused(loopback + ("NODE_OPTIONS" to "--title=local\t--require=/x/loopback.js"), preloadInPlace = true))
        assertFalse("no loopback asked for", NodeRuntime.loopbackRefused(mapOf("NODE_OPTIONS" to "--inspect"), preloadInPlace = false))
    }

    @Test
    fun theLoopbackIsAskedForAsLoopbackJsReadsIt() {
        // runtime-tools/preload/loopback.js: `process.env.POCKETSK_LOOPBACK === "1"`.
        val js = File(System.getProperty("pocketsk.preloadDir") ?: error("pocketsk.preloadDir not set by the build"), "loopback.js").readText()
        assertTrue(js.contains("const ENV = \"${NodeRuntime.LOOPBACK_ENV}\";"))
        assertTrue(js.contains("process.env[ENV] === \"${NodeRuntime.LOOPBACK_ON}\""))
    }
}
