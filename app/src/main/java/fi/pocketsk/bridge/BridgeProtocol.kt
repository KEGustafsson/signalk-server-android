package fi.pocketsk.bridge

/**
 * The bridge protocol (docs/PLAN.md §7), the app's side: newline-delimited JSON over the Unix
 * socket, one object a line, its type in `t`. The plugin's side is
 * `sk-plugin/android-bridge/lib/protocol.js`; change both together.
 *
 * App to plugin: `hello` once a connection is up, then `sensor` lines. Plugin to app:
 * `subscribe`, which names the sensors it wants and how often. Pure, so it is unit-tested.
 */
internal object BridgeProtocol {
    const val VERSION = 1

    /** Longest line either side sends or accepts. */
    const val MAX_LINE = 64 * 1024

    /** The sensors a subscribe may name, as the plugin calls them. */
    const val BATTERY = "battery"
    const val BARO = "baro"
    const val GNSS = "gnss"
    /** Roll, pitch and rate of turn. */
    const val ATTITUDE = "attitude"
    /** The magnetic heading, apart from [ATTITUDE]: a phone's compass on a boat is often wrong. */
    const val HEADING = "heading"
    /** The cabin's temperature, humidity and light, as far as the phone has the sensors. */
    const val ENVIRONMENT = "environment"
    val SENSORS = setOf(BATTERY, BARO, GNSS, ATTITUDE, HEADING, ENVIRONMENT)

    /** The barometer's averaging period, at most once a second and at least once every ten minutes. */
    const val BARO_MIN_HZ = 1.0 / 600
    const val BARO_MAX_HZ = 1.0
    const val BARO_DEFAULT_HZ = 0.1

    /** GNSS fixes, five a second at most (what phone receivers give) and one every ten seconds at least. */
    const val GNSS_MIN_HZ = 0.1
    const val GNSS_MAX_HZ = 5.0
    const val GNSS_DEFAULT_HZ = 1.0

    /** Attitude and heading, ten a second at most (the rotation sensor gives about fifty). */
    const val ATTITUDE_MIN_HZ = 0.1
    const val ATTITUDE_MAX_HZ = 10.0
    const val ATTITUDE_DEFAULT_HZ = 1.0

    /** The cabin's sensors, averaged like the barometer. */
    const val ENVIRONMENT_MIN_HZ = 1.0 / 600
    const val ENVIRONMENT_MAX_HZ = 1.0
    const val ENVIRONMENT_DEFAULT_HZ = 0.1

    /**
     * What the plugin wants: the sensors it named that the app knows, at clamped rates, and the
     * bow offset of the phone's mounting ([Attitude.mount]), in degrees, + to starboard.
     */
    data class Subscription(
        val sensors: Set<String>,
        val baroHz: Double = BARO_DEFAULT_HZ,
        val gnssHz: Double = GNSS_DEFAULT_HZ,
        val attitudeHz: Double = ATTITUDE_DEFAULT_HZ,
        val environmentHz: Double = ENVIRONMENT_DEFAULT_HZ,
        val bowOffsetDeg: Double = 0.0,
    ) {
        companion object {
            val NONE = Subscription(emptySet())
        }
    }

    /** A line from the plugin, read: a subscription, or why it was dropped. */
    sealed interface Incoming {
        data class Subscribe(val subscription: Subscription) : Incoming
        data class Dropped(val reason: String) : Incoming
    }

    fun hello(appVersion: String): String = """{"t":"hello","v":$VERSION,"app":${Json.quote(appVersion)}}"""

    /**
     * A one-number reading; null says the sensor no longer has one, which Signal K stores as null
     * rather than keeping the last value as if it were current.
     */
    fun sensor(path: String, value: Double?, src: String, ts: Long): String =
        """{"t":"sensor","path":${Json.quote(path)},"value":${value?.let(Json::number) ?: "null"},"src":${Json.quote(src)},"ts":$ts}"""

    /** A reading of several named numbers (a position, an attitude); a null one is unknown. */
    fun sensor(path: String, value: Map<String, Double?>, src: String, ts: Long): String {
        val fields = value.entries.joinToString(",") { (k, v) -> "${Json.quote(k)}:${v?.let(Json::number) ?: "null"}" }
        return """{"t":"sensor","path":${Json.quote(path)},"value":{$fields},"src":${Json.quote(src)},"ts":$ts}"""
    }

    /** Reads one line from the plugin. */
    fun parse(line: String): Incoming {
        if (line.length > MAX_LINE) return Incoming.Dropped("line too long")
        val msg = try {
            Json.parse(line)
        } catch (e: Json.ParseException) {
            return Incoming.Dropped("not JSON: ${e.message}")
        }
        if (msg !is Map<*, *>) return Incoming.Dropped("not an object")
        return when (val t = msg["t"]) {
            "subscribe" -> subscribe(msg)
            else -> Incoming.Dropped("unknown type ${(t as? String)?.take(32) ?: t}")
        }
    }

    private fun subscribe(msg: Map<*, *>): Incoming {
        val named = msg["sensors"] as? List<*> ?: return Incoming.Dropped("subscribe without a sensor list")
        val sensors = named.filterIsInstance<String>().filter { it in SENSORS }.toSet()
        val rates = msg["rateHz"] as? Map<*, *>
        return Incoming.Subscribe(
            Subscription(
                sensors,
                baroHz = rate(rates, BARO, BARO_MIN_HZ, BARO_MAX_HZ, BARO_DEFAULT_HZ),
                gnssHz = rate(rates, GNSS, GNSS_MIN_HZ, GNSS_MAX_HZ, GNSS_DEFAULT_HZ),
                attitudeHz = rate(rates, ATTITUDE, ATTITUDE_MIN_HZ, ATTITUDE_MAX_HZ, ATTITUDE_DEFAULT_HZ),
                environmentHz = rate(rates, ENVIRONMENT, ENVIRONMENT_MIN_HZ, ENVIRONMENT_MAX_HZ, ENVIRONMENT_DEFAULT_HZ),
                bowOffsetDeg = (msg["bowOffsetDeg"] as? Double)?.takeIf { it.isFinite() }?.coerceIn(-180.0, 180.0) ?: 0.0,
            ),
        )
    }

    /** A sensor's rate from the subscribe, within its bounds; its default when missing or not a rate. */
    private fun rate(rates: Map<*, *>?, sensor: String, min: Double, max: Double, default: Double): Double =
        (rates?.get(sensor) as? Double)?.takeIf { it.isFinite() && it > 0 }?.coerceIn(min, max) ?: default
}
