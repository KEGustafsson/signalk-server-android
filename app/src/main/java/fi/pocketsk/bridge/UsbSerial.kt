package fi.pocketsk.bridge

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import fi.pocketsk.bridge.BridgeProtocol.Device
import fi.pocketsk.net.reporting
import fi.pocketsk.net.transportThread
import fi.pocketsk.server.ServerStatus
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * The USB serial adapters plugged into the phone (docs/PLAN.md §7.1): GPS receivers, AIS
 * receivers, NMEA 0183 interfaces, through usb-serial-for-android's drivers (FTDI, Prolific,
 * Silicon Labs, CH34x, CDC-ACM). Android gives an app no `/dev/tty*`, so the server cannot open
 * them itself: the app reads each one and passes its bytes, as they came, to the plugin over the
 * bridge's socket ([BridgeProtocol.serial]), and the plugin turns them into Signal K. Nothing is
 * listening on a port for them, so no other app on the phone and nothing on the boat's network can
 * reach a device through this.
 *
 * Every device found is listed ([BridgeProtocol.devices], and [ServerStatus.devices] for the
 * Dock) while the server runs; one is opened only while the plugin asks for USB ([apply]) and the
 * owner has allowed the app the device. That permission comes from Android's dialog: the one it
 * shows on its own when a device the app's filter names is plugged in (res/xml/usb_devices.xml,
 * on [fi.pocketsk.MainActivity]), or the one the Dock's Allow opens ([askPermission]). The app
 * does not ask by itself when a device is plugged in, since Android's own dialog is then already
 * on the screen, and a second one on top of it would be one too many. Writing to a device (NMEA
 * out to an autopilot) is not done: what is read is passed on, nothing goes back.
 *
 * Plug-in, unplug and a granted permission each make it look again ([rescan]). A device that
 * fails while open is left closed, with the reason in the Dock and the server log, until it is
 * plugged in again or the plugin's settings change; reopening it in a loop would only fill the log.
 * Everything here runs on its own thread ("bridge-usb") but the readers, one per open port.
 */
internal class UsbSerial(context: Context, private val send: (String) -> Unit, private val note: (String) -> Unit) {
    private val app = context.applicationContext
    private val usb = app.getSystemService(UsbManager::class.java)
    private val thread = HandlerThread("bridge-usb").apply { start() }
    private val handler = Handler(thread.looper)
    private val prober = UsbSerialProber.getDefaultProber()

    // All below on [thread] only.
    private var running = false
    private var wants = BridgeProtocol.Serial.NONE
    private val open = HashMap<String, Opened>()
    /** Why a device could not be opened or stopped, by id, until it is plugged in again. */
    private val failed = HashMap<String, String>()
    /** The list as the plugin was last told it, so it hears of changes only. */
    private var told: String? = null
    private var listed: List<Device> = emptyList()

    /** An open port and the thread reading it. */
    private class Opened(val id: String, val address: String, val port: UsbSerialPort, val baud: Int) {
        @Volatile var reading = true
        val bytes = AtomicLong()
        var reader: Thread? = null
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == UsbManager.ACTION_USB_DEVICE_DETACHED) {
                // Plugged in again later, it gets a fresh try.
                failed.clear()
            }
            look()
        }
    }

    /** Ticks while a device is open, so the Dock's byte counts move. */
    private val tick = object : Runnable {
        override fun run() {
            if (open.isEmpty()) return
            ServerStatus.publishDevices(withBytes(listed))
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** With the server: devices are listed (and opened once the plugin asks), plugged in or out. */
    fun start() = handler.post {
        if (running) return@post
        running = true
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_PERMISSION)
        }
        reporting({ note("usb: cannot watch for devices: $it") }) {
            ContextCompat.registerReceiver(app, receiver, filter, null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)
        }
        look()
    }

    /** What the plugin wants read; [BridgeProtocol.Serial.NONE] when it has gone. */
    fun apply(serial: BridgeProtocol.Serial) = handler.post {
        if (serial != wants) failed.clear()
        wants = serial
        if (running) look()
    }

    /** A plugin has just connected: it hears the list, which is otherwise sent on change only. */
    fun resend() = handler.post {
        told = null
        if (running) publish(listed)
    }

    /** A permission may have been granted (the app came to the front): look again. */
    fun rescan() = handler.post { if (running) look() }

    /** With the server: every device closed, and no more watching. */
    fun stop() = handler.post { halt() }

    /** For good, when the service goes. */
    fun close() = handler.post {
        halt()
        thread.quitSafely()
    }

    private fun halt() {
        if (!running) return
        running = false
        reporting({}) { app.unregisterReceiver(receiver) }
        for (o in open.values.toList()) shut(o)
        open.clear()
        failed.clear()
        publish(emptyList())
    }

    /** What is plugged in now, each port opened or closed as the plugin and the owner allow. */
    private fun look() {
        val drivers = reportingList { prober.findAllDrivers(usb) }.sortedBy { it.device.deviceId }
        val ports = drivers.flatMap { d -> d.ports.map { d to it } }
        val ids = SerialIds.ids(
            ports.map { (d, p) ->
                SerialIds.Port(d.device.vendorId, d.device.productId, serialOf(d.device), p.portNumber, d.ports.size)
            },
        )
        val present = HashSet<String>()
        val list = ports.zip(ids).map { (dp, id) ->
            val (driver, port) = dp
            present += id
            device(id, driver, port)
        }
        for (o in open.values.toList()) if (o.id !in present) {
            shut(o)
            open.remove(o.id)
        }
        publish(list)
    }

    /** One port: opened, closed or left as it is, and how it stands. */
    private fun device(id: String, driver: UsbSerialDriver, port: UsbSerialPort): Device {
        val device = driver.device
        val baud = wants.baudFor(id)
        val allowed = usb?.hasPermission(device) == true
        val existing = open[id]
        if (existing != null && (!wants.usb || !allowed || existing.baud != baud || existing.address != device.deviceName)) {
            shut(existing)
            open.remove(id)
        }
        val name = nameOf(driver)
        fun state(state: String, detail: String? = null) = Device(id, BridgeProtocol.USB, name, baud, state, detail, address = device.deviceName)
        return when {
            !allowed -> state(Device.NEEDS_PERMISSION)
            !wants.usb -> state(Device.IDLE)
            open[id] != null -> state(Device.OPEN)
            failed[id] != null -> state(Device.ERROR, failed[id])
            else -> {
                val why = openPort(id, device, port, baud)
                if (why == null) state(Device.OPEN) else state(Device.ERROR, why).also { failed[id] = why }
            }
        }
    }

    /** Opens [port] at [baud] and starts its reader; null when it is open, else why not. */
    private fun openPort(id: String, device: UsbDevice, port: UsbSerialPort, baud: Int): String? {
        val connection: UsbDeviceConnection = usb?.openDevice(device) ?: return "Android would not open it"
        try {
            port.open(connection)
            port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            // The library closes the connection with the port; a port that never opened has not.
            reporting({}) { if (port.isOpen) port.close() else connection.close() }
            note("usb: cannot open $id: $e")
            return e.message ?: e.toString()
        }
        // Some adapters (CDC-ACM ones above all) send nothing until the host says it is there.
        reporting({}) { port.dtr = true }
        reporting({}) { port.rts = true }
        val o = Opened(id, device.deviceName, port, baud)
        open[id] = o
        o.reader = transportThread("usb-read-$id", onError = { e -> handler.post { lost(o, e) } }) { read(o) }
        note("usb: reading $id (${nameOf(port.driver)}) at $baud baud")
        if (open.size == 1) {
            // A port closed and opened again in one look leaves the last tick queued: one chain.
            handler.removeCallbacks(tick)
            handler.postDelayed(tick, TICK_MS)
        }
        return null
    }

    /** The reader: whatever the port gives, as it comes, until it is shut. */
    private fun read(o: Opened) {
        val buffer = ByteArray(BridgeProtocol.MAX_SERIAL_BYTES)
        while (o.reading) {
            val n = o.port.read(buffer, READ_TIMEOUT_MS)
            if (n > 0 && o.reading) {
                o.bytes.addAndGet(n.toLong())
                send(BridgeProtocol.serial(o.id, buffer, n))
            }
        }
    }

    /** A reader stopped with [e]: the device was pulled out, or it failed. */
    private fun lost(o: Opened, e: Throwable) {
        if (!o.reading || open[o.id] !== o) return
        shut(o)
        open.remove(o.id)
        // Unplugged: the detach that follows lists it as gone. Anything else is kept as the reason.
        failed[o.id] = (e as? IOException)?.message ?: e.toString()
        note("usb: ${o.id} stopped: $e")
        look()
    }

    private fun shut(o: Opened) {
        o.reading = false
        reporting({}) { o.port.close() }
        // The read in progress ends within its timeout once the port is closed.
        reporting({}) { o.reader?.join(READ_TIMEOUT_MS * 2L) }
    }

    /** The plugin hears of a change in the list; the Dock is shown it, with the byte counts. */
    private fun publish(list: List<Device>) {
        listed = list
        val line = BridgeProtocol.devices(list)
        if (line != told) {
            told = line
            send(line)
        }
        ServerStatus.publishDevices(withBytes(list))
    }

    private fun withBytes(list: List<Device>) = list.map { d -> open[d.id]?.let { d.copy(bytes = it.bytes.get()) } ?: d }

    private inline fun reportingList(block: () -> List<UsbSerialDriver>): List<UsbSerialDriver> =
        try {
            if (usb == null) emptyList() else block()
        } catch (e: Exception) {
            note("usb: cannot list devices: $e")
            emptyList()
        }

    /** The serial number, which Android shows only once the device is allowed (null before). */
    private fun serialOf(device: UsbDevice): String? =
        if (usb?.hasPermission(device) != true) null else try {
            device.serialNumber
        } catch (_: SecurityException) {
            null
        }

    /** What the device calls itself, else its chip family as the driver knows it. */
    private fun nameOf(driver: UsbSerialDriver): String {
        val product = try {
            driver.device.productName?.trim()?.takeIf { it.isNotEmpty() }
        } catch (_: SecurityException) {
            null
        }
        return (product ?: driver.javaClass.simpleName.removeSuffix("SerialDriver")).take(MAX_NAME)
    }

    companion object {
        /** Android's answer to [askPermission]; the app looks again whatever it was. */
        private const val ACTION_PERMISSION = "fi.pocketsk.action.USB_PERMISSION"

        /** How long a read waits for bytes before it looks whether it should stop. */
        private const val READ_TIMEOUT_MS = 200

        /** How often the Dock's byte counts are refreshed while a device is open. */
        private const val TICK_MS = 2_000L

        private const val MAX_NAME = 64

        /**
         * The Dock's Allow: Android's dialog for the device at [address]. Its answer comes back as
         * a broadcast the running bridge listens for; the app coming back to the front looks again
         * as well. Immutable: the bridge reads nothing from the answer, it only looks again.
         */
        fun askPermission(context: Context, address: String) {
            val usb = context.getSystemService(UsbManager::class.java) ?: return
            val device = usb.deviceList[address] ?: return
            val answer = PendingIntent.getBroadcast(
                context,
                0,
                Intent(ACTION_PERMISSION).setPackage(context.packageName),
                PendingIntent.FLAG_IMMUTABLE,
            )
            usb.requestPermission(device, answer)
        }
    }
}
