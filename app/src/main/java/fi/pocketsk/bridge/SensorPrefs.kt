package fi.pocketsk.bridge

import android.content.Context
import androidx.core.content.edit

/**
 * What the phone's sensors remember across starts, in their own preferences file (excluded
 * from backups with every other one, data_extraction_rules.xml): where down was in the phone's
 * frame when the boat last lay level ("level now", [Attitude.mount]). It belongs to this phone
 * where it is mounted, not to the server's settings, so it lives here rather than in the
 * plugin's configuration.
 */
internal class SensorPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Down in the phone's frame at the last level, or null when it has never been levelled. */
    var level: Attitude.Vec?
        get() {
            if (!prefs.contains(LEVEL_X)) return null
            return Attitude.Vec(
                prefs.getFloat(LEVEL_X, 0f).toDouble(),
                prefs.getFloat(LEVEL_Y, 0f).toDouble(),
                prefs.getFloat(LEVEL_Z, 0f).toDouble(),
            ).unit()
        }
        set(value) = prefs.edit {
            if (value == null) {
                remove(LEVEL_X)
                remove(LEVEL_Y)
                remove(LEVEL_Z)
            } else {
                putFloat(LEVEL_X, value.x.toFloat())
                putFloat(LEVEL_Y, value.y.toFloat())
                putFloat(LEVEL_Z, value.z.toFloat())
            }
        }

    private companion object {
        const val FILE = "sensors"
        const val LEVEL_X = "level_x"
        const val LEVEL_Y = "level_y"
        const val LEVEL_Z = "level_z"
    }
}
