package fi.pocketsk.server

import fi.pocketsk.server.RestartPolicy.Decision
import org.junit.Assert.assertEquals
import org.junit.Test

/** The supervisor's restart rules of PLAN §6: restart, back off, give up. */
class RestartPolicyTest {
    private val s = 1_000L
    private val min = 60_000L

    @Test
    fun aCleanExitIsSignalKsOwnRestart() {
        val policy = RestartPolicy()
        repeat(20) { i ->
            val start = i * 10 * min
            assertEquals(Decision.Restart(0), policy.exited(0, start, start + min))
        }
    }

    @Test
    fun aCleanExitStraightAfterTheStartIsAFailure() {
        val policy = RestartPolicy()
        assertEquals(Decision.Restart(2 * s), policy.exited(0, 0, 1 * s))
    }

    @Test
    fun failuresBackOffFromTwoSecondsToSixty() {
        val policy = RestartPolicy(failureLimit = 100)
        var t = 0L
        val delays = (1..8).map {
            val d = policy.exited(1, t, t + 30 * s) as Decision.Restart
            t += 30 * s + d.delayMs
            d.delayMs / s
        }
        assertEquals(listOf(2L, 4, 8, 16, 32, 60, 60, 60), delays)
    }

    @Test
    fun fiveFailuresInTenMinutesGiveUp() {
        val policy = RestartPolicy()
        var t = 0L
        repeat(4) {
            val d = policy.exited(1, t, t + 10 * s)
            assertEquals(Decision.Restart::class, d::class)
            t += 10 * s + (d as Decision.Restart).delayMs
        }
        assertEquals(Decision.GiveUp, policy.exited(1, t, t + 10 * s))
    }

    @Test
    fun failuresFurtherApartThanTheWindowDoNotAddUp() {
        val policy = RestartPolicy()
        // A crash every three minutes: never five within ten, so it never gives up.
        var t = 0L
        repeat(12) {
            val d = policy.exited(1, t, t + 3 * min)
            assertEquals(Decision.Restart::class, d::class)
            t += 3 * min + (d as Decision.Restart).delayMs
        }
    }

    @Test
    fun aLongRunResetsTheBackoff() {
        val policy = RestartPolicy()
        assertEquals(Decision.Restart(2 * s), policy.exited(1, 0, 10 * s))
        assertEquals(Decision.Restart(4 * s), policy.exited(1, 12 * s, 20 * s))
        // Up for eleven minutes: working, so its crash starts the backoff again.
        assertEquals(Decision.Restart(2 * s), policy.exited(1, 24 * s, 24 * s + 11 * min))
    }

    @Test
    fun resetForgetsEverything() {
        val policy = RestartPolicy()
        repeat(4) { policy.exited(1, it * s, it * s + s) }
        policy.reset()
        assertEquals(Decision.Restart(2 * s), policy.exited(1, 10 * s, 11 * s))
    }
}
