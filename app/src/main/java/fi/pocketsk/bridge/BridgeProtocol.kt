package fi.pocketsk.bridge

import java.util.Base64

/**
 * The bridge protocol (docs/PLAN.md §7), the app's side: newline-delimited JSON over the Unix
 * socket, one object a line, its type in `t`. The plugin's side is
 * `sk-plugin/android-bridge/lib/protocol.js`; change both together.
 *
 * App to plugin: `hello` once a connection is up, then `sensor` lines, `devices` whenever the
 * serial devices plugged into the phone change, and `serial` lines with the bytes each one sent.
 * Plugin to app: `subscribe`, which names the sensors it wants and how often, and whether and at
 * which baud rates to read the serial devices. Pure, so it is unit-tested.
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

    /** The kind of serial device a subscribe may ask the app to read: USB adapters. */
    const val USB = "usb"

    /** NMEA 0183's own rate, and the rates a subscribe may give a device; any other is the default. */
    const val DEFAULT_BAUD = 4800
    val BAUD_RATES = setOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800)

    /** Most devices a subscribe may give a rate of their own; more is a broken plugin. */
    const val MAX_DEVICE_RATES = 32

    /** A device's id: its kind, then its USB ids and what tells two of them apart ([SerialIds]). */
    private val DEVICE_ID = Regex("^[a-z]+-[a-z0-9-]{1,64}$")

    /**
     * Which serial devices the plugin wants read: USB ones or none, each at its own baud rate in
     * [bauds] when the plugin set one, else at [baud].
     */
    data class Serial(val usb: Boolean = false, val baud: Int = DEFAULT_BAUD, val bauds: Map<String, Int> = emptyMap()) {
        fun baudFor(id: String): Int = bauds[id] ?: baud

        companion object {
            val NONE = Serial()
        }
    }

    /**
     * A serial device as the plugin and the Dock see it: [id] (stable for the device, [SerialIds]),
     * its [kind] ([USB]), the [name] it gives itself, the [baud] it is read at, its [state] (one
     * of the states below) with a [detail] when it failed, and, for the Dock alone, the bytes read
     * since it was opened and where Android has it ([address], `/dev/bus/usb/...`).
     */
    data class Device(
        val id: String,
        val kind: String,
        val name: String,
        val baud: Int,
        val state: String,
        val detail: String? = null,
        val bytes: Long = 0,
        val address: String = "",
    ) {
        companion object {
            /** Open and read. */
            const val OPEN = "open"
            /** Waiting for the owner to allow the app to use it, in Android's dialog. */
            const val NEEDS_PERMISSION = "needs-permission"
            /** Plugged in, but the plugin has not asked for it: nothing would take its bytes. */
            const val IDLE = "idle"
            /** It could not be opened, or stopped answering; [detail] says why. */
            const val ERROR = "error"
        }
    }

    /**
     * What the plugin wants: the sensors it named that the app knows, at clamped rates, the bow
     * offset of the phone's mounting ([Attitude.mount]), in degrees, + to starboard, and the
     * serial devices to read.
     */
    data class Subscription(
        val sensors: Set<String>,
        val baroHz: Double = BARO_DEFAULT_HZ,
        val gnssHz: Double = GNSS_DEFAULT_HZ,
        val attitudeHz: Double = ATTITUDE_DEFAULT_HZ,
        val environmentHz: Double = ENVIRONMENT_DEFAULT_HZ,
        val bowOffsetDeg: Double = 0.0,
        val serial: Serial = Serial.NONE,
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

    /** The serial devices plugged in, as the plugin is told of them (no byte counts: they change all the time). */
    fun devices(list: List<Device>): String = list.joinToString(",", """{"t":"devices","list":[""", "]}") { d ->
        val detail = d.detail?.let { ",\"detail\":${Json.quote(it)}" } ?: ""
        """{"id":${Json.quote(d.id)},"kind":${Json.quote(d.kind)},"name":${Json.quote(d.name)},"baud":${d.baud},"state":${Json.quote(d.state)}$detail}"""
    }

    /**
     * The first [length] bytes [bytes] a serial device sent, as they came: base64, since a device
     * may send binary (an NGT-1 does), and the plugin cuts them into sentences. A read is at most
     * [MAX_SERIAL_BYTES], so the line stays well inside [MAX_LINE].
     */
    fun serial(id: String, bytes: ByteArray, length: Int): String {
        require(length in 0..MAX_SERIAL_BYTES && length <= bytes.size) { "a serial read of $length bytes" }
        val data = Base64.getEncoder().encodeToString(if (length == bytes.size) bytes else bytes.copyOf(length))
        return """{"t":"serial","id":${Json.quote(id)},"data":"$data"}"""
    }

    /** Largest read put into one `serial` line: 16 KiB is 22 KiB of base64. */
    const val MAX_SERIAL_BYTES = 16 * 1024

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
                serial = serial(msg["serial"] as? Map<*, *>),
            ),
        )
    }

    /**
     * The serial part of a subscribe: `{"usb":true,"baud":4800,"devices":{"usb-1a86-7523":38400}}`.
     * Missing or not an object: no serial devices. A rate that is not one of [BAUD_RATES], and a
     * device id that is not one, are left out rather than failing the whole subscribe.
     */
    private fun serial(msg: Map<*, *>?): Serial {
        if (msg == null) return Serial.NONE
        val bauds = (msg["devices"] as? Map<*, *>).orEmpty().entries
            .mapNotNull { (k, v) -> if (k is String && DEVICE_ID.matches(k)) baud(v)?.let { k to it } else null }
            .take(MAX_DEVICE_RATES)
            .toMap()
        return Serial(usb = msg[USB] == true, baud = baud(msg["baud"]) ?: DEFAULT_BAUD, bauds = bauds)
    }

    private fun baud(v: Any?): Int? = (v as? Double)?.takeIf { it % 1.0 == 0.0 }?.toInt()?.takeIf { it in BAUD_RATES }

    /** A sensor's rate from the subscribe, within its bounds; its default when missing or not a rate. */
    private fun rate(rates: Map<*, *>?, sensor: String, min: Double, max: Double, default: Double): Double =
        (rates?.get(sensor) as? Double)?.takeIf { it.isFinite() && it > 0 }?.coerceIn(min, max) ?: default
}
