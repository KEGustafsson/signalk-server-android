package fi.pocketsk.bridge

import fi.pocketsk.node.Tar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The plugin tar the build puts into the APK ([BridgePlugin.ASSET]) unpacks with the app's own
 * reader into a package the server can load: Gradle's tar writer and [Tar] must agree.
 */
class BridgePluginTarTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun theApksPluginUnpacksIntoALoadablePackage() {
        val tar = File(System.getProperty("pocketsk.bridgeTar") ?: error("run through Gradle: pocketsk.bridgeTar is not set"))
        val dest = tmp.newFolder("android-bridge")
        val files = tar.inputStream().use { Tar.extract(it, dest) }
        assertEquals(setOf("CHANGELOG.md", "LICENSE", "README.md", "index.js", "package.json", "lib/alarms.js", "lib/link.js", "lib/protocol.js", "lib/serial.js"),
            dest.walkTopDown().filter { it.isFile }.map { it.relativeTo(dest).path }.toSet())
        assertEquals(9, files)
        assertFalse("the tests stay out of the APK", File(dest, "test").exists())
        val pkg = File(dest, "package.json").readText()
        assertTrue(pkg.contains("\"name\": \"${BridgePlugin.PACKAGE}\""))
        assertTrue(pkg.contains("signalk-node-server-plugin"))
        assertTrue(File(dest, "index.js").readText().contains("id: \"${BridgePlugin.PLUGIN_ID}\""))
    }
}
