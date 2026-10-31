package fi.pocketsk.satellite

import android.content.Context
import androidx.core.content.edit
import java.util.UUID

/**
 * Satellite mode's state (docs/PLAN.md §16 13.1), in a preferences file of its own: the main
 * server being followed or asked, the access request's `href` while its admin decides, and the
 * token once approved. The bridge hands [link] to its plugin whenever the plugin connects and
 * whenever it changes; the token lives here alone, never in `.signalk`, which goes to the USB
 * folder and the Dock's backups. Every preferences file is out of Android's backup and device
 * transfer (`data_extraction_rules.xml`), the token with it.
 */
internal class SatellitePrefs(context: Context) {
    private val p = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** The main server, `http://host:port`, while it is followed or asked; null when off. */
    val url: String? get() = p.getString(URL, null)

    /** Its name as it advertises itself, or its address when typed. */
    val name: String? get() = p.getString(NAME, null)

    /**
     * The last access request's `href`, kept after a cancel: the main server refuses a second
     * request from this phone while the first is pending, so following the same server again
     * polls this one ([askedOf] is the server it was asked of).
     */
    val href: String? get() = p.getString(HREF, null)

    /** The server [href] was asked of. */
    val askedOf: String? get() = p.getString(ASKED_OF, null)

    /**
     * Whether [href] is a request for read/write made while following, still waited for: polled
     * again when the process comes back. A cancelled one keeps its [href] (asked again, it is
     * polled rather than refused) but is no longer waited for.
     */
    val askingWrite: Boolean get() = p.getBoolean(ASKING_WRITE, false)

    /** Approved (or no security there): the server follows [url]. */
    val following: Boolean get() = p.getBoolean(FOLLOWING, false)

    /**
     * This phone's client id in the main server's access requests and devices list. Made once and
     * kept, so a request asked again replaces the phone's entry there rather than adding another.
     */
    val clientId: String
        @Synchronized get() = p.getString(CLIENT_ID, null) ?: UUID.randomUUID().toString().also { p.edit { putString(CLIENT_ID, it) } }

    /**
     * What of the phone's own goes to the main server (§16 13.2): none until the Dock says, and
     * for the server it was said for alone. Another server followed, or following stopped, starts
     * again from none, so the phone's position never goes to a server the owner did not choose.
     */
    var push: Satellite.Push
        get() = Satellite.Push(sensors = p.getBoolean(PUSH_SENSORS, false), serial = p.getBoolean(PUSH_SERIAL, false))
        set(value) = p.edit {
            putBoolean(PUSH_SENSORS, value.sensors)
            putBoolean(PUSH_SERIAL, value.serial)
        }

    /** What the bridge's plugin is to follow, or null when not following. */
    fun link(): Satellite.Link? {
        if (!following) return null
        val url = url?.takeIf { Satellite.target(it) != null } ?: return null
        return Satellite.Link(url, p.getString(TOKEN, null), Satellite.label(clientId), push)
    }

    /**
     * An access request asked of [url] (named [name]), waiting at [href]; [keepFollowing] when it
     * asks for read/write while the link goes on with the token it has.
     */
    fun asking(url: String, name: String, href: String, keepFollowing: Boolean = false) = p.edit {
        putString(HREF, href)
        putString(ASKED_OF, url)
        putBoolean(ASKING_WRITE, keepFollowing)
        if (keepFollowing) return@edit
        putString(URL, url)
        putString(NAME, name)
        putBoolean(FOLLOWING, false)
        remove(TOKEN)
    }

    /** The token a request for read/write was approved with, in the old one's place. */
    fun token(token: String) = p.edit {
        putString(TOKEN, token)
        remove(HREF)
        remove(ASKED_OF)
        remove(ASKING_WRITE)
    }

    /** The request for read/write at [href] waited for again, or no longer (see [askingWrite]). */
    fun askingWrite(on: Boolean) = p.edit { putBoolean(ASKING_WRITE, on) }

    /** [url] asked of again: what to poll, if anything (see [href]). */
    fun target(url: String, name: String) = p.edit {
        if (url != this@SatellitePrefs.url) {
            remove(PUSH_SENSORS)
            remove(PUSH_SERIAL)
        }
        putString(URL, url)
        putString(NAME, name)
        putBoolean(FOLLOWING, false)
        remove(TOKEN)
        remove(ASKING_WRITE)
        if (askedOf != url) {
            remove(HREF)
            remove(ASKED_OF)
        }
    }

    /** The request at [href] is answered or gone: a new one is asked next time. */
    fun forgetRequest() = p.edit {
        remove(HREF)
        remove(ASKED_OF)
        remove(ASKING_WRITE)
    }

    /** Following [url] with [token] (null: its server runs without security). */
    fun follow(url: String, name: String, token: String?) = p.edit {
        putString(URL, url)
        putString(NAME, name)
        remove(HREF)
        remove(ASKED_OF)
        remove(ASKING_WRITE)
        putBoolean(FOLLOWING, true)
        if (token == null) remove(TOKEN) else putString(TOKEN, token)
    }

    /**
     * Off: nothing followed, the token and the push choices forgotten. The client id stays, and so
     * does a pending request's [href] (see there).
     */
    fun off() = p.edit {
        remove(URL)
        remove(NAME)
        remove(TOKEN)
        remove(ASKING_WRITE)
        remove(PUSH_SENSORS)
        remove(PUSH_SERIAL)
        putBoolean(FOLLOWING, false)
    }

    private companion object {
        const val FILE = "satellite"
        const val URL = "url"
        const val NAME = "name"
        const val HREF = "href"
        const val ASKED_OF = "asked_of"
        const val ASKING_WRITE = "asking_write"
        const val TOKEN = "token"
        const val FOLLOWING = "following"
        const val CLIENT_ID = "client_id"
        const val PUSH_SENSORS = "push_sensors"
        const val PUSH_SERIAL = "push_serial"
    }
}
