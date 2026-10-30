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
        ) : State

        /** The request ended without access: [denied] by the admin, or refused for [why]. */
        data class Ended(val url: String, val name: String, val denied: Boolean, val why: Satellite.Why? = null) : State
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

    fun detach(observer: Observer) {
        observers -= observer
    }

    /** What the preferences say at the process's first look, a waiting request polled again. */
    private fun resume(app: Context): State {
        val prefs = SatellitePrefs(app)
        val url = prefs.url ?: return State.Off
        val name = prefs.name ?: url
        if (prefs.following) return State.Following(url, name, prefs.link()?.token != null, link.first, link.second)
        if (prefs.href == null || prefs.askedOf != url) return State.Off.also { prefs.off() }
        start(app, url, name)
        return State.Asking(url, name, reached = true)
    }

    /**
     * The plugin's word on its stream ([BridgeProtocol.Incoming.Satellite]), or null when the
     * plugin has gone; shown while following.
     */
    @Synchronized
    fun linked(state: BridgeProtocol.SatelliteLink?, detail: String?) {
        link = state to detail
        (latest as? State.Following)?.let { publish(it.copy(link = state, detail = detail)) }
    }

    /** The plugin's last word, for a [State.Following] published after it. */
    private var link: Pair<BridgeProtocol.SatelliteLink?, String?> = null to null

    /** Asks [url] (named [name]) for access, or polls the request already made of it. */
    @Synchronized
    fun follow(context: Context, url: String, name: String) {
        val app = context.applicationContext
        val wasFollowing = SatellitePrefs(app).following
        SatellitePrefs(app).target(url, name)
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
        publish(State.Off)
        if (wasFollowing) SignalKService.satelliteChanged(app)
    }

    private fun start(app: Context, url: String, name: String) {
        val gen = ++generation
        worker?.interrupt()
        worker = transportThread("satellite-access", onError = { e ->
            // Nothing the loop expects (an address the platform refuses, say): ended rather than
            // left asking with nobody asking.
            if (settle(gen) { SatellitePrefs(app).off(); State.Ended(url, name, denied = false, why = Satellite.Why.NOT_SIGNAL_K) }) {
                ServerStatus.log(app).note("satellite: the access request to $url failed: ${e.message ?: e}")
            }
        }) { run(app, gen, url, name) }
    }

    private fun run(app: Context, gen: Int, url: String, name: String) {
        val prefs = SatellitePrefs(app)
        val client = SignalKClient(app, url, null)
        val backoff = Backoff(RETRY_FIRST_MS, RETRY_MAX_MS)
        var href = prefs.href?.takeIf { prefs.askedOf == url }
        // A request kept from an earlier follow may be gone (the main server restarted): one that
        // this run did not make is then asked again, rather than reported as lapsed.
        var resumed = href != null
        val log = ServerStatus.log(app)
        while (true) {
            val outcome = href?.let { Satellite.afterPoll(it, client.pollAccess(it)) }
                ?: Satellite.afterRequest(client.requestAccess(prefs.clientId, description(app)))
            when (outcome) {
                is Satellite.Outcome.Pending -> {
                    if (href == null) {
                        if (!settle(gen) { prefs.asking(url, name, outcome.href); State.Asking(url, name, reached = true) }) return
                        log.note("satellite: asked $url for access; its admin approves it under Security, Access Requests")
                    } else if (!settle(gen) { State.Asking(url, name, reached = true) }) return
                    href = outcome.href
                    backoff.reset()
                    if (!sleepQuietly(POLL_MS)) return
                }
                is Satellite.Outcome.Approved -> {
                    if (!settle(gen) { prefs.follow(url, name, outcome.token); State.Following(url, name, outcome.token != null) }) return
                    log.note("satellite: following $url" + if (outcome.token == null) " (it runs without security)" else ", access approved")
                    SignalKService.satelliteChanged(app)
                    return
                }
                Satellite.Outcome.Denied -> {
                    if (settle(gen) { prefs.forgetRequest(); prefs.off(); State.Ended(url, name, denied = true) }) {
                        log.note("satellite: $url denied access")
                    }
                    return
                }
                is Satellite.Outcome.Refused -> {
                    if (outcome.why == Satellite.Why.EXPIRED && resumed) {
                        resumed = false
                        href = null
                        if (!settle(gen) { prefs.forgetRequest(); State.Asking(url, name, reached = false) }) return
                        continue
                    }
                    if (settle(gen) { prefs.forgetRequest(); prefs.off(); State.Ended(url, name, denied = false, why = outcome.why) }) {
                        log.note("satellite: $url refused the access request (${outcome.why})")
                    }
                    return
                }
                is Satellite.Outcome.Retry -> {
                    if (!settle(gen) { State.Asking(url, name, reached = false, detail = outcome.detail) }) return
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
    private const val RETRY_FIRST_MS = 2_000L
    private const val RETRY_MAX_MS = 30_000L
}
