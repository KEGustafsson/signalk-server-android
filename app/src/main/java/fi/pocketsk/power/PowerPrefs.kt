package fi.pocketsk.power

import android.content.Context
import androidx.core.content.edit

/**
 * The power profile the owner chose in the Dock, in its own preferences file. It belongs to this
 * phone, like the helm screen's settings, so it is neither the server's nor in a backup.
 */
internal class PowerPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    var profile: PowerProfile
        get() = prefs.getString(PROFILE, null)?.let { name -> PowerProfile.entries.firstOrNull { it.name == name } } ?: PowerProfile.HARBOR
        set(value) = prefs.edit { putString(PROFILE, value.name) }

    private companion object {
        const val FILE = "power"
        const val PROFILE = "profile"
    }
}
