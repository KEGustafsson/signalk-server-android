package fi.pocketsk.install

import fi.pocketsk.install.InstallHistory.Entry
import fi.pocketsk.install.InstallHistory.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The version the Updates card offers. */
class UpdateOfferTest {
    private fun versions(vararg texts: String) = ServerVersion.newestFirst(texts.toList(), withPrereleases = true)
    private val none = InstallHistory()

    @Test
    fun offersTheNewestNewerThanTheLiveOne() {
        assertEquals("2.34.0", UpdateOffer.pick(versions("2.32.0", "2.33.0", "2.34.0"), "2.33.0", none)?.text)
    }

    @Test
    fun nothingWhenTheLiveOneIsTheNewest() {
        assertNull(UpdateOffer.pick(versions("2.32.0", "2.33.0"), "2.33.0", none))
        assertNull(UpdateOffer.pick(emptyList(), "2.33.0", none))
    }

    @Test
    fun skipsAVersionTheHealthGateRolledBack() {
        val history = none + Entry(1, "2.34.0", "B", "rollback", Outcome.BAD)
        assertEquals("2.33.1", UpdateOffer.pick(versions("2.33.0", "2.33.1", "2.34.0"), "2.33.0", history)?.text)
        // Rolled back by hand is no verdict on the version: still offered.
        val byHand = none + Entry(1, "2.34.0", "B", "rollback", Outcome.ROLLED_BACK)
        assertEquals("2.34.0", UpdateOffer.pick(versions("2.33.0", "2.34.0"), "2.33.0", byHand)?.text)
    }

    @Test
    fun anUnreadableOrMissingLiveVersionGetsTheNewest() {
        assertEquals("2.34.0", UpdateOffer.pick(versions("2.33.0", "2.34.0"), "local-build", none)?.text)
        assertEquals("2.34.0", UpdateOffer.pick(versions("2.33.0", "2.34.0"), null, none)?.text)
    }

    @Test
    fun neverOffersABetaOrTestRelease() {
        assertEquals("2.34.0", UpdateOffer.pick(versions("2.34.0", "2.35.0-beta.1", "2.3.0-canboatv2.1"), "2.33.0", none)?.text)
        assertNull(UpdateOffer.pick(versions("2.33.0", "2.34.0-beta.2"), "2.33.0", none))
        // Running a beta, its release is newer and offered.
        assertEquals("2.34.0", UpdateOffer.pick(versions("2.34.0-beta.2", "2.34.0"), "2.34.0-beta.2", none)?.text)
    }
}
