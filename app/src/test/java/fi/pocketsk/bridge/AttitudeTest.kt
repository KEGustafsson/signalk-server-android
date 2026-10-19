package fi.pocketsk.bridge

import fi.pocketsk.bridge.Attitude.Vec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class AttitudeTest {
    private val eps = 1e-9

    /** The rotation vector's values for a turn of [angle] about the unit [axis], as Android gives them. */
    private fun turn(axis: Vec, angle: Double): Attitude.Rotation {
        val s = sin(angle / 2)
        return Attitude.rotation(floatArrayOf((axis.x * s).toFloat(), (axis.y * s).toFloat(), (axis.z * s).toFloat(), cos(angle / 2).toFloat()))!!
    }

    private val east = Vec(1.0, 0.0, 0.0)
    private val north = Vec(0.0, 1.0, 0.0)
    private val up = Vec(0.0, 0.0, 1.0)
    private val flat = Attitude.mount(null, 0.0)

    @Test
    fun aPhoneFlatWithItsTopToTheNorthIsLevelAndHeadsNorth() {
        val e = Attitude.euler(turn(up, 0.0), flat)
        assertEquals(0.0, e.roll, eps)
        assertEquals(0.0, e.pitch, eps)
        assertEquals(0.0, e.yaw, eps)
    }

    @Test
    fun turningThePhoneCounterClockwiseTurnsTheHeadingToPort() {
        // A quarter turn counter-clockwise seen from above: the top points west.
        assertEquals(3 * PI / 2, Attitude.euler(turn(up, PI / 2), flat).yaw, 1e-6)
    }

    @Test
    fun aHeelToStarboardIsPositiveRoll() {
        // About the forward (north) axis, right-handed: the starboard (east) side goes down.
        val e = Attitude.euler(turn(north, 0.2), flat)
        assertEquals(0.2, e.roll, 1e-6)
        assertEquals(0.0, e.pitch, 1e-6)
    }

    @Test
    fun theBowGoingUpIsPositivePitch() {
        // About the starboard (east) axis, right-handed: the top, the bow, goes up.
        val e = Attitude.euler(turn(east, 0.1), flat)
        assertEquals(0.1, e.pitch, 1e-6)
        assertEquals(0.0, e.roll, 1e-6)
    }

    @Test
    fun anUprightPhoneFacingAftTakesItsBackAsForward() {
        // Upright, screen to the stern, top up, boat heading north: a quarter turn about east.
        val r = turn(east, PI / 2)
        val down = r.downInPhone
        assertEquals(-1.0, down.y, 1e-6)
        val mount = Attitude.mount(down, 0.0)
        assertEquals(-1.0, mount.forward.z, 1e-6)
        assertEquals(1.0, mount.starboard.x, 1e-6)
        val e = Attitude.euler(r, mount)
        assertEquals(0.0, e.roll, 1e-6)
        assertEquals(0.0, e.pitch, 1e-6)
        // North, which may come out as a hair under a full turn.
        assertEquals(0.0, sin(e.yaw), 1e-6)
        assertEquals(1.0, cos(e.yaw), 1e-6)
    }

    @Test
    fun levellingTakesAPhoneThatSitsTiltedAsLevel() {
        // Sitting 20° nose up in its holder while the boat lies level.
        val r = turn(east, 0.35)
        assertEquals(0.35, Attitude.euler(r, flat).pitch, 1e-6)
        val levelled = Attitude.mount(r.downInPhone, 0.0)
        val e = Attitude.euler(r, levelled)
        assertEquals(0.0, e.roll, 1e-6)
        assertEquals(0.0, e.pitch, 1e-6)
    }

    @Test
    fun theBowOffsetTurnsForwardToStarboard() {
        // The phone's top points 90° to port of the bow: the bow is the phone's right side.
        val e = Attitude.euler(turn(up, 0.0), Attitude.mount(null, PI / 2))
        assertEquals(PI / 2, e.yaw, 1e-6)
    }

    @Test
    fun rateOfTurnIsPositiveToStarboardWhateverTheMount() {
        // Counter-clockwise about up, Android's positive sense, is a turn to port.
        assertEquals(-0.1, Attitude.rateOfTurn(turn(up, 0.0), Vec(0.0, 0.0, 0.1)), 1e-6)
        // Upright, the phone's own y is up.
        assertEquals(-0.1, Attitude.rateOfTurn(turn(east, PI / 2), Vec(0.0, 0.1, 0.0)), 1e-6)
    }

    @Test
    fun aRotationVectorWithoutItsScalarPartIsCompleted() {
        val s = sin(0.1).toFloat()
        val three = Attitude.rotation(floatArrayOf(0f, 0f, s))
        val four = Attitude.rotation(floatArrayOf(0f, 0f, s, cos(0.1).toFloat()))
        assertNotNull(three)
        assertNotNull(four)
        assertEquals(Attitude.euler(four!!, flat).yaw, Attitude.euler(three!!, flat).yaw, 1e-6)
    }

    @Test
    fun valuesThatAreNoRotationGiveNone() {
        assertNull(Attitude.rotation(floatArrayOf(0f, 0f)))
        assertNull(Attitude.rotation(floatArrayOf(Float.NaN, 0f, 0f, 1f)))
        assertNull(Attitude.rotation(floatArrayOf(0f, 0f, 0f, 0f)))
    }

    @Test
    fun anglesAreKeptWithinOneTurn() {
        assertEquals(3 * PI / 2, Attitude.angle(-PI / 2), eps)
        assertEquals(0.5, Attitude.angle(2 * PI + 0.5), eps)
    }
}
