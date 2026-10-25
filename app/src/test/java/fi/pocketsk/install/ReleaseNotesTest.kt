package fi.pocketsk.install

import fi.pocketsk.bridge.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/** signalk-server's GitHub release notes as the Updates card shows them. */
class ReleaseNotesTest {
    @get:Rule val tmp = TemporaryFolder()

    /** A body as GitHub generates it for signalk-server. */
    private val generated = """
        <!-- Release notes generated using configuration in .github/release.yml -->

        ## What's Changed
        * feat: course API arrival circle by @tkurki in https://github.com/SignalK/signalk-server/pull/2101
        * fix: **History** API returns `null` for missing paths by @someone-else in https://github.com/SignalK/signalk-server/pull/2104
        * chore(deps): bump ws by @dependabot[bot] in https://github.com/SignalK/signalk-server/pull/2099

        ## New Contributors
        * @someone-else made their first contribution in https://github.com/SignalK/signalk-server/pull/2104

        **Full Changelog**: https://github.com/SignalK/signalk-server/compare/v2.12.0...v2.13.0
    """.trimIndent()

    private fun release(tag: String, body: String = "", draft: Boolean = false, published: String? = "2026-10-01T12:00:00Z") =
        """{"tag_name":${Json.quote(tag)},"draft":$draft,"prerelease":false,""" +
            """"published_at":${published?.let(Json::quote) ?: "null"},"body":${Json.quote(body)},"author":{"login":"x"}}"""

    private fun json(vararg releases: String) = releases.joinToString(",", "[", "]")

    private fun v(text: String) = ServerVersion.parse(text)!!

    @Test
    fun generatedNotesAreTheChangesEachWithItsPullRequest() {
        assertEquals(
            listOf(
                "• feat: course API arrival circle (#2101)",
                "• fix: History API returns null for missing paths (#2104)",
                "• chore(deps): bump ws (#2099)",
            ),
            ReleaseNotes.lines(generated),
        )
    }

    @Test
    fun notesWrittenByHandKeepTheirHeadingsAndPoints() {
        val body = """
            # Server 2.14: the [course API](https://signalk.org/specification) grows up

            Highlights:

            - Routes ![shot](https://x/y.png) on the **chart**
            + <b>Faster</b> start, see https://github.com/SignalK/signalk-server/pull/2200

            ### Breaking ###
            1. Node 20 or later
        """.trimIndent()
        assertEquals(
            listOf(
                "Server 2.14: the course API grows up",
                "Highlights:",
                "• Routes on the chart",
                "• Faster start, see #2200",
                "Breaking",
                "1. Node 20 or later",
            ),
            ReleaseNotes.lines(body),
        )
    }

    @Test
    fun theListIsTheVersionsNewestFirstWithoutDraftsOrOtherTags() {
        val releases = ReleaseNotes.parse(
            json(
                release("v2.12.0", generated),
                release("v2.14.0", draft = true),
                release("v2.13.0"),
                release("server-admin-ui-1.2"),
                release("2.13.1-beta.1", published = null),
            ),
        )
        assertEquals(listOf("2.13.1-beta.1", "v2.13.0", "v2.12.0"), releases.map { it.version.text })
        assertEquals(1_790_856_000_000L, releases[1].published)
        assertNull(releases[0].published)
        assertEquals(3, releases[2].lines.size)
    }

    @Test
    fun anUpdateShowsWhatItBringsAndNoMore() {
        val releases = ReleaseNotes.parse(
            json(release("v2.14.0"), release("v2.13.1-beta.1"), release("v2.13.0"), release("v2.12.0"), release("v2.11.0")),
        )
        assertEquals(
            listOf("v2.13.0", "v2.12.0"),
            ReleaseNotes.between(releases, "2.11.0", v("2.13.0")).map { it.version.text },
        )
        // A beta on offer brings the betas on the way.
        assertEquals(
            listOf("v2.13.1-beta.1", "v2.13.0"),
            ReleaseNotes.between(releases, "2.12.0", v("2.13.1-beta.1")).map { it.version.text },
        )
        // A live build that is not a version: the offered release alone.
        assertEquals(listOf("v2.14.0"), ReleaseNotes.between(releases, "local-build", v("2.14.0")).map { it.version.text })
        assertEquals(emptyList<String>(), ReleaseNotes.between(releases, "2.14.0", v("2.14.0")).map { it.version.text })
    }

    @Test(expected = Json.ParseException::class)
    fun anAnswerThatIsNotAListIsAnError() {
        ReleaseNotes.parse("""{"message":"API rate limit exceeded"}""")
    }

    @Test
    fun aFreshCacheIsUsedAndABrokenOneIsDropped() {
        val cache = File(tmp.root, "release-notes.json")
        cache.writeText(json(release("v2.13.0", generated)))
        val source = ReleaseNotesSource(cache, now = { cache.lastModified() + 1000 })
        assertEquals(listOf("v2.13.0"), source.releases(maxAgeMs = 60_000).map { it.version.text })

        cache.writeText("<html>not JSON</html>")
        try {
            source.releases(maxAgeMs = 60_000)
            throw AssertionError("a broken cache was read")
        } catch (e: IOException) {
            assertTrue(e.message!!.startsWith("GitHub's answer could not be read"))
        }
        assertTrue(!cache.exists())
    }

    @Test
    fun anAnswerThatCannotBeReadNeverReplacesAGoodCache() {
        val cache = File(tmp.root, "release-notes.json")
        val good = json(release("v2.13.0", generated))
        cache.writeText(good)
        var answer = "<html>a captive portal</html>"
        val source = ReleaseNotesSource(cache, now = { cache.lastModified() + 120_000 }, fetch = { answer })

        // Stale, and GitHub's answer is not releases: the old cache stands in, untouched.
        assertEquals(listOf("v2.13.0"), source.releases(maxAgeMs = 60_000).map { it.version.text })
        assertEquals(good, cache.readText())

        answer = json(release("v2.14.0"), release("v2.13.0", generated))
        assertEquals(listOf("v2.14.0", "v2.13.0"), source.releases(maxAgeMs = 60_000).map { it.version.text })
        assertEquals(answer, cache.readText())

        // No cache at all: the answer's fault is the error.
        cache.delete()
        answer = "<html>a captive portal</html>"
        try {
            source.releases(maxAgeMs = 60_000)
            throw AssertionError("an unreadable answer was taken")
        } catch (e: IOException) {
            assertTrue(e.message!!.startsWith("GitHub's answer could not be read"))
        }
        assertTrue(!cache.exists())
    }
}
