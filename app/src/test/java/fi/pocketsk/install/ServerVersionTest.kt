package fi.pocketsk.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parsing npm's version strings and ordering them the way semver does. */
class ServerVersionTest {
    private fun v(text: String) = requireNotNull(ServerVersion.parse(text)) { text }

    @Test
    fun parsesReleasesAndPrereleases() {
        val release = v("2.33.0")
        assertEquals(listOf(2, 33, 0), listOf(release.major, release.minor, release.patch))
        assertTrue(release.isStable)
        val beta = v("2.34.0-beta.1")
        assertEquals(listOf("beta", "1"), beta.prerelease)
        assertFalse(beta.isStable)
        assertEquals("2.34.0-beta.1", beta.toString())
    }

    @Test
    fun refusesWhatNpmWouldNotCallAVersion() {
        for (bad in listOf("", "2", "2.33", "2.33.0.1", "02.1.0", "2.x.0", "2.33.0-", "2.33.0-beta..1", "latest")) {
            assertNull(bad, ServerVersion.parse(bad))
        }
    }

    @Test
    fun ordersNumericallyNotAsText() {
        assertTrue(v("2.10.0") > v("2.9.0"))
        assertTrue(v("10.0.0") > v("9.99.99"))
        assertTrue(v("2.33.1") > v("2.33.0"))
    }

    @Test
    fun aReleaseSortsAfterItsPrereleases() {
        assertTrue(v("2.34.0") > v("2.34.0-rc.1"))
        assertTrue(v("2.34.0-rc.1") > v("2.34.0-beta.11"))
        assertTrue(v("2.34.0-beta.11") > v("2.34.0-beta.2"))
        assertTrue(v("2.34.0-beta.1") > v("2.34.0-beta"))
        // Numeric identifiers sort before alphanumeric ones.
        assertTrue(v("1.0.0-alpha") > v("1.0.0-1"))
    }

    @Test
    fun buildMetadataDoesNotOrder() {
        assertEquals(0, v("1.0.0+build.5").compareTo(v("1.0.0+other")))
    }

    @Test
    fun newestFirstLeavesBetasOutUnlessAsked() {
        val published = listOf("2.32.0", "2.34.0-beta.1", "2.33.0", "junk", "2.33.1")
        assertEquals(listOf("2.33.1", "2.33.0", "2.32.0"), ServerVersion.newestFirst(published).map { it.text })
        assertEquals(
            listOf("2.34.0-beta.1", "2.33.1", "2.33.0", "2.32.0"),
            ServerVersion.newestFirst(published, withPrereleases = true).map { it.text },
        )
    }
}
