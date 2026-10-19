package fi.pocketsk.bridge

import kotlin.math.abs

/**
 * What the phone's sensors report, in Signal K's units (SI: ratios, volts, kelvin, pascals),
 * and when a reading is worth a line on the socket. Pure, so the conversions are unit-tested;
 * [PhoneSensors] feeds it from the platform.
 */
internal object Readings {

    /** Where the phone's own battery lives in the Signal K tree. */
    const val BATTERY_PATH = "electrical.batteries.phone"
    const val PRESSURE_PATH = "environment.outside.pressure"
    const val POSITION_PATH = "navigation.position"
    const val SOG_PATH = "navigation.speedOverGround"
    const val COG_PATH = "navigation.courseOverGroundTrue"
    const val SRC_BATTERY = "android.battery"
    const val SRC_BARO = "android.baro"
    const val SRC_GNSS = "android.gnss"

    /** The battery is told at most this often, however often Android announces a change. */
    const val BATTERY_MIN_GAP_MS = 10_000L

    /** And at least this often, so a quiet battery does not go stale in the server. */
    const val BATTERY_MAX_GAP_MS = 300_000L

    /** One look at the battery, from ACTION_BATTERY_CHANGED's extras, in the platform's units. */
    data class Battery(val level: Int, val scale: Int, val voltageMv: Int, val tenthsCelsius: Int?)

    /** The battery as Signal K paths and values; what the platform did not report is left out. */
    fun battery(b: Battery): Map<String, Double> = buildMap {
        if (b.level >= 0 && b.scale > 0) put("$BATTERY_PATH.capacity.stateOfCharge", (b.level.toDouble() / b.scale).coerceIn(0.0, 1.0))
        if (b.voltageMv > 0) put("$BATTERY_PATH.voltage", b.voltageMv / 1000.0)
        b.tenthsCelsius?.let { put("$BATTERY_PATH.temperature", it / 10.0 + 273.15) }
    }

    /**
     * Whether a battery reading goes out now: at most every [BATTERY_MIN_GAP_MS], and then when
     * something changed (a percent, 10 mV, half a degree) or [BATTERY_MAX_GAP_MS] has passed.
     */
    fun batteryDue(last: Map<String, Double>?, lastAtMs: Long, now: Map<String, Double>, nowMs: Long): Boolean {
        if (last == null) return true
        val gap = nowMs - lastAtMs
        if (gap < BATTERY_MIN_GAP_MS) return false
        if (gap >= BATTERY_MAX_GAP_MS) return true
        if (last.keys != now.keys) return true
        return now.any { (k, v) ->
            val step = when {
                k.endsWith("stateOfCharge") -> 0.01
                k.endsWith("voltage") -> 0.01
                else -> 0.5
            }
            abs(v - last.getValue(k)) >= step - 1e-9
        }
    }

    /**
     * One GNSS fix as the platform's Location reports it: degrees, metres, m/s; speed, bearing
     * and altitude only when the fix has them.
     */
    data class Fix(
        val latitude: Double,
        val longitude: Double,
        val altitudeM: Double? = null,
        val speedMs: Float? = null,
        val bearingDeg: Float? = null,
    )

    /** A fix in Signal K's terms: the position, and speed and course over ground where known. */
    data class Gnss(val position: Map<String, Double?>, val speedOverGround: Double?, val courseOverGroundTrue: Double?)

    /**
     * The fix as Signal K wants it: the position with altitude only when known, speed in m/s,
     * course in radians in [0, 2π). A fix with a latitude or longitude outside the Earth is no
     * fix and gives null; a speed or bearing that is not a number is left out, not passed on.
     */
    fun gnss(f: Fix): Gnss? {
        if (!f.latitude.isFinite() || !f.longitude.isFinite()) return null
        if (f.latitude !in -90.0..90.0 || f.longitude !in -180.0..180.0) return null
        val position = linkedMapOf<String, Double?>("latitude" to f.latitude, "longitude" to f.longitude)
        f.altitudeM?.takeIf { it.isFinite() }?.let { position["altitude"] = it }
        val speed = f.speedMs?.takeIf { it.isFinite() && it >= 0f }?.toDouble()
        val course = f.bearingDeg?.takeIf { it.isFinite() }?.let { Math.toRadians((((it % 360f) + 360f) % 360f).toDouble()) }
        return Gnss(position, speed, course)
    }

    /**
     * The barometer's samples averaged over one period: the sensor reports several times a second,
     * and one number a period is what a pressure trend needs. In hPa from the platform, pascals out.
     */
    class PressureAverage {
        private var sum = 0.0
        private var count = 0

        fun add(hPa: Float) {
            if (hPa.isFinite() && hPa in 300f..1100f) {
                sum += hPa
                count++
            }
        }

        /** The mean of the period in pascals, or null when it had no plausible sample; starts the next. */
        fun take(): Double? {
            val mean = if (count == 0) null else sum / count * 100.0
            sum = 0.0
            count = 0
            return mean
        }
    }
}
