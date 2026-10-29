// The one place in the app where a deprecated platform call is allowed, and why: each one below is
// the only form the platform offers on the API levels it is called on, and every caller reaches it
// behind that API check. A deprecated constant with a stable value is not here: it is written out
// by hand beside its caller and pinned by a unit test (WifiLockModes).
@file:Suppress("DEPRECATION")

package fi.pocketsk

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.service.quicksettings.TileService
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

    // ---- Quick Settings tile, below API 34 -------------------------------------------------------
    // startActivityAndCollapse(PendingIntent) replaces it from API 34, which refuses the Intent form.

    /** Opens [intent] from the tile and closes the panel. */
    // Lint cannot see the caller's API check from here (ServerTile and NightTile call the
    // PendingIntent form on API 34 and later); below 34 this Intent form is the only one there is.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    fun startActivityAndCollapse(tile: TileService, intent: Intent) {
        tile.startActivityAndCollapse(intent)
    }

    // ---- The app's own update ------------------------------------------------------------------

    /** Who installed [packageName], below API 30; getInstallSourceInfo replaces it from 30. */
    fun installerPackageName(pm: PackageManager, packageName: String): String? = pm.getInstallerPackageName(packageName)

    /** What Android reads of the APK at [path], below API 33; the PackageInfoFlags form replaces it from 33. */
    fun packageArchiveInfo(pm: PackageManager, path: String, flags: Int): PackageInfo? = pm.getPackageArchiveInfo(path, flags)
}
