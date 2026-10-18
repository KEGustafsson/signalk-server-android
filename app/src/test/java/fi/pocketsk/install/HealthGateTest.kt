package fi.pocketsk.install

import fi.pocketsk.install.HealthGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

/** The promotion health gate of PLAN §5.2: answer within 90 s, and no crash loop for 5 min. */
class HealthGateTest {
    private val t0 = 1_000_000L
    private fun gate() = HealthGate(promotedAt = t0)
    private fun s(seconds: Long) = t0 + seconds * 1_000

    @Test
    fun pendingUntilItAnswersThenHealthyOnceTheGateCloses() {
        val gate = gate()
        assertEquals(Verdict.PENDING, gate.verdict(s(10)))
        gate.answered(s(30))
        assertEquals(Verdict.PENDING, gate.verdict(s(60)))
        assertEquals(Verdict.PENDING, gate.verdict(s(300)))
        assertEquals(Verdict.HEALTHY, gate.verdict(s(301)))
        // Healthy for good: a crash after the gate closed is the supervisor's ordinary restart.
        gate.crashed(s(400))
        gate.crashed(s(401))
        gate.crashed(s(402))
        assertEquals(Verdict.HEALTHY, gate.verdict(s(403)))
    }

    @Test
    fun noAnswerWithinNinetySecondsFails() {
        val gate = gate()
        assertEquals(Verdict.PENDING, gate.verdict(s(90)))
        assertEquals(Verdict.FAILED, gate.verdict(s(91)))
        // A late answer does not undo it.
        gate.answered(s(95))
        assertEquals(Verdict.FAILED, gate.verdict(s(400)))
    }

    @Test
    fun anAnswerTooLateDoesNotCount() {
        val gate = gate()
        gate.answered(s(120))
        assertEquals(Verdict.FAILED, gate.verdict(s(121)))
    }

    @Test
    fun threeCrashesInFiveMinutesFailEvenAfterAnAnswer() {
        val gate = gate()
        gate.answered(s(20))
        gate.crashed(s(40))
        gate.crashed(s(100))
        assertEquals(Verdict.PENDING, gate.verdict(s(110)))
        gate.crashed(s(200))
        assertEquals(Verdict.FAILED, gate.verdict(s(201)))
    }

    @Test
    fun crashesFurtherApartThanTheWindowDoNotAddUp() {
        val gate = HealthGate(promotedAt = t0, openMs = 20 * 60_000L)
        gate.answered(s(10))
        gate.crashed(s(20))
        gate.crashed(s(200))
        // The first is more than 5 min before the third: two in the window, not three.
        gate.crashed(s(400))
        assertEquals(Verdict.PENDING, gate.verdict(s(401)))
        gate.crashed(s(450))
        assertEquals(Verdict.FAILED, gate.verdict(s(451)))
    }
}
