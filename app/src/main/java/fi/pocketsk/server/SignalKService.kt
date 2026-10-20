package fi.pocketsk.server

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import fi.pocketsk.MainActivity
import fi.pocketsk.R
import fi.pocketsk.WifiLockModes
import fi.pocketsk.bridge.Bridge
import fi.pocketsk.net.reporting
import fi.pocketsk.node.NodeRuntime

/**
 * The foreground service that keeps Signal K Server running with the screen off and the app
 * gone (docs/PLAN.md §6). The server itself is [Supervisor]'s; this is the Android side of it:
 * the foreground promotion, the locks, the notification, the network watch and the commands.
 *
 * Adapted from Crew Radio's `PttService`: the foreground promotion comes first, on the main
 * thread, inside the platform's ten seconds, and everything slow is the supervisor's threads';
 * the notification is posted once with `startForeground` and then updated with `notify()`, at
 * most every [NOTIFY_GAP_MS]; the locks are taken once and given back once.
 *
 * Commands arrive as intents ([start], [stop], the notification's actions), never through a
 * binding: the screens read [ServerStatus], which outlives the service. Stopping is asynchronous:
 * the service leaves the foreground and stops itself once the supervisor reports
 * [ServerState.Stopped] or [ServerState.Failed], and not if a start came in meanwhile.
 *
 * `START_STICKY`: when Android kills the process, it starts the service again with no intent,
 * and the server comes back if it was running ([ServerPrefs.running]). [BootReceiver] does the
 * same after a reboot (if autostart is on) and an app update.
 *
 * The foreground type is connectedDevice alone, not the plan's dataSync with it: since Android 15
 * a dataSync service gets six hours a day, and may not be started at boot at all, while a
 * server runs for days. Its prerequisite on Android 14 and later is one of the connectivity
 * permissions; CHANGE_WIFI_MULTICAST_STATE (mDNS) is that one. The location type joins with
 * the bridge's GNSS (§7).
 */
class SignalKService : Service() {
    private lateinit var supervisor: Supervisor
    private lateinit var bridge: Bridge
    private lateinit var prefs: ServerPrefs
    private lateinit var log: ServerLog
    private val main = Handler(Looper.getMainLooper())

    private var wakeLock: PowerManager.WakeLock? = null
    private val wifiLocks = mutableListOf<WifiManager.WifiLock>()
    private var multicastLock: WifiManager.MulticastLock? = null
    private var lastStartId = 0

    /** Main thread only, like everything below that has no lock of its own. */
    private var foreground = false
    private var notifyPending = false
    private var lastNotifyMs = 0L
    private var address: String? = null

    /** False from [onDestroy] on, for whatever the supervisor still posts. */
    private var alive = true

    /**
     * Wi-Fi and Ethernet coming and going: the multicast lock is taken again for the new network
     * (mDNS), and the address in the notification follows. Without the internet capability in
     * the request, because a boat's router seldom has a way out and is still the network that
     * matters.
     */
    private val networks = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed()
        override fun onLost(network: Network) = changed()
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) = changed()

        private fun changed() {
            main.post { if (foreground) networkChanged() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        prefs = ServerPrefs(this)
        log = ServerStatus.log(this)
        supervisor = Supervisor(this, NodeRuntime(this), log, prefs, ::onState, ::onAlert)
        bridge = Bridge(this, log)
        createChannels()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        reporting({ log.note("cannot watch the network: $it") }) {
            getSystemService(ConnectivityManager::class.java)?.registerNetworkCallback(request, networks)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastStartId = startId
        when (intent?.action) {
            // Restarted by the system after it killed the process.
            null -> if (prefs.running) begin() else stopIfIdle()
            ACTION_START -> begin()
            ACTION_ROLLBACK -> begin(rollbackFirst = true)
            ACTION_STOP -> {
                prefs.running = false
                if (supervisor.isActive) supervisor.stop() else stopIfIdle()
            }
            ACTION_RESTART -> if (supervisor.isActive) supervisor.restart() else stopIfIdle()
            ACTION_PROMOTED -> if (supervisor.isActive) supervisor.promoted() else stopIfIdle()
            ACTION_PERMISSIONS -> if (foreground) permissionsChanged() else stopIfIdle()
            ACTION_LEVEL -> if (foreground) bridge.level() else stopIfIdle()
            ACTION_DEVICES -> if (foreground) bridge.devicesChanged() else stopIfIdle()
            ACTION_SILENCE -> {
                val id = intent.getStringExtra(EXTRA_ALARM)
                if (foreground && id != null) bridge.silence(id) else stopIfIdle()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        // Throws when the registration above failed; there is nothing to undo then.
        reporting({}) { getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(networks) }
        main.removeCallbacksAndMessages(null)
        // The finish() that would have said so may have been among the callbacks just dropped.
        ServerStatus.publishLocation(null)
        // Last resort, when Android destroys a service that still runs the server: the group goes
        // now, since nothing will be left to reap it.
        if (supervisor.isActive) supervisor.shutdown()
        // On the bridge's own thread: the socket closes and its threads end after this returns.
        bridge.close()
        releaseLocks()
        super.onDestroy()
    }

    /**
     * Into the foreground and the server started. A start the platform refuses (started from the
     * background where no exemption applies) leaves the service stopped and says why in the log.
     */
    private fun begin(rollbackFirst: Boolean = false) {
        try {
            enterForeground()
        } catch (e: Exception) {
            // ForegroundServiceStartNotAllowedException and friends; broadly, as nothing else can be done.
            log.note("cannot run in the foreground: $e")
            // Nothing runs, so nothing holds location; a server already running keeps what it has.
            if (!supervisor.isActive) ServerStatus.publishLocation(null)
            stopIfIdle()
            return
        }
        foreground = true
        lastNotifyMs = SystemClock.uptimeMillis()
        prefs.running = true
        getSystemService(NotificationManager::class.java)?.cancel(ALERT_ID)
        acquireLocks()
        address = LanAddress.ofThisPhone()
        bridge.start()
        supervisor.start(rollbackFirst)
    }

    /**
     * Connected device always; location too once precise location is granted, which a service
     * must declare to keep receiving fixes while no screen of the app is in front. Asking for the
     * location type without the permission throws on Android 14 and later.
     */
    private fun foregroundTypes(): Int {
        val location = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
    }

    /**
     * A screen of the app is in front and a permission may have changed: the service takes the
     * foreground types it is now allowed (a server started at boot gains location only like this,
     * since while-in-use access is granted to a foreground service started or renewed while the
     * app is visible), and the bridge's sensors try again.
     */
    private fun permissionsChanged() {
        reporting({ log.note("cannot renew the foreground service: $it") }) { enterForeground() }
        bridge.permissionsChanged()
    }

    /**
     * Into the foreground with [foregroundTypes]. Android 14 and later refuse the location type
     * to a service started while no screen of the app is in front (at boot), with a
     * SecurityException: the server then runs as a connected device alone, and the GNSS waits
     * for the app to be opened ([permissionsChanged]). Anything else is the caller's to handle.
     */
    private fun enterForeground() {
        val types = foregroundTypes()
        val location = (types and ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION) != 0
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), types)
            ServerStatus.publishLocation(location)
        } catch (e: SecurityException) {
            if (!location) throw e
            // Not always given back on renewal either, as the phones show: the Dock then offers
            // a start with the app in front ([ServerStatus.locationHeld]).
            if (ServerStatus.locationHeld != false) {
                log.note("location is allowed only with the app in front: the phone's GNSS is off until the server is started from the app")
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            ServerStatus.publishLocation(false)
        }
    }

    /** From the supervisor's threads. */
    private fun onState(state: ServerState) {
        ServerStatus.publish(state)
        if (state.isActive) postNotification() else main.post { finish() }
    }

    /** The server has stopped or given up: out of the foreground, the locks given back, and gone. */
    private fun finish() {
        // A start since the stop was reported has the supervisor running again.
        if (!alive || supervisor.isActive) return
        foreground = false
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        ServerStatus.publishLocation(null)
        bridge.stop()
        releaseLocks()
        stopIfIdle()
    }

    /** Stops the service unless a newer start command is on its way. */
    private fun stopIfIdle() {
        if (!supervisor.isActive) stopSelfResult(lastStartId)
    }

    /**
     * Something to hear about with no screen open: a rollback, or the server given up, [failed]
     * then, with what can be done about it.
     */
    private fun onAlert(text: String, failed: ServerState.Failed?) = main.post {
        if (!alive || !mayNotify()) return@post
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val b = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.server_alert_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_ERROR)
        if (failed != null) {
            b.addAction(0, getString(R.string.server_action_start), foregroundAction(ACTION_START, 10, flags))
            failed.rollbackTo?.let {
                b.addAction(0, getString(R.string.server_action_rollback, it), foregroundAction(ACTION_ROLLBACK, 11, flags))
            }
        }
        getSystemService(NotificationManager::class.java)?.notify(ALERT_ID, b.build())
    }

    private fun networkChanged() {
        // Taken again, not kept: a lock taken on the network that went away does not carry over.
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock?.acquire()
        val now = LanAddress.ofThisPhone()
        if (now != address) {
            address = now
            log.note(now?.let { "reachable on the boat's network at $it" } ?: "no LAN address")
            postNotification()
        }
    }

    // ---- notification --------------------------------------------------------------------------

    /** Coalesces updates onto the main thread, at most one per [NOTIFY_GAP_MS]. */
    private fun postNotification() = main.post {
        if (notifyPending || !foreground || !alive) return@post
        notifyPending = true
        val wait = (lastNotifyMs + NOTIFY_GAP_MS - SystemClock.uptimeMillis()).coerceIn(0L, NOTIFY_GAP_MS)
        main.postDelayed({
            notifyPending = false
            lastNotifyMs = SystemClock.uptimeMillis()
            if (foreground && alive && mayNotify()) {
                getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, buildNotification())
            }
        }, wait)
    }

    /** Without the permission nothing is shown; the server runs on regardless. */
    private fun mayNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** State, version and where to reach it; Stop and Restart while it runs. */
    private fun buildNotification(): Notification {
        val state = supervisor.state
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val (title, text) = when (state) {
            is ServerState.Starting -> getString(R.string.server_title, state.version) to getString(R.string.server_starting)
            is ServerState.Running -> getString(R.string.server_title, state.version) to
                "http://${address ?: "127.0.0.1"}:${state.port}".let { url ->
                    when {
                        !state.answering -> getString(R.string.server_not_answering, url)
                        state.onTrial -> getString(R.string.server_on_trial, url)
                        else -> url
                    }
                }
            is ServerState.Waiting -> getString(R.string.server_title, state.version) to
                getString(R.string.server_waiting, state.reason, state.delayMs / 1000)
            ServerState.Stopping -> getString(R.string.app_name) to getString(R.string.server_stopping)
            ServerState.Stopped, is ServerState.Failed -> getString(R.string.app_name) to getString(R.string.server_starting)
        }
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(openApp())
            .addAction(0, getString(R.string.server_action_stop), action(ACTION_STOP, 1, flags))
            .addAction(0, getString(R.string.server_action_restart), action(ACTION_RESTART, 2, flags))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** For a service already in the foreground. */
    private fun action(name: String, code: Int, flags: Int): PendingIntent =
        PendingIntent.getService(this, code, intent(this, name), flags)

    /** For one that is not: the tap on the notification is what allows the start. */
    private fun foregroundAction(name: String, code: Int, flags: Int): PendingIntent =
        PendingIntent.getForegroundService(this, code, intent(this, name), flags)

    /** Low importance for the ongoing one, so it never sounds; default for the alerts, which should. */
    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.server_channel), NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) },
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, getString(R.string.server_alert_channel), NotificationManager.IMPORTANCE_DEFAULT),
        )
    }

    // ---- locks ---------------------------------------------------------------------------------

    /**
     * The CPU, the Wi-Fi radio and multicast, for as long as the server runs. Idempotent.
     * Wi-Fi gets the modes [WifiLockModes] picks (low latency, and high performance below
     * Android 14, which keeps the radio out of power save with the screen off); multicast, so
     * mDNS answers reach the phone and Signal K is found on the boat's network.
     */
    @SuppressLint("WakelockTimeout") // held while the server runs, which is days; released in finish() and onDestroy()
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketsk:server")
                ?.also { it.acquire() }
        }
        // A phone without Wi-Fi (a tablet on Ethernet) has no Wi-Fi locks to take.
        val wm = applicationContext.getSystemService(WifiManager::class.java) ?: return
        if (wifiLocks.isEmpty()) {
            for (mode in WifiLockModes.forSdk(Build.VERSION.SDK_INT)) {
                wifiLocks += wm.createWifiLock(mode, "pocketsk:wifi:$mode").also {
                    it.setReferenceCounted(false)
                    it.acquire()
                }
            }
        }
        if (multicastLock == null) {
            multicastLock = wm.createMulticastLock("pocketsk:mdns").also {
                it.setReferenceCounted(false)
                it.acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        for (lock in wifiLocks) if (lock.isHeld) lock.release()
        wifiLocks.clear()
        multicastLock?.let { if (it.isHeld) it.release() }
        multicastLock = null
    }

    companion object {
        private const val ACTION_START = "fi.pocketsk.action.START"
        private const val ACTION_STOP = "fi.pocketsk.action.STOP"
        private const val ACTION_RESTART = "fi.pocketsk.action.RESTART"
        private const val ACTION_ROLLBACK = "fi.pocketsk.action.ROLLBACK"
        private const val ACTION_PROMOTED = "fi.pocketsk.action.PROMOTED"
        private const val ACTION_PERMISSIONS = "fi.pocketsk.action.PERMISSIONS"
        private const val ACTION_LEVEL = "fi.pocketsk.action.LEVEL"
        private const val ACTION_DEVICES = "fi.pocketsk.action.DEVICES"

        /** Silence for an alarm ([EXTRA_ALARM], its id), from the alarm's notification ([fi.pocketsk.bridge.PhoneAlarm]). */
        internal const val ACTION_SILENCE = "fi.pocketsk.action.SILENCE"
        internal const val EXTRA_ALARM = "fi.pocketsk.extra.ALARM"
        private const val CHANNEL = "server"
        private const val ALERT_CHANNEL = "server-alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_ID = 2

        /** Shortest time between two notification updates; the shade cannot show more anyway. */
        private const val NOTIFY_GAP_MS = 250L

        private fun intent(context: Context, action: String) = Intent(context, SignalKService::class.java).setAction(action)

        /**
         * Starts the server. From the screen in front, a notification action or [BootReceiver]:
         * a start from the background without one of those is refused by Android 12 and later.
         */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context, intent(context, ACTION_START))
        }

        /** Stops the server and keeps it stopped; nothing to do when it is not running. */
        fun stop(context: Context) {
            ServerPrefs(context).running = false
            if (ServerStatus.state.isActive) send(context, ACTION_STOP)
        }

        /** Restarts a running server; nothing to do when none runs. */
        fun restart(context: Context) {
            if (ServerStatus.state.isActive) send(context, ACTION_RESTART)
        }

        /**
         * [version] is about to be made live by the installer, so it owes its health gate. Called
         * under the installer's lock, before `current` moves, so no run can start it ungated.
         */
        internal fun owesGate(context: Context, version: String) {
            ServerPrefs(context).owe(version)
        }

        /**
         * The installer made a new version live: a running server moves to it. From the installing
         * thread. Decided on whether the server is meant to run ([ServerPrefs.running], set as the
         * service starts it), not on the published state, which reads Stopped until the supervisor
         * first reports, and a start in that moment may already have read the old slot. A service
         * that has nothing running ignores the command.
         */
        internal fun promoted(context: Context) {
            if (ServerPrefs(context).running) send(context, ACTION_PROMOTED)
        }

        /**
         * From a screen of the app in front: a permission may have been granted (location, from
         * the Dock or the system settings). A running server renews its foreground service with
         * the types it may now hold, and the bridge's sensors try again; nothing when none runs.
         * Gated on [ServerPrefs.running] like [promoted], not on the published state, which reads
         * Stopped while a start at boot has not yet reported; a service with nothing in the
         * foreground ignores the command.
         */
        fun permissionsChanged(context: Context) {
            if (ServerPrefs(context).running) send(context, ACTION_PERMISSIONS)
        }

        /**
         * "Level now" from the Dock: a running server's bridge takes the phone's present tilt as
         * the boat's level. Gated like [permissionsChanged]; the server log says how it went.
         */
        fun level(context: Context) {
            if (ServerPrefs(context).running) send(context, ACTION_LEVEL)
        }

        /**
         * The owner chose or dropped a Bluetooth device in the Dock: a running server's bridge
         * looks at its serial devices again. Gated like [permissionsChanged].
         */
        fun devicesChanged(context: Context) {
            if (ServerPrefs(context).running) send(context, ACTION_DEVICES)
        }

        /**
         * Silence for the alarm [id], from its screen or the Dock: the phone goes quiet, and the
         * server silences it as it allows. Gated like [permissionsChanged].
         */
        fun silence(context: Context, id: String) {
            if (ServerPrefs(context).running) {
                try {
                    context.startService(intent(context, ACTION_SILENCE).putExtra(EXTRA_ALARM, id))
                } catch (_: IllegalStateException) {
                    // Background start refused: the service, and the alarm with it, is gone.
                }
            }
        }

        /**
         * A command to a running service. The app counts as in the foreground while the service
         * is, so this is allowed; if the service has just gone, there is nothing to tell.
         */
        private fun send(context: Context, action: String) {
            try {
                context.startService(intent(context, action))
            } catch (_: IllegalStateException) {
                // Background start refused: the service is gone, and with it what this was for.
            }
        }
    }
}
