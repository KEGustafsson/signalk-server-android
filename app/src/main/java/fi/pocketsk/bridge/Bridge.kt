package fi.pocketsk.bridge

import android.content.Context
import fi.pocketsk.BuildConfig
import fi.pocketsk.net.reporting
import fi.pocketsk.power.PowerPrefs
import fi.pocketsk.power.PowerRules
import fi.pocketsk.server.ServerLog
import fi.pocketsk.server.ServerStatus
import fi.pocketsk.widget.BoatWidget
import fi.pocketsk.widget.GlanceWatch
import java.io.File
import java.util.concurrent.Executors

/**
 * The Android bridge's app half (docs/PLAN.md §7.1): the socket the server's plugin connects to
 * ([BridgeServer]), the phone's sensors it asks for ([PhoneSensors]), and the serial devices:
 * USB ones plugged into the phone ([UsbSerial]) and paired Bluetooth ones ([BluetoothSerial]),
 * listed together for the plugin and the Dock by a [DeviceList]; the Bluetooth LE devices the
 * phone hears ([BleScanner]); and the other way, the boat's alarms, which ring on the phone
 * ([PhoneAlarm]); and the boat's readings for the home-screen widget, asked for while it can be seen
 * ([GlanceWatch]) and drawn as they come ([BoatWidget]). Started with the server
 * and stopped with it by [fi.pocketsk.server.SignalKService]; the plugin itself is put in place by
 * [BridgePlugin] before every start and finds the socket through [ENV].
 *
 * What the plugin asks for is served as the Dock's power profile allows ([PowerRules]): battery
 * saver keeps GNSS and the battery of the phone's sensors and stops the Bluetooth LE scan.
 *
 * The protocol's lines are [BridgeProtocol]'s. What the plugin sends that is not a known message
 * is noted in the server log, a few times, then only counted, so a broken plugin cannot fill it.
 */
internal class Bridge(context: Context, private val log: ServerLog) {
    private val app = context.applicationContext
    private val sensors = PhoneSensors(context, ::sendToPeer, log::note)
    private val devices = DeviceList(::sendToPeer, ServerStatus::publishDevices)
    private val usb = UsbSerial(context, ::sendToPeer, { devices.update(BridgeProtocol.USB, it) }, log::note)
    private val bluetooth = BluetoothSerial(context, ::sendToPeer, { devices.update(BridgeProtocol.BLUETOOTH, it) }, log::note)
    private val alarms = PhoneAlarm(context, ::sendToPeer, log::note)
    private val ble = BleScanner(context, ::sendToPeer, log::note)
    private val glance = GlanceWatch(context, ::sendToPeer)
    private val server = BridgeServer(socketFile(context), ::connected, ::line, ::disconnected, log::note)

    @Volatile private var peer: BridgeServer.Peer? = null

    /** What the plugin last asked for, which the power profile may cut ([PowerRules]); read again when the profile changes. */
    @Volatile private var asked = BridgeProtocol.Subscription.NONE
    private val power = PowerPrefs(context)

    /**
     * Held while what the phone serves is decided and posted: a subscription from the plugin's
     * reader, a profile change from the service and a disconnect come on different threads, and
     * each posts to the same handlers, so without it an older one could land after a newer one.
     */
    private val serving = Any()
    @Volatile private var droppedNotes = 0

    /**
     * Starts and stops run here, one after another and off the caller's thread: a stop waits for
     * the accept thread to go, which the main thread must not, and a start right after a stop
     * must find the old socket already closed.
     */
    private val control = Executors.newSingleThreadExecutor { r -> Thread(r, "bridge-control") }

    fun start() = control.execute {
        reporting(::failed) { server.start() }
        usb.start()
        bluetooth.start()
        ble.start()
        reporting(::failed) { glance.start() }
    }

    fun stop() = control.execute {
        reporting(::failed) { server.stop() }
        sensors.apply(BridgeProtocol.Subscription.NONE)
        usb.stop()
        bluetooth.stop()
        ble.stop()
        alarms.stop()
        glance.stop()
        ServerStatus.publishDeltaRate(null)
        showGlance(null)
    }

    /**
     * A permission may have been granted (location, a USB device, Bluetooth, notifications): the
     * sensors try again what they could not start, the serial devices are looked at again, the
     * Bluetooth LE scans are tried again, and an alarm that could not be shown is posted.
     */
    fun permissionsChanged() {
        sensors.reapply()
        devicesChanged()
        ble.rescan()
        alarms.refresh()
    }

    /** The owner chose or dropped a Bluetooth device in the Dock: the serial devices are looked at again. */
    fun devicesChanged() {
        usb.rescan()
        bluetooth.rescan()
    }

    /** Silence for the alarm [id], from its notification, its screen or the Dock ([PhoneAlarm.silence]). */
    fun silence(id: String) = alarms.silence(id)

    /** The owner chose another power profile in the Dock: what the plugin asked for is served again under it. */
    fun powerChanged() = synchronized(serving) { serve() }

    /** "Level now" from the Dock: the phone's present tilt becomes the boat's level ([PhoneSensors.level]). */
    fun level() = sensors.level()

    /** For good, when the service goes. */
    fun close() {
        control.execute {
            reporting(::failed) { server.stop() }
            sensors.close()
            usb.close()
            bluetooth.close()
            ble.close()
            alarms.close()
            glance.stop()
        }
        control.shutdown()
    }

    private fun failed(t: Throwable) = log.note("bridge: $t")

    private fun connected(p: BridgeServer.Peer) {
        peer = p
        p.send(BridgeProtocol.hello(BuildConfig.VERSION_NAME))
        // The device list is sent on change only; a new plugin has not heard it.
        devices.resend()
        glance.connected()
        log.note("bridge: the server's plugin connected")
    }

    private fun line(p: BridgeServer.Peer, text: String) {
        when (val msg = BridgeProtocol.parse(text)) {
            is BridgeProtocol.Incoming.Subscribe -> synchronized(serving) {
                if (p === peer) {
                    asked = msg.subscription
                    serve()
                }
            }
            is BridgeProtocol.Incoming.Alarms -> if (p === peer) alarms.update(msg.list)
            is BridgeProtocol.Incoming.Stats -> if (p === peer) ServerStatus.publishDeltaRate(msg.deltaRate)
            is BridgeProtocol.Incoming.Glance -> if (p === peer) showGlance(msg.glance)
            is BridgeProtocol.Incoming.Dropped -> if (droppedNotes++ < MAX_DROPPED_NOTES) log.note("bridge: dropped a line from the plugin: ${msg.reason}")
        }
    }

    private fun disconnected(p: BridgeServer.Peer) {
        synchronized(serving) {
            if (peer !== p) return
            peer = null
            asked = BridgeProtocol.Subscription.NONE
            sensors.apply(BridgeProtocol.Subscription.NONE)
            usb.apply(BridgeProtocol.Serial.NONE)
            bluetooth.apply(BridgeProtocol.Serial.NONE)
            ble.apply(BridgeProtocol.Ble.NONE)
        }
        alarms.lost()
        // With the plugin gone, nobody counts: no rate is better than the last one shown as current.
        ServerStatus.publishDeltaRate(null)
        glance.disconnected()
        showGlance(null)
        if (p.dropped > 0) log.note("bridge: ${p.dropped} readings were dropped while the plugin was not reading")
    }

    /** What the plugin asked for, as the power profile lets the phone serve it. Under [serving]. */
    private fun serve() {
        val s = PowerRules.subscription(power.profile, asked)
        sensors.apply(s)
        usb.apply(s.serial)
        bluetooth.apply(s.serial)
        ble.apply(s.ble)
    }

    /** The widget's readings, or none when the plugin has gone: drawn at once. */
    private fun showGlance(g: BridgeProtocol.Glance?) {
        ServerStatus.publishGlance(g)
        reporting(::failed) { BoatWidget.render(app) }
    }

    private fun sendToPeer(line: String) {
        peer?.send(line)
    }

    companion object {
        /** The environment variable that tells the plugin where the socket is. */
        const val ENV = "POCKETSK_BRIDGE"

        private const val MAX_DROPPED_NOTES = 5

        /** The socket, in the app's private files: only this app's user can open it. */
        fun socketFile(context: Context): File = File(context.filesDir, "bridge.sock")
    }
}
