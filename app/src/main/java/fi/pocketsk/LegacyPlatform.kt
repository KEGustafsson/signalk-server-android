// The one place in the app where a deprecated platform call is allowed, and why: each one below is
// the only form the platform offers on the API levels it is called on, and every caller reaches it
// behind that API check. A deprecated constant with a stable value is not here: it is written out
// by hand beside its caller and pinned by a unit test (WifiLockModes).
@file:Suppress("DEPRECATION")

package fi.pocketsk

import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.InetAddress

/**
 * Deprecated platform calls with no replacement on the levels that use them. Nothing here decides
 * anything: the callers keep their API checks and their policy, this only makes the call.
 */
object LegacyPlatform {

    // ---- mDNS, below API 34 ----------------------------------------------------------------------
    // registerServiceInfoCallback and NsdServiceInfo.hostAddresses replace both from API 34.

    /** One-shot resolve of a discovered service. */
    fun resolveService(manager: NsdManager, info: NsdServiceInfo, listener: NsdManager.ResolveListener) {
        manager.resolveService(info, listener)
    }

    /** The single address a pre-34 resolve reports. */
    fun hostOf(info: NsdServiceInfo): InetAddress? = info.host
}
