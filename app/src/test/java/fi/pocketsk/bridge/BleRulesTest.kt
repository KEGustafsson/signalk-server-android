package fi.pocketsk.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Bluetooth LE scanner's two rules: Android's start budget, and one packet passed on once. */
class BleRulesTest {
    @Test
    fun startsKeepWithinTheBudget() {
        val b = ScanBudget(starts = 4, windowMs = 30_000)
        for (t in listOf(0L, 1_000, 2_000, 3_000)) {
            assertEquals(0L, b.wait(t))
            b.started(t)
        }
        assertEquals("the fifth waits for the first to leave the window", 26_000L, b.wait(4_000))
        assertEquals(0L, b.wait(30_000))
        b.started(30_000)
        assertEquals(1_000L, b.wait(30_000))
    }

    @Test
    fun theDefaultsLeaveOneStartOfAndroidsFive() {
        assertEquals(4, ScanBudget.MAX_STARTS)
        assertEquals(30_000L, ScanBudget.WINDOW_MS)
    }

    @Test
    fun aPacketHeardTwiceAtOnceIsPassedOnOnce() {
        val r = Repeats(windowMs = 500, max = 3)
        assertTrue(r.fresh("A", 1, 0))
        assertFalse("the other scan's copy", r.fresh("A", 1, 100))
        assertTrue("other bytes", r.fresh("A", 2, 150))
        assertTrue("the same bytes again later", r.fresh("A", 2, 700))
        assertTrue("another device", r.fresh("B", 2, 700))
    }

    @Test
    fun theOldestDeviceIsForgottenPastMax() {
        val r = Repeats(windowMs = 500, max = 2)
        r.fresh("A", 1, 0)
        r.fresh("B", 1, 0)
        r.fresh("C", 1, 0)
        assertTrue("A was forgotten, so its copy is fresh again", r.fresh("A", 1, 10))
        assertFalse(r.fresh("C", 1, 10))
    }
}
