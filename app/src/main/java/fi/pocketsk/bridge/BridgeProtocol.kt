package fi.pocketsk.bridge

import java.util.Base64

/**
 * The bridge protocol (docs/PLAN.md §7), the app's side: newline-delimited JSON over the Unix
 * socket, one object a line, its type in `t`. The plugin's side is
 * `sk-plugin/android-bridge/lib/protocol.js`; change both together.
 *
 * App to plugin: `hello` once a connection is up, then `sensor` lines (a number, an object of
 * numbers, a short text or a yes or no; null when there is no reading now), `devices` whenever the
 * serial devices plugged into the phone change, `serial` lines with the bytes each one sent, and
 * `silence` when the owner silences an alarm on the phone.
 * Plugin to app: `subscribe`, which names the sensors it wants and how often, and whether and at
 * which baud rates to read the serial devices, `alarms`, the boat's alarms whenever they
 * change, and `stats`, the server's own delta rate, for the Dock. Pure, so it is unit-tested.
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
    /** The cellular network: signal strength, the operator and whether the phone roams. */
    const val CELLULAR = "cellular"
    val SENSORS = setOf(BATTERY, BARO, GNSS, ATTITUDE, HEADING, ENVIRONMENT, CELLULAR)

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

    /** The cellular reading, a look at the modem's last report: at most once a second, by default every 30 s. */
    const val CELLULAR_MIN_HZ = 1.0 / 600
    const val CELLULAR_MAX_HZ = 1.0
    const val CELLULAR_DEFAULT_HZ = 1.0 / 30

    /** Longest text a sensor line carries (an operator's name), as [sensorText] cuts it. */
    const val MAX_TEXT = 64

    /** The kinds of serial device a subscribe may ask the app to read: USB adapters, Bluetooth SPP ones. */
    const val USB = "usb"
    const val BLUETOOTH = "bt"

    /** NMEA 0183's own rate, and the rates a subscribe may give a device; any other is the default. */
    const val DEFAULT_BAUD = 4800
    val BAUD_RATES = setOf(1200, 2400, 4800, 9600, 19200, 38400, 57600, 115200, 230400, 460800)

    /** Most devices a subscribe may give a rate of their own; more is a broken plugin. */
    const val MAX_DEVICE_RATES = 32

    /** A device's id: its kind, then its USB ids and what tells two of them apart ([SerialIds]). */
    private val DEVICE_ID = Regex("^[a-z]+-[a-z0-9-]{1,64}$")

    /**
     * Which serial devices the plugin wants read: USB ones, each at its own baud rate in [bauds]
     * when the plugin set one, else at [baud], and the Bluetooth ones the owner chose in the Dock
     * (a Bluetooth link has no baud rate of its own to set).
     */
    data class Serial(
        val usb: Boolean = false,
        val baud: Int = DEFAULT_BAUD,
        val bauds: Map<String, Int> = emptyMap(),
        val bluetooth: Boolean = false,
    ) {
        fun baudFor(id: String): Int = bauds[id] ?: baud

        companion object {
            val NONE = Serial()
        }
    }

    /**
     * A serial device as the plugin and the Dock see it: [id] (stable for the device, [SerialIds]),
     * its [kind] ([USB], [BLUETOOTH]), the [name] it gives itself, the [baud] it is read at (0 for
     * Bluetooth), its [state] (one of the states below) with a [detail] when it failed, and, for the
     * Dock alone, the bytes read since it was opened, where Android has it ([address], a USB device
     * path or a Bluetooth address) and whether the owner [chosen] it (a paired Bluetooth device is
     * listed for the Dock to offer, and read only once chosen; a USB device always is).
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
        val chosen: Boolean = true,
    ) {
        companion object {
            /** Open and read. */
            const val OPEN = "open"
            /** Waiting for the owner to allow the app to use it, in Android's dialog. */
            const val NEEDS_PERMISSION = "needs-permission"
            /** Being dialled, or dialled again after its link dropped (Bluetooth). */
            const val CONNECTING = "connecting"
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
        val cellularHz: Double = CELLULAR_DEFAULT_HZ,
        val bowOffsetDeg: Double = 0.0,
        val serial: Serial = Serial.NONE,
    ) {
        companion object {
            val NONE = Subscription(emptySet())
        }
    }

    /** The notification states an alarm may be in, least urgent first; the plugin sends no others. */
    val ALARM_STATES = listOf("alert", "warn", "alarm", "emergency")

    /** Most alarms in one list, as the plugin cuts it; more is a broken plugin. */
    const val MAX_ALARMS = 32

    /** Longest alarm message kept, as the plugin cuts it. */
    private const val MAX_MESSAGE = 200

    /**
     * An alarm's id: the server's notification id (a UUID), or its path where the server gives
     * none. A path may carry a colon: a closest-approach alarm names the other vessel by its URN
     * (`notifications.navigation.closestApproach.urn:mrn:imo:mmsi:230099999`).
     */
    private val ALARM_ID = Regex("^[A-Za-z0-9._:-]{1,256}$")
    private val NOTIFICATION_PATH = Regex("^notifications(\\.[A-Za-z0-9_:-]+)+$")

    /**
     * One of the boat's alarms, a Signal K notification: [id] to silence it by, its [path], its
     * [state] (one of [ALARM_STATES]), the [message] to show, whether the server still wants it
     * heard ([sound]: its method has sound, so not yet silenced), and what the server allows done
     * to it from the phone ([action]: [SILENCE], [ACKNOWLEDGE], or null when nothing, and then
     * Silence quiets the phone alone).
     */
    data class Alarm(
        val id: String,
        val path: String,
        val state: String,
        val message: String,
        val sound: Boolean,
        val action: String?,
    ) {
        /** How urgent: the higher, the more. */
        val rank: Int get() = ALARM_STATES.indexOf(state)

        companion object {
            const val SILENCE = "silence"
            const val ACKNOWLEDGE = "acknowledge"
        }
    }

    /** Most deltas a second a stats line may give; more is a broken plugin, not a boat. */
    const val MAX_DELTA_RATE = 1_000_000.0

    /** A line from the plugin, read: a subscription, the alarms, the server's stats, or why it was dropped. */
    sealed interface Incoming {
        data class Subscribe(val subscription: Subscription) : Incoming
        data class Alarms(val list: List<Alarm>) : Incoming
        /** The deltas a second the server took in, as its own statistics count them. */
        data class Stats(val deltaRate: Double) : Incoming
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

    /**
     * A reading that is a name (the cellular operator's): cleared of control characters and runs
     * of white space, and cut to [MAX_TEXT] without splitting a character in two; null, or nothing
     * left, says there is none now.
     */
    fun sensorText(path: String, value: String?, src: String, ts: Long): String {
        val text = value?.map { if (it.isISOControl()) ' ' else it }?.joinToString("")
            ?.trim()?.replace(SPACES, " ")?.take(MAX_TEXT)
            ?.let { if (it.isNotEmpty() && it.last().isHighSurrogate()) it.dropLast(1) else it }
            ?.trim()?.takeIf { it.isNotEmpty() }
        return """{"t":"sensor","path":${Json.quote(path)},"value":${text?.let(Json::quote) ?: "null"},"src":${Json.quote(src)},"ts":$ts}"""
    }

    /** A reading that is yes or no (whether the phone roams); null when it cannot be told now. */
    fun sensorFlag(path: String, value: Boolean?, src: String, ts: Long): String =
        """{"t":"sensor","path":${Json.quote(path)},"value":$value,"src":${Json.quote(src)},"ts":$ts}"""

    private val SPACES = Regex("\\s+")

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

    /** The owner silenced the alarm [id] on the phone: the plugin silences it on the server as it may. */
    fun silence(id: String): String = """{"t":"silence","id":${Json.quote(id)}}"""

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
            "alarms" -> alarms(msg)
            "stats" -> (msg["deltaRate"] as? Double)?.takeIf { it.isFinite() && it in 0.0..MAX_DELTA_RATE }
                ?.let { Incoming.Stats(it) } ?: Incoming.Dropped("stats without a delta rate")
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
                cellularHz = rate(rates, CELLULAR, CELLULAR_MIN_HZ, CELLULAR_MAX_HZ, CELLULAR_DEFAULT_HZ),
                bowOffsetDeg = (msg["bowOffsetDeg"] as? Double)?.takeIf { it.isFinite() }?.coerceIn(-180.0, 180.0) ?: 0.0,
                serial = serial(msg["serial"] as? Map<*, *>),
            ),
        )
    }

    /**
     * The alarm list. An entry that is not an alarm is left out rather than losing the others: an
     * alarm is the one message here a phone must not miss. A message is cleared of control
     * characters, since it is shown on the lock screen, and cut.
     */
    private fun alarms(msg: Map<*, *>): Incoming {
        val list = msg["list"] as? List<*> ?: return Incoming.Dropped("alarms without a list")
        if (list.size > MAX_ALARMS) return Incoming.Dropped("alarms with ${list.size} entries")
        return Incoming.Alarms(
            list.mapNotNull { a ->
                if (a !is Map<*, *>) return@mapNotNull null
                val id = (a["id"] as? String)?.takeIf { ALARM_ID.matches(it) } ?: return@mapNotNull null
                val path = (a["path"] as? String)?.takeIf { it.length <= 256 && NOTIFICATION_PATH.matches(it) } ?: return@mapNotNull null
                val state = (a["state"] as? String)?.takeIf { it in ALARM_STATES } ?: return@mapNotNull null
                val message = (a["message"] as? String).orEmpty().map { if (it.isISOControl()) ' ' else it }
                    .joinToString("").trim().take(MAX_MESSAGE)
                val action = (a["action"] as? String)?.takeIf { it == Alarm.SILENCE || it == Alarm.ACKNOWLEDGE }
                Alarm(id, path, state, message, sound = a["sound"] != false, action = action)
            },
        )
    }

    /**
     * The serial part of a subscribe: `{"usb":true,"baud":4800,"devices":{"usb-1a86-7523":38400},"bt":true}`.
     * Missing or not an object: no serial devices. A rate that is not one of [BAUD_RATES], and a
     * device id that is not one, are left out rather than failing the whole subscribe.
     */
    private fun serial(msg: Map<*, *>?): Serial {
        if (msg == null) return Serial.NONE
        val bauds = (msg["devices"] as? Map<*, *>).orEmpty().entries
            .mapNotNull { (k, v) -> if (k is String && DEVICE_ID.matches(k)) baud(v)?.let { k to it } else null }
            .take(MAX_DEVICE_RATES)
            .toMap()
        return Serial(usb = msg[USB] == true, baud = baud(msg["baud"]) ?: DEFAULT_BAUD, bauds = bauds, bluetooth = msg[BLUETOOTH] == true)
    }

    private fun baud(v: Any?): Int? = (v as? Double)?.takeIf { it % 1.0 == 0.0 }?.toInt()?.takeIf { it in BAUD_RATES }

    /** A sensor's rate from the subscribe, within its bounds; its default when missing or not a rate. */
    private fun rate(rates: Map<*, *>?, sensor: String, min: Double, max: Double, default: Double): Double =
        (rates?.get(sensor) as? Double)?.takeIf { it.isFinite() && it > 0 }?.coerceIn(min, max) ?: default
}
