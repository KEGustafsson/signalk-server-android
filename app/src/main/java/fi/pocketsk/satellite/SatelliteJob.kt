package fi.pocketsk.satellite

import android.content.Context
import android.os.Build
import android.provider.Settings
import fi.pocketsk.R
import fi.pocketsk.bridge.BridgeProtocol
import fi.pocketsk.net.Backoff
import fi.pocketsk.net.sleepQuietly
import fi.pocketsk.net.transportThread
import fi.pocketsk.server.ServerStatus
import fi.pocketsk.server.SignalKService
import fi.pocketsk.signalk.SignalKClient
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Satellite mode's one link (docs/PLAN.md §16 13.1), held by the process like
 * [fi.pocketsk.server.BackupJob], so the Dock closing and opening finds the request it left: the
 * access request to the main server, polled until its admin approves or denies it, then the
 * choice in [SatellitePrefs], which the bridge hands to its plugin at once (no restart: the
 * plugin opens or closes its stream as told). A request that is still waiting is polled again
 * when the process comes back ([observe]); the main server keeps it in memory and forgets it when
 * it restarts, which the poll then says. How the plugin's stream stands comes back over the
 * bridge ([linked]).
 *
 * The push (§16 13.2): what of the phone's own the plugin sends the main server is the Dock's
 * choice ([setPush]), and needs the main server to let this phone write, which the plugin hears
 * from it and says ([linked]). A token that may only read is asked again ([askWrite]): a second
 * access request naming read/write, while the link goes on reading, the new token taking the old
 * one's place once approved.
 */
internal object SatelliteJob {

    /** What satellite mode is doing, as the Dock shows it. */
    sealed interface State {
        data object Off : State

        /**
         * Waiting for the admin of [url] ([reached]) or for it to answer at all; [detail] is what
         * the last failed attempt said.
         */
        data class Asking(val url: String, val name: String, val reached: Boolean, val detail: String? = null) : State

        /**
         * The server follows [url]; [secured] when with a token. [link] is how the plugin's stream
         * stands, null while no plugin has said (the server stopped, or just starting), with its
         * [detail].
         */
        data class Following(
            val url: String,
            val name: String,
            val secured: Boolean,
            val link: BridgeProtocol.SatelliteLink? = null,
            val detail: String? = null,
            /** Whether the main server lets this phone write, null until the plugin has heard. */
            val write: Boolean? = null,
            /** What of the phone's own is sent there. */
            val push: Satellite.Push = Satellite.Push.NONE,
            /** A request for read/write under way or ended, null when none was asked. */
            val upgrade: Upgrade? = null,
        ) : State

        /** The request ended without access: [denied] by the admin, or refused for [why]. */
        data class Ended(val url: String, val name: String, val denied: Boolean, val why: Satellite.Why? = null) : State
    }

    /** The request for read/write while following ([askWrite]). */
    sealed interface Upgrade {
        /** Waiting for the admin ([reached]) or for the main server to answer at all. */
        data class Asking(val reached: Boolean, val detail: String? = null) : Upgrade

        /** Ended without read/write: [denied] by the admin, or refused for [why]. */
        data class Ended(val denied: Boolean, val why: Satellite.Why? = null) : Upgrade
    }

    /** A screen's view of the job. Called on the job's thread, or the observing one at first. */
    fun interface Observer {
        fun onState(state: State)
    }

    private val observers = CopyOnWriteArraySet<Observer>()
    @Volatile private var latest: State? = null

    /** The run that may write; a cancel or a new follow moves it on, and the old loop stops writing. */
    private var generation = 0
    private var worker: Thread? = null

    fun observe(context: Context, observer: Observer) {
        val state = synchronized(this) {
            latest ?: resume(context.applicationContext).also { latest = it }
        }
        observers += observer
        observer.onState(state)
    }

    /** The state last published, null before the first look ([observe]). */
    val current: State? get() = latest

    fun detach(observer: Observer) {
        observers -= observer
    }

    /**
     * What the preferences say at the process's first look, a waiting request polled again: one
     * to follow, or one for read/write while following.
     */
    private fun resume(app: Context): State {
        val prefs = SatellitePrefs(app)
        val url = prefs.url ?: return State.Off
        val name = prefs.name ?: url
        if (prefs.following) {
            if (!prefs.askingWrite || prefs.href == null || prefs.askedOf != url) return following(prefs, url, name)
            start(app, url, name, write = true)
            return following(prefs, url, name, Upgrade.Asking(reached = true))
        }
        if (prefs.href == null || prefs.askedOf != url) return State.Off.also { prefs.off() }
        start(app, url, name)
        return State.Asking(url, name, reached = true)
    }

    /**
     * The plugin's word on its stream ([BridgeProtocol.Incoming.Satellite]), or null when the
     * plugin has gone; shown while following.
     */
    @Synchronized
    fun linked(state: BridgeProtocol.SatelliteLink?, detail: String?, write: Boolean?) {
        heard = Heard(state, detail, write)
        (latest as? State.Following)?.let { publish(it.copy(link = state, detail = detail, write = write)) }
    }

    /** The plugin's last word, for a [State.Following] published after it. */
    private data class Heard(val link: BridgeProtocol.SatelliteLink?, val detail: String?, val write: Boolean?)

    private var heard = Heard(null, null, null)

    /** The following state as the preferences and the plugin's last word make it. */
    private fun following(prefs: SatellitePrefs, url: String, name: String, upgrade: Upgrade? = null) =
        State.Following(url, name, prefs.link()?.token != null, heard.link, heard.detail, heard.write, prefs.push, upgrade)

    /** What of the phone's own goes to the main server, as the Dock chose; the plugin hears at once. */
    @Synchronized
    fun setPush(context: Context, change: (Satellite.Push) -> Satellite.Push) {
        val app = context.applicationContext
        // Applied to what is stored, under the job's lock: two switches flicked before the Dock
        // redraws each change their own field, rather than one copy undoing the other.
        val prefs = SatellitePrefs(app)
        val push = change(prefs.push)
        prefs.push = push
        (latest as? State.Following)?.let { publish(it.copy(push = push)) }
        SignalKService.satelliteChanged(app)
    }

    /**
     * Asks the main server followed for read/write, the link reading on meanwhile: a new access
     * request naming the permission, or the one already made of it polled.
     */
    @Synchronized
    fun askWrite(context: Context) {
        val f = latest as? State.Following ?: return
        val app = context.applicationContext
        // One already made of it (kept from a cancel) is waited for from now, not from its next answer.
        val prefs = SatellitePrefs(app)
        if (prefs.href != null && prefs.askedOf == f.url) prefs.askingWrite(true)
        publish(f.copy(upgrade = Upgrade.Asking(reached = false)))
        start(app, f.url, f.name, write = true)
    }

    /** No longer waits for read/write; the link stays as it is. */
    @Synchronized
    fun cancelWrite(context: Context) {
        SatellitePrefs(context.applicationContext).askingWrite(false)
        generation++
        worker?.interrupt()
        worker = null
        (latest as? State.Following)?.let { publish(it.copy(upgrade = null)) }
    }

    /** Asks [url] (named [name]) for access, or polls the request already made of it. */
    @Synchronized
    fun follow(context: Context, url: String, name: String) {
        val app = context.applicationContext
        val wasFollowing = SatellitePrefs(app).following
        SatellitePrefs(app).target(url, name)
        // What the plugin said was of the server followed before: the next one says again.
        heard = Heard(null, null, null)
        // Moving from one server to another: the old link goes at once, not when the new is approved.
        if (wasFollowing) SignalKService.satelliteChanged(app)
        publish(State.Asking(url, name, reached = false))
        start(app, url, name)
    }

    /** Off: no request polled, nothing followed, and the plugin told to let go of the main server. */
    @Synchronized
    fun stop(context: Context) {
        val app = context.applicationContext
        val prefs = SatellitePrefs(app)
        val wasFollowing = prefs.following
        generation++
        worker?.interrupt()
        worker = null
        prefs.off()
        heard = Heard(null, null, null)
        publish(State.Off)
        if (wasFollowing) SignalKService.satelliteChanged(app)
    }

    private fun start(app: Context, url: String, name: String, write: Boolean = false) {
        val gen = ++generation
        worker?.interrupt()
        worker = transportThread("satellite-access", onError = { e ->
            // Nothing the loop expects (an address the platform refuses, say): ended rather than
            // left asking with nobody asking.
            val ended = settle(gen) {
                if (write) {
                    val prefs = SatellitePrefs(app)
                    prefs.forgetRequest()
                    following(prefs, url, name, Upgrade.Ended(denied = false, why = Satellite.Why.NOT_SIGNAL_K))
                } else {
                    SatellitePrefs(app).off()
                    State.Ended(url, name, denied = false, why = Satellite.Why.NOT_SIGNAL_K)
                }
            }
            if (ended) ServerStatus.log(app).note("satellite: the access request to $url failed: ${e.message ?: e}")
        }) { run(app, gen, url, name, write) }
    }

    /**
     * One access request to [url] and its polls, to follow it or, with [write], for read/write
     * while following it already: then an ending leaves the link as it is.
     */
    private fun run(app: Context, gen: Int, url: String, name: String, write: Boolean) {
        val prefs = SatellitePrefs(app)
        val client = SignalKClient(app, url, null)
        val backoff = Backoff(RETRY_FIRST_MS, RETRY_MAX_MS)
        var href = prefs.href?.takeIf { prefs.askedOf == url }
        // A request kept from an earlier follow may be gone (the main server restarted): one that
        // this run did not make is then asked again, rather than reported as lapsed.
        var resumed = href != null
        val log = ServerStatus.log(app)
        val what = if (write) "read/write" else "access"
        fun asking(reached: Boolean, detail: String? = null): State =
            if (write) following(prefs, url, name, Upgrade.Asking(reached, detail)) else State.Asking(url, name, reached, detail)
        fun ended(denied: Boolean, why: Satellite.Why? = null): State {
            prefs.forgetRequest()
            if (write) return following(prefs, url, name, Upgrade.Ended(denied, why))
            prefs.off()
            return State.Ended(url, name, denied, why)
        }
        while (true) {
            val outcome = href?.let { Satellite.afterPoll(it, client.pollAccess(it)) }
                ?: Satellite.afterRequest(client.requestAccess(prefs.clientId, description(app), if (write) READ_WRITE else null))
            when (outcome) {
                is Satellite.Outcome.Pending -> {
                    if (href == null) {
                        if (!settle(gen) { prefs.asking(url, name, outcome.href, keepFollowing = write); asking(true) }) return
                        log.note("satellite: asked $url for $what; its admin approves it under Security, Access Requests")
                    } else if (!settle(gen) { asking(true) }) return
                    href = outcome.href
                    backoff.reset()
                    if (!sleepQuietly(POLL_MS)) return
                }
                is Satellite.Outcome.Approved -> {
                    val done = settle(gen) {
                        if (write) {
                            // A server without security answers as for the first request: the token stays.
                            outcome.token?.let { prefs.token(it) } ?: prefs.forgetRequest()
                            following(prefs, url, name)
                        } else {
                            prefs.follow(url, name, outcome.token)
                            following(prefs, url, name)
                        }
                    }
                    if (!done) return
                    log.note("satellite: " + if (write) "$url approved read/write" else "following $url" + if (outcome.token == null) " (it runs without security)" else ", access approved")
                    SignalKService.satelliteChanged(app)
                    return
                }
                Satellite.Outcome.Denied -> {
                    if (settle(gen) { ended(denied = true) }) log.note("satellite: $url denied $what")
                    return
                }
                is Satellite.Outcome.Refused -> {
                    if (outcome.why == Satellite.Why.EXPIRED && resumed) {
                        resumed = false
                        href = null
                        if (!settle(gen) { prefs.forgetRequest(); asking(false) }) return
                        continue
                    }
                    if (settle(gen) { ended(denied = false, why = outcome.why) }) {
                        log.note("satellite: $url refused the request for $what (${outcome.why})")
                    }
                    return
                }
                is Satellite.Outcome.Retry -> {
                    if (!settle(gen) { asking(false, outcome.detail) }) return
                    if (!sleepQuietly(backoff.next())) return
                }
            }
        }
    }

    /**
     * Writes and publishes what [step] does, unless a cancel or a new follow came since this run
     * started; false then, and the run stops.
     */
    private inline fun settle(gen: Int, step: () -> State): Boolean = synchronized(this) {
        if (gen != generation) return false
        publish(step())
        true
    }

    private fun publish(state: State) {
        latest = state
        for (o in observers) o.onState(state)
    }

    /**
     * What the main server's admin sees in the request: the app and this phone's name, which the
     * owner set (Settings, About phone), else its model.
     */
    private fun description(app: Context): String {
        val device = Settings.Global.getString(app.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL
        return app.getString(R.string.satellite_request_description, device)
    }

    /** How often a waiting request is polled: the admin is approving it on another screen now. */
    private const val POLL_MS = 3_000L

    /** The permission a request for writing names (signalk-server's own word for it). */
    private const val READ_WRITE = "readwrite"
    private const val RETRY_FIRST_MS = 2_000L
    private const val RETRY_MAX_MS = 30_000L
}
