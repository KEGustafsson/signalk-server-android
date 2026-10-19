package fi.pocketsk.bridge

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The boat's attitude from the phone's rotation sensor, wherever the phone is mounted. Pure, so
 * the geometry is unit-tested; [PhoneSensors] feeds it from the platform.
 *
 * Android's rotation vector says how the phone lies in a world frame of east, north and up. The
 * boat's attitude needs the boat's own axes instead: forward, starboard and down, the frame
 * Signal K's roll (+ to starboard), pitch (+ bow up) and yaw (+ to starboard) are angles in. The
 * phone is fixed somewhere on board in some orientation, so the boat's axes are fixed vectors in
 * the phone's frame, a [Mount]. It is found from one look at gravity while the boat lies level
 * ("level now", docs/PLAN.md §8.1), which says where down is, and the rule that the boat's
 * forward is where the top of the phone points, or the back of the phone where the phone stands
 * upright; a bow offset turns it from there. Without a look at gravity the phone is taken to
 * lie flat, screen up.
 */
internal object Attitude {

    /** A vector of three, in whatever frame its user says. */
    data class Vec(val x: Double, val y: Double, val z: Double) {
        operator fun plus(o: Vec) = Vec(x + o.x, y + o.y, z + o.z)
        operator fun minus(o: Vec) = Vec(x - o.x, y - o.y, z - o.z)
        operator fun times(k: Double) = Vec(x * k, y * k, z * k)
        infix fun dot(o: Vec) = x * o.x + y * o.y + z * o.z
        infix fun cross(o: Vec) = Vec(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
        val length get() = sqrt(this dot this)
        fun unit(): Vec? = length.takeIf { it > 1e-9 && it.isFinite() }?.let { this * (1 / it) }
    }

    /**
     * The phone's orientation as Android's SensorManager.getRotationMatrixFromVector gives it:
     * row-major, world (east, north, up) = R × phone. Written out here so it can be tested.
     */
    class Rotation(private val m: DoubleArray) {
        /** A phone-frame vector in the world frame. */
        fun toWorld(v: Vec) = Vec(
            m[0] * v.x + m[1] * v.y + m[2] * v.z,
            m[3] * v.x + m[4] * v.y + m[5] * v.z,
            m[6] * v.x + m[7] * v.y + m[8] * v.z,
        )

        /** Which way down is, in the phone's frame: the transpose applied to the world's (0, 0, -1). */
        val downInPhone get() = Vec(-m[6], -m[7], -m[8])
    }

    /**
     * The rotation from a rotation vector's values: x, y, z of the unit quaternion and, where
     * the sensor gives it, its scalar part (computed when it does not, as Android does). Null for
     * values that are not a rotation.
     */
    fun rotation(values: FloatArray): Rotation? {
        if (values.size < 3) return null
        val x = values[0].toDouble()
        val y = values[1].toDouble()
        val z = values[2].toDouble()
        val w = if (values.size >= 4) values[3].toDouble() else sqrt((1 - x * x - y * y - z * z).coerceAtLeast(0.0))
        val n = sqrt(x * x + y * y + z * z + w * w)
        if (!n.isFinite() || n < 0.5) return null
        val (qx, qy, qz, qw) = listOf(x / n, y / n, z / n, w / n)
        return Rotation(
            doubleArrayOf(
                1 - 2 * (qy * qy + qz * qz), 2 * (qx * qy - qz * qw), 2 * (qx * qz + qy * qw),
                2 * (qx * qy + qz * qw), 1 - 2 * (qx * qx + qz * qz), 2 * (qy * qz - qx * qw),
                2 * (qx * qz - qy * qw), 2 * (qy * qz + qx * qw), 1 - 2 * (qx * qx + qy * qy),
            ),
        )
    }

    /** The boat's forward, starboard and down axes, each a unit vector in the phone's frame. */
    data class Mount(val forward: Vec, val starboard: Vec, val down: Vec)

    /** Down for a phone lying flat, screen up: out of its back. */
    val FLAT = Vec(0.0, 0.0, -1.0)

    /**
     * The mount from where down was when the boat lay level ([down], phone frame; [FLAT] when
     * never levelled) and the bow offset ([bowOffsetRad], + turns forward to starboard). Forward
     * is the top of the phone (+y) laid into the level plane; when the top points within 30° of
     * straight up or down, as in an upright holder, it is the back of the phone (-z) instead.
     */
    fun mount(down: Vec?, bowOffsetRad: Double): Mount {
        val d = down?.unit() ?: FLAT
        val top = Vec(0.0, 1.0, 0.0)
        val candidate = if (abs(top dot d) < cos(PI / 6)) top else Vec(0.0, 0.0, -1.0)
        val ahead = (candidate - d * (candidate dot d)).unit() ?: Vec(1.0, 0.0, 0.0)
        // Turned about down by the offset: positive, clockwise seen from above, to starboard.
        val side = d cross ahead
        val forward = (ahead * cos(bowOffsetRad) + side * sin(bowOffsetRad)).unit() ?: ahead
        return Mount(forward, d cross forward, d)
    }

    /** Roll, pitch and yaw in radians, Signal K's signs; yaw from north, in [0, 2π). */
    data class Euler(val roll: Double, val pitch: Double, val yaw: Double)

    /**
     * The boat's attitude from the phone's [r] and its [mount]: the boat's axes taken into the
     * world, east-north-up turned into north-east-down, and the ZYX angles read off them. Yaw is
     * from whatever north the rotation sensor knows: magnetic for the rotation vector, an
     * arbitrary one for the game rotation vector, which has no magnetometer.
     */
    fun euler(r: Rotation, mount: Mount): Euler {
        val f = ned(r.toWorld(mount.forward))
        val s = ned(r.toWorld(mount.starboard))
        val d = ned(r.toWorld(mount.down))
        val pitch = -asin(f.z.coerceIn(-1.0, 1.0))
        val roll = atan2(s.z, d.z)
        return Euler(roll, pitch, angle(atan2(f.y, f.x)))
    }

    /**
     * How fast the boat turns about the vertical, rad/s, + to starboard (Signal K's
     * rateOfTurn), from the gyroscope's rates about the phone's own axes. The mount does not
     * matter: the phone's rates are taken into the world, where up is up however it lies.
     * Android's rates are counter-clockwise positive, so a turn to port is positive about up.
     */
    fun rateOfTurn(r: Rotation, gyro: Vec): Double = -r.toWorld(gyro).z

    /** An angle in [0, 2π). */
    fun angle(a: Double): Double = ((a % (2 * PI)) + 2 * PI) % (2 * PI)

    private fun ned(enu: Vec) = Vec(enu.y, enu.x, -enu.z)
}
