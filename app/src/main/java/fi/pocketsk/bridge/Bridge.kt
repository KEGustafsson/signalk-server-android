package fi.pocketsk.bridge

import android.content.Context
import fi.pocketsk.BuildConfig
import fi.pocketsk.net.reporting
import fi.pocketsk.server.ServerLog
import fi.pocketsk.server.ServerStatus
import java.io.File
import java.util.concurrent.Executors

/**
 * The Android bridge's app half (docs/PLAN.md §7.1): the socket the server's plugin connects to
 * ([BridgeServer]), the phone's sensors it asks for ([PhoneSensors]), and the serial devices:
 * USB ones plugged into the phone ([UsbSerial]) and paired Bluetooth ones ([BluetoothSerial]),
 * listed together for the plugin and the Dock by a [DeviceList]. Started with the server
 * and stopped with it by [fi.pocketsk.server.SignalKService]; the plugin itself is put in place by
 * [BridgePlugin] before every start and finds the socket through [ENV].
 *
 * The protocol's lines are [BridgeProtocol]'s. What the plugin sends that is not a known message
 * is noted in the server log, a few times, then only counted, so a broken plugin cannot fill it.
 */
internal class Bridge(context: Context, private val log: ServerLog) {
    private val sensors = PhoneSensors(context, ::sendToPeer, log::note)
    private val devices = DeviceList(::sendToPeer, ServerStatus::publishDevices)
    private val usb = UsbSerial(context, ::sendToPeer, { devices.update(BridgeProtocol.USB, it) }, log::note)
    private val bluetooth = BluetoothSerial(context, ::sendToPeer, { devices.update(BridgeProtocol.BLUETOOTH, it) }, log::note)
    private val server = BridgeServer(socketFile(context), ::connected, ::line, ::disconnected, log::note)

    @Volatile private var peer: BridgeServer.Peer? = null
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
    }

    fun stop() = control.execute {
        reporting(::failed) { server.stop() }
        sensors.apply(BridgeProtocol.Subscription.NONE)
        usb.stop()
        bluetooth.stop()
    }

    /**
     * A permission may have been granted (location, a USB device, Bluetooth): the sensors try
     * again what they could not start, and the serial devices are looked at again.
     */
    fun permissionsChanged() {
        sensors.reapply()
        devicesChanged()
    }

    /** The owner chose or dropped a Bluetooth device in the Dock: the serial devices are looked at again. */
    fun devicesChanged() {
        usb.rescan()
        bluetooth.rescan()
    }

    /** "Level now" from the Dock: the phone's present tilt becomes the boat's level ([PhoneSensors.level]). */
    fun level() = sensors.level()

    /** For good, when the service goes. */
    fun close() {
        control.execute {
            reporting(::failed) { server.stop() }
            sensors.close()
            usb.close()
            bluetooth.close()
        }
        control.shutdown()
    }

    private fun failed(t: Throwable) = log.note("bridge: $t")

    private fun connected(p: BridgeServer.Peer) {
        peer = p
        p.send(BridgeProtocol.hello(BuildConfig.VERSION_NAME))
        // The device list is sent on change only; a new plugin has not heard it.
        devices.resend()
        log.note("bridge: the server's plugin connected")
    }

    private fun line(p: BridgeServer.Peer, text: String) {
        when (val msg = BridgeProtocol.parse(text)) {
            is BridgeProtocol.Incoming.Subscribe -> if (p === peer) {
                sensors.apply(msg.subscription)
                usb.apply(msg.subscription.serial)
                bluetooth.apply(msg.subscription.serial)
            }
            is BridgeProtocol.Incoming.Dropped -> if (droppedNotes++ < MAX_DROPPED_NOTES) log.note("bridge: dropped a line from the plugin: ${msg.reason}")
        }
    }

    private fun disconnected(p: BridgeServer.Peer) {
        if (peer !== p) return
        peer = null
        sensors.apply(BridgeProtocol.Subscription.NONE)
        usb.apply(BridgeProtocol.Serial.NONE)
        bluetooth.apply(BridgeProtocol.Serial.NONE)
        if (p.dropped > 0) log.note("bridge: ${p.dropped} readings were dropped while the plugin was not reading")
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
