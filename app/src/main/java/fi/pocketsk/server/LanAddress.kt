package fi.pocketsk.server

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.SocketException

/**
 * Which of the phone's addresses to show as the server's, in the notification and on the Dock:
 * the one a tablet or a chart plotter on the boat would use. Wi-Fi first (the boat's router),
 * then the phone's own hotspot (its interface is not a network Android joined, so
 * ConnectivityManager does not list it, and it is found by name), then Ethernet, then anything
 * else that is not mobile data: an address behind the carrier's NAT reaches nobody aboard.
 * IPv4 only: what people type, and what every plotter takes. Loopback and link-local
 * (169.254/16) are never an answer.
 *
 * [pick] is pure, over (interface name, dotted address) pairs, so the order is unit-tested;
 * [ofThisPhone] reads them from [NetworkInterface] for the notification and the Dock.
 */
internal object LanAddress {

    /** The phone's address on the boat's network, or null with none. */
    fun ofThisPhone(): String? {
        val all = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        } catch (_: SocketException) {
            return null
        }
        val candidates = all.filter {
            try {
                it.isUp
            } catch (_: SocketException) {
                false
            }
        }.flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().mapNotNull { a -> a.hostAddress?.let { i.name to it } } }
        return pick(candidates)
    }

    /** The best address of [candidates] (interface name to IPv4 address), or null when none will do. */
    fun pick(candidates: List<Pair<String, String>>): String? =
        candidates
            .filter { (name, address) -> usable(address) && rank(name) < MOBILE }
            .minByOrNull { (name, _) -> rank(name) }
            ?.second

    private fun usable(address: String): Boolean {
        val parts = address.split('.')
        if (parts.size != 4 || parts.any { p -> p.toIntOrNull()?.let { it in 0..255 } != true }) return false
        return parts[0] != "127" && !(parts[0] == "169" && parts[1] == "254") && parts[0] != "0"
    }

    private fun rank(name: String): Int = when {
        name.startsWith("wlan") -> 0
        HOTSPOT.any { name.startsWith(it) } -> 1
        name.startsWith("eth") -> 2
        CELLULAR.any { name.startsWith(it) } -> MOBILE
        else -> 3
    }

    /** Names the hotspot interface has on the phones seen so far: AOSP, Samsung, Qualcomm. */
    private val HOTSPOT = listOf("ap", "swlan", "softap")

    /** Mobile data: Qualcomm, MediaTek, the IPv4-over-IPv6 tunnel, and Samsung's older names. */
    private val CELLULAR = listOf("rmnet", "ccmni", "v4-", "pdp", "seth")

    private const val MOBILE = 10
}
