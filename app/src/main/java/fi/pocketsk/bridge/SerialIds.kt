package fi.pocketsk.bridge

import java.util.Locale

/**
 * The ids of the serial devices plugged into the phone, as the plugin and the plugin's settings
 * know them (a baud rate per device) and as the server's `$source` shows them
 * (`android.usb-0403-6001-a10k1b2c.GP`). Pure, so it is unit-tested.
 *
 * An id must stay the same each time the same adapter is plugged in, or the rate set for it would
 * not find it: so it is made of what the adapter says about itself, its vendor and product ids,
 * and its serial number where it has one, never of where Android happens to put it
 * (`/dev/bus/usb/001/004` moves with every plug). Android shows the serial number only once the
 * owner has allowed the app the device, so a device waiting for that has an id without it; it is
 * not read until then, so nothing is lost by that. Two adapters that say the same about themselves
 * (two CH340s, which have no serial number) are told apart by the order Android numbered them in,
 * which is the order they were plugged in: `-2` for the second. A device with several ports (an
 * FT2232) has one id per port, `-p0`, `-p1`.
 *
 * A Bluetooth device is its address, which is its own and does not move: `bt-001bdc0a1b2c`.
 */
internal object SerialIds {
    /** One port of a device, as [ids] takes it. */
    data class Port(val vendorId: Int, val productId: Int, val serial: String?, val port: Int, val ports: Int)

    /** Longest part of a serial number kept in an id. */
    private const val MAX_SERIAL = 24

    /** The ids of [ports], in their order, which the caller makes Android's numbering order. */
    fun ids(ports: List<Port>): List<String> {
        val seen = HashMap<String, Int>()
        return ports.map { p ->
            val base = buildString {
                append(String.format(Locale.ROOT, "%s-%04x-%04x", BridgeProtocol.USB, p.vendorId and 0xffff, p.productId and 0xffff))
                clean(p.serial)?.let { append('-').append(it) }
                if (p.ports > 1) append("-p").append(p.port)
            }
            val n = (seen[base] ?: 0) + 1
            seen[base] = n
            if (n == 1) base else "$base-$n"
        }
    }

    /** A Bluetooth device's id from its address (`00:1B:DC:0A:1B:2C`), or null for one that is not an address. */
    fun bluetooth(address: String): String? {
        val hex = address.lowercase(Locale.ROOT).replace(":", "")
        return if (hex.length == 12 && hex.all { it in '0'..'9' || it in 'a'..'f' }) "${BridgeProtocol.BLUETOOTH}-$hex" else null
    }

    /** A serial number as an id can carry it: lower-case letters and digits, at most [MAX_SERIAL]. */
    fun clean(serial: String?): String? =
        serial?.lowercase(Locale.ROOT)?.filter { it in 'a'..'z' || it in '0'..'9' }?.take(MAX_SERIAL)?.takeIf { it.isNotEmpty() }
}
