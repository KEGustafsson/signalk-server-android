package fi.pocketsk.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

/** The diagnostics bundle: what goes in, and that no secret does. */
class DiagnosticsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = linkedMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                out[e.name] = zip.readBytes().toString(Charsets.UTF_8)
            }
        }
        return out
    }

    @Test
    fun settingsLeaveSecretsOutAndShortenAddresses() {
        val text = Diagnostics.settings(
            mapOf(
                "serial" to mapOf("bluetooth" to setOf("00:1B:DC:0A:1B:2C", "aa:bb:cc:dd:ee:ff")),
                "server" to mapOf("running" to true, "admin_token" to "eyJhbGciOi", "data_volume" to "1234-ABCD"),
            ),
        )
        assertEquals(
            """
            [serial]
            bluetooth = [XX:XX:XX:XX:1B:2C, XX:XX:XX:XX:ee:ff]

            [server]
            admin_token = (left out)
            data_volume = 1234-ABCD
            running = true

            """.trimIndent() + "\n",
            text,
        )
        assertFalse(text.contains("eyJ"))
    }

    @Test
    fun theZipHoldsTheTextsAndTheCopiesThereAreWithAddressesMasked() {
        val logs = tmp.newFolder("logs")
        File(logs, "server.log").writeText("bridge: reading 00:1B:DC:0A:1B:2C\n")
        val out = ByteArrayOutputStream()
        Diagnostics.write(
            out,
            listOf(Diagnostics.Text("README.txt", "what is here")),
            listOf(
                Diagnostics.Copy("logs/server.log", File(logs, "server.log")),
                Diagnostics.Copy("logs/server.log.1", File(logs, "server.log.1")),
            ),
            secretDir = File(tmp.root, "home/.signalk"),
        )
        assertEquals(
            mapOf("README.txt" to "what is here", "logs/server.log" to "bridge: reading XX:XX:XX:XX:1B:2C\n"),
            unzip(out.toByteArray()),
        )
    }

    @Test
    fun aLongCopyIsMaskedInPiecesWithAddressesAcrossTheirEdges() {
        // Addresses at every offset against a small buffer, so some fall across two reads.
        val text = buildString {
            for (i in 0 until 40) append("x".repeat(i % 7)).append(" 00:1B:DC:0A:1B:${"%02X".format(i)} ")
            append("end 0a:0b:0c:0d:0e:0f")
        }
        val log = tmp.newFile("server.log").apply { writeText(text) }
        val out = ByteArrayOutputStream()
        Diagnostics.write(out, emptyList(), listOf(Diagnostics.Copy("server.log", log)), File(tmp.root, "home/.signalk"), buffer = 5)
        assertEquals(Diagnostics.maskAddresses(text), unzip(out.toByteArray())["server.log"])
        assertFalse(unzip(out.toByteArray())["server.log"]!!.contains("DC:0A"))
    }

    @Test
    fun nothingUnderTheServersConfigurationIsEverWritten() {
        val secret = tmp.newFolder("home", ".signalk")
        val security = File(secret, "security.json").apply { writeText("""{"secretKey":"x"}""") }
        val sneaky = File(tmp.root, "logs/../home/.signalk/security.json")
        for (file in listOf(security, sneaky)) {
            val out = ByteArrayOutputStream()
            try {
                Diagnostics.write(out, emptyList(), listOf(Diagnostics.Copy("security.json", file)), secret)
                throw AssertionError("$file was written")
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.contains("never leaves the phone"))
            }
            assertEquals(0, out.size())
        }
    }
}
