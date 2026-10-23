package fi.pocketsk.bridge

import kotlin.math.abs

/**
 * What the phone's sensors report, in Signal K's units (SI: ratios, volts, kelvin, pascals; a
 * cell's power in dBm, as every phone and modem gives it), and when a reading is worth a line on
 * the socket. Pure, so the conversions are unit-tested; [PhoneSensors] feeds it from the platform.
 */
internal object Readings {

    /** Where the phone's own battery lives in the Signal K tree. */
    const val BATTERY_PATH = "electrical.batteries.phone"
    const val PRESSURE_PATH = "environment.outside.pressure"
    const val POSITION_PATH = "navigation.position"
    const val SOG_PATH = "navigation.speedOverGround"
    const val COG_PATH = "navigation.courseOverGroundTrue"
    const val SATELLITES_PATH = "navigation.gnss.satellites"
    const val ATTITUDE_PATH = "navigation.attitude"
    const val RATE_OF_TURN_PATH = "navigation.rateOfTurn"
    const val HEADING_MAGNETIC_PATH = "navigation.headingMagnetic"
    const val INSIDE_TEMPERATURE_PATH = "environment.inside.temperature"
    const val INSIDE_HUMIDITY_PATH = "environment.inside.relativeHumidity"
    const val INSIDE_ILLUMINANCE_PATH = "environment.inside.illuminance"
    /** The cellular network the phone is on: Signal K names no such group, so it is the plan's own (§7.1). */
    const val CELLULAR_PATH = "communication.cellular"
    const val CELLULAR_STRENGTH_PATH = "$CELLULAR_PATH.signalStrength"
    const val CELLULAR_DBM_PATH = "$CELLULAR_PATH.dbm"
    const val CELLULAR_OPERATOR_PATH = "$CELLULAR_PATH.operator"
    const val CELLULAR_ROAMING_PATH = "$CELLULAR_PATH.roaming"
    const val SRC_BATTERY = "android.battery"
    const val SRC_BARO = "android.baro"
    const val SRC_GNSS = "android.gnss"
    /** Roll, pitch and rate of turn: the rotation sensor and the gyroscope. */
    const val SRC_IMU = "android.imu"
    /** The magnetic heading: the rotation sensor with the magnetometer, a source of its own so a server can rank it below a real compass. */
    const val SRC_COMPASS = "android.compass"
    const val SRC_ENVIRONMENT = "android.environment"
    const val SRC_CELLULAR = "android.cellular"

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
     * The boat's attitude as Signal K's `navigation.attitude`: roll and pitch, and yaw only where
     * it has a north to be measured from ([withYaw]: the rotation sensor with the magnetometer).
     */
    fun attitude(e: Attitude.Euler, withYaw: Boolean): Map<String, Double?> =
        if (withYaw) linkedMapOf("roll" to e.roll, "pitch" to e.pitch, "yaw" to e.yaw) else linkedMapOf("roll" to e.roll, "pitch" to e.pitch)

    /** The ambient temperature sensor's °C as kelvin; null for what no cabin reads. */
    fun cabinTemperature(celsius: Double): Double? = celsius.takeIf { it.isFinite() && it in -40.0..85.0 }?.let { it + 273.15 }

    /** The humidity sensor's percent as Signal K's ratio; null outside 0–100 %. */
    fun cabinHumidity(percent: Double): Double? = percent.takeIf { it.isFinite() && it in 0.0..100.0 }?.let { it / 100.0 }

    /** The light sensor's lux, as they are; null for a reading no light gives. */
    fun cabinLight(lux: Double): Double? = lux.takeIf { it.isFinite() && it in 0.0..200_000.0 }

    /**
     * The signal's bars as Android counts them (SignalStrength.getLevel: 0, none or unknown, to 4,
     * great) as Signal K's ratio. Null for a level Android does not give, and for a report that
     * measured no cell at all ([cells] empty): its level 0 then means unknown, not no signal.
     */
    fun cellularStrength(level: Int, cells: List<Cell>): Double? =
        level.takeIf { it in 0..MAX_CELL_LEVEL && cells.isNotEmpty() }?.let { it.toDouble() / MAX_CELL_LEVEL }

    /** One cellular reading as it is sent; null where there is nothing to say ([NONE]: no reading at all). */
    data class Cellular(val strength: Double?, val dbm: Double?, val operator: String?, val roaming: Boolean?) {
        companion object {
            val NONE = Cellular(null, null, null, null)
        }
    }

    /** One cell the modem hears, as CellSignalStrength reports it: its bars and its power in dBm. */
    data class Cell(val level: Int, val dbm: Int)

    /**
     * The power of the cell the phone is best served by, in dBm: the one with the most bars, the
     * stronger of two alike. Android gives CellInfo.UNAVAILABLE (Int.MAX_VALUE) where a radio cannot
     * tell, and a figure outside what any cell gives is left out with it; null when none is left.
     */
    fun cellularDbm(cells: List<Cell>): Double? =
        cells.filter { it.dbm in MIN_DBM..MAX_DBM }.maxWithOrNull(compareBy({ it.level }, { it.dbm }))?.dbm?.toDouble()

    /** Bars a cell may have: SignalStrength's SIGNAL_STRENGTH_GREAT. */
    private const val MAX_CELL_LEVEL = 4
    /** The weakest and the strongest power a phone reports for a cell, with room to spare (3GPP's RSRP runs -140 to -44). */
    private const val MIN_DBM = -150
    private const val MAX_DBM = -20

    /**
     * The mean of a period's samples, for the readings that come many times a second and are
     * worth one number a period (rate of turn, the cabin's sensors). What [add] is given has been
     * checked by its caller.
     */
    class Mean {
        private var sum = 0.0
        private var count = 0

        fun add(v: Double) {
            if (!v.isFinite()) return
            sum += v
            count++
        }

        /** The mean, or null when the period had no sample; starts the next. */
        fun take(): Double? {
            val mean = if (count == 0) null else sum / count
            sum = 0.0
            count = 0
            return mean
        }
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
