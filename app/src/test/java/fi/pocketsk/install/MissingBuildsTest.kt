package fi.pocketsk.install

import fi.pocketsk.bridge.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.net.URI
import java.net.URLDecoder

/** The native addons installed without an Android build, as npm's launcher records them. */
class MissingBuildsTest {
    @get:Rule val tmp = TemporaryFolder()

    /** One tree's record as prebuilds.js writes it. */
    private fun record(root: String, list: List<MissingBuilds.Missing>) = """{"format":1,"root":${Json.quote(root)},"missing":""" +
        list.joinToString(",", "[", "]") {
            """{"name":${Json.quote(it.name)},"version":${Json.quote(it.version)},"dir":${Json.quote(it.dir)}}"""
        } + "}"

    private fun pkg(name: String, version: String, addon: String? = null): MissingBuilds.Missing {
        val dir = File(tmp.root, "node_modules/$name").apply { mkdirs() }
        File(dir, "package.json").writeText("""{"name":${Json.quote(name)},"version":${Json.quote(version)}}""")
        if (addon != null) File(dir, addon).apply { parentFile!!.mkdirs() }.writeText("addon")
        return MissingBuilds.Missing(name, version, dir.path)
    }

    @Test
    fun aTreesRecordGivesItsPackagesInOrder() {
        val a = MissingBuilds.Missing("usb", "2.9.0", "/x/node_modules/usb")
        val b = MissingBuilds.Missing("@canboat/socketcan", "1.0.0", "/x/node_modules/@canboat/socketcan")
        assertEquals(listOf(a, b), MissingBuilds.parse(record("/x/node_modules", listOf(a, b))))
    }

    @Test
    fun anythingElseIsNoRecord() {
        for (text in listOf("", "[1, 2", "[]", """{"format":2,"missing":[]}""", """{"format":1}""", """{"format":1,"missing":{}}""")) {
            assertEquals(text, emptyList<MissingBuilds.Missing>(), MissingBuilds.parse(text))
        }
        assertEquals(
            "entries that are no package are skipped",
            listOf(MissingBuilds.Missing("usb", "2.9.0", "/d")),
            MissingBuilds.parse("""{"format":1,"root":"/r","missing":[1,{"name":"x"},{"name":"","version":"1","dir":"/d"},{"name":"usb","version":"2.9.0","dir":"/d"}]}"""),
        )
    }

    @Test
    fun aPackageIsMissingOnlyWhileItIsThereInThatVersionWithoutAnAddon() {
        assertTrue(MissingBuilds.stillMissing(pkg("usb", "2.9.0")))
        assertFalse("another version installed since", MissingBuilds.stillMissing(pkg("serial", "2.0.0").copy(version = "1.0.0")))
        assertFalse("its own build", MissingBuilds.stillMissing(pkg("sqlite3", "5.1.6", addon = "build/Release/node_sqlite3.node")))
        assertFalse("node-gyp-build's place", MissingBuilds.stillMissing(pkg("bufferutil", "4.1.0", addon = "prebuilds/android-arm64/bufferutil.node")))
        assertFalse("removed", MissingBuilds.stillMissing(MissingBuilds.Missing("gone", "1.0.0", File(tmp.root, "gone").path)))
        assertTrue("another platform's build does not count", MissingBuilds.stillMissing(pkg("noble", "1.9.2", addon = "prebuilds/linux-x64/noble.node")))
        val stale = pkg("better-sqlite3", "12.11.1", addon = "build/Release/better_sqlite3.node")
        File(stale.dir, "build/Release/${MissingBuilds.MARKER}").writeText("""{"file":"better-sqlite3@12.11.1-android-arm64-node137.tar.gz"}""")
        assertTrue("a build the launcher placed for another runtime does not count", MissingBuilds.stillMissing(stale))
    }

    @Test
    fun readGivesWhatIsStillMissingInEveryTreeEachOnce() {
        val usb = pkg("usb", "2.9.0")
        val built = pkg("sqlite3", "5.1.6", addon = "build/Release/node_sqlite3.node")
        val can = pkg("@canboat/socketcan", "1.0.0")
        val dir = File(tmp.root, MissingBuilds.DIR).apply { mkdirs() }
        File(dir, "a.json").writeText(record("/a", listOf(usb, built)))
        File(dir, "b.json").writeText(record("/b", listOf(usb, can)))
        File(dir, ".c.json.1234.ab12cd34.part").writeText(record("/c", listOf(pkg("staged", "1.0.0"))))
        File(dir, "d.json").writeText("[1, 2")
        assertEquals(listOf(usb, can), MissingBuilds.read(dir))
        assertEquals(emptyList<MissingBuilds.Missing>(), MissingBuilds.read(File(tmp.root, "none")))
    }

    @Test
    fun theRequestIsANewIssueHereWithItsTitleAndText() {
        val url = MissingBuilds.requestUrl(MissingBuilds.Missing("@canboat/socketcan", "1.0.0", "/d"), "1.250", "24.20.0-0")
        assertTrue(url, url.startsWith("https://github.com/KEGustafsson/signalk-server-android/issues/new?title="))
        val query = URI(url).rawQuery.split('&').associate { it.substringBefore('=') to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
        assertEquals("Android build for @canboat/socketcan@1.0.0", query["title"])
        val body = query.getValue("body")
        assertTrue(body, body.contains("`@canboat/socketcan` 1.0.0"))
        assertTrue(body, body.contains("- App: 1.250") && body.contains("- Runtime: nodejs-mobile 24.20.0-0"))
        assertFalse("spaces are %20, not +", url.contains('+'))
    }

    @Test
    fun theDirectoryIsTheOnePrebuildsJsWrites() {
        // runtime-tools/npm/prebuilds.js: `const MISSING_DIR = "missing";`
        val dir = File(System.getProperty("pocketsk.npmToolsDir") ?: error("pocketsk.npmToolsDir not set by the build"))
        val js = File(dir, "prebuilds.js").readText()
        assertTrue(js.contains("const MISSING_DIR = \"${MissingBuilds.DIR}\";"))
        assertTrue(js.contains("const MARKER = \"${MissingBuilds.MARKER}\";"))
    }
}
