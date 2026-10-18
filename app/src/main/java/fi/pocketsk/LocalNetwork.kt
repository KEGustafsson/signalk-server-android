package fi.pocketsk

import android.os.Build
import fi.pocketsk.net.NetworkChoice
import java.net.URI

/**
 * Android 17's local network protection, decided in one pure place. An app that targets API 37
 * (this one does) needs the runtime permission ACCESS_LOCAL_NETWORK, part of the Nearby devices
 * group, for any traffic to or from a local address: the server's own listeners reached by other
 * devices on the LAN (a browser on a tablet showing the admin UI), mDNS advertising and discovery,
 * and an NMEA gateway at 192.168.x.x. Without it TCP times out and UDP fails with EPERM,
 * deep in the stack, so it has to be asked for before those are used, not diagnosed after.
 * Earlier releases grant it with INTERNET, so below [Build.VERSION_CODES.CINNAMON_BUN] nothing is
 * ever needed.
 *
 * Loopback is not the local network: the server and the bridge talking to each other on 127.0.0.1,
 * or the phone's own browser showing the admin UI from there, never need it. (Ported from Crew Radio; whether a child process inherits the grant
 * is spike R1b in docs/PLAN.md.)
 */
internal object LocalNetwork {
    /** Manifest.permission.ACCESS_LOCAL_NETWORK (API 37), written out so no older-API reference inlines it; pinned by the test. */
    const val PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

    enum class Need {
        /** Not asked for. */
        NONE,
        /** Asked for with the others, but the feature starts without it. */
        OPTIONAL,
        /** The feature cannot work without it. */
        REQUIRED,
    }

    /**
     * What running the server needs: with LAN access switched on (the default; other devices
     * reach the server) it is required, loopback-only needs nothing.
     */
    fun forServer(sdk: Int, lanAccess: Boolean): Need = when {
        sdk < Build.VERSION_CODES.CINNAMON_BUN -> Need.NONE
        lanAccess -> Need.REQUIRED
        else -> Need.NONE
    }

    /**
     * What a client connection to [host] needs (an NMEA 0183 gateway, a remote Signal K server
     * in satellite mode): a private address does, a public one does not, and a name may be either,
     * which only the lookup can tell, so it is asked for without holding the connection up.
     */
    fun forHost(sdk: Int, host: String?): Need = when {
        sdk < Build.VERSION_CODES.CINNAMON_BUN -> Need.NONE
        host == null -> Need.NONE
        else -> when (NetworkChoice.target(host)) {
            NetworkChoice.Target.PRIVATE -> Need.REQUIRED
            NetworkChoice.Target.NAME -> Need.OPTIONAL
            NetworkChoice.Target.PUBLIC -> Need.NONE
        }
    }

    /**
     * Whether reaching the Signal K server at [serverUrl] needs it: a server at a private address
     * or by name (almost always on the boat's LAN) does, a public address does not, and loopback
     * never does. With no URL yet, the search for one is mDNS on the local network, so it does.
     */
    fun forServerUrl(sdk: Int, serverUrl: String?): Boolean {
        if (sdk < Build.VERSION_CODES.CINNAMON_BUN) return false
        val host = serverUrl?.let { runCatching { URI(it).host }.getOrNull() } ?: return true
        if (isLoopback(host)) return false
        return NetworkChoice.target(host) != NetworkChoice.Target.PUBLIC
    }

    private fun isLoopback(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]").lowercase()
        return h == "localhost" || h == "::1" || h.startsWith("127.")
    }
}
