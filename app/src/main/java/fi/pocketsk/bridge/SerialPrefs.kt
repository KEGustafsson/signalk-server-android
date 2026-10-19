package fi.pocketsk.bridge

import android.content.Context
import androidx.core.content.edit

/**
 * Which of the phone's paired Bluetooth devices the owner chose to read (the Dock's Serial
 * devices card), by address, in their own preferences file (excluded from backups with every
 * other one, data_extraction_rules.xml). Pairing alone says nothing: a phone is paired with
 * headsets and cars too, and dialling them would be rude to them and useless to Signal K. It
 * belongs to this phone and its pairings, so it lives here rather than in the plugin's settings.
 */
internal class SerialPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The addresses chosen, as Android writes them (`00:1B:DC:0A:1B:2C`). */
    val bluetooth: Set<String>
        get() = prefs.getStringSet(BLUETOOTH, null)?.toSet() ?: emptySet()

    fun choose(address: String, chosen: Boolean) {
        val next = if (chosen) bluetooth + address else bluetooth - address
        // A copy: Android keeps the set it returned and must not be handed it back changed.
        prefs.edit { putStringSet(BLUETOOTH, HashSet(next)) }
    }

    private companion object {
        const val FILE = "serial"
        const val BLUETOOTH = "bluetooth"
    }
}
