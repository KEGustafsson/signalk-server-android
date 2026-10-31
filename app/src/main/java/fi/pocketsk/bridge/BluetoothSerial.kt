package fi.pocketsk.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.core.content.ContextCompat
import fi.pocketsk.bridge.BridgeProtocol.Device
import fi.pocketsk.net.Backoff
import fi.pocketsk.net.Failure
import fi.pocketsk.net.Waiter
import fi.pocketsk.net.reporting
import fi.pocketsk.net.transportThread
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/**
 * The phone's paired Bluetooth serial devices (docs/PLAN.md §7.1): NMEA 0183 senders and AIS
 * receivers that speak the Serial Port Profile. The app dials each one the owner chose in the
 * Dock ([SerialPrefs]) over RFCOMM and passes its bytes, as they came, to the plugin over the
 * bridge's socket ([BridgeProtocol.serial]), as [UsbSerial] does for USB; the plugin makes Signal
 * K of them. Nothing is written to a device.
 *
 * The dial and redial loop is Crew Radio's BluetoothTransport's (its `dialLoop` and `redial`),
 * without the listener, the tie-break and the framing: an NMEA device is always the one that
 * answers, and its bytes are a plain stream. A link that drops is dialled again with [Backoff]
 * for as long as the device is chosen and the plugin asks for Bluetooth; one that is out of
 * range simply waits there. The adapter going off drops every link, and its coming back on
 * wakes the waits.
 *
 * Every paired device is listed for the Dock to offer, chosen or not ([list]); the bridge's
 * [DeviceList] tells the plugin of the chosen ones. Without BLUETOOTH_CONNECT (Android 12 and
 * later, asked for in the Dock) the paired devices cannot even be seen: the list is empty and the
 * Dock says what is missing. Every call into the Bluetooth stack can throw: a [SecurityException]
 * for a permission taken back, and whatever a vendor stack throws; each becomes a state and a
 * line in the server log, never a crash. Everything runs on "bridge-bt" but the links, one
 * thread each.
 */
// Lint cannot follow the permission check in [allowed] into the calls it guards, and each of them
// also catches the SecurityException a permission taken back in between would throw.
@SuppressLint("MissingPermission")
internal class BluetoothSerial(
    context: Context,
    private val send: (String) -> Unit,
    private val list: (List<Device>) -> Unit,
    private val note: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val thread = HandlerThread("bridge-bt").apply { start() }
    private val handler = Handler(thread.looper)
    private val prefs = SerialPrefs(app)

    // All below on [thread] only.
    private var running = false
    private var wants = BridgeProtocol.Serial.NONE
    private val links = HashMap<String, Link>()
    private var listed: List<Device> = emptyList()
    private var toldNoPermission = false

    /** One chosen device's link: dialled, read, and dialled again, on a thread of its own. */
    private class Link(val address: String, val id: String) {
        @Volatile var running = true
        @Volatile var state = Device.CONNECTING
        @Volatile var detail: String? = null
        @Volatile var socket: BluetoothSocket? = null
        /** Its thread has ended on an error it cannot dial past; [rescan] lets it try again. */
        @Volatile var dead = false
        val bytes = AtomicLong()
        val waiter = Waiter()
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    // Every link closed and forgotten, so none stays blocked in a read on a dead
                    // socket; the look below dials them afresh, to wait for the adapter to come back.
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                        for (l in links.values.toList()) shut(l)
                        links.clear()
                    }
                    BluetoothAdapter.STATE_ON -> for (l in links.values) l.waiter.wake()
                }
            }
            look()
        }
    }

    /** Ticks while a link is open, so the Dock's byte counts move. */
    private val tick = object : Runnable {
        override fun run() {
            if (links.values.none { it.state == Device.OPEN }) return
            list(withBytes(listed))
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** With the server: the paired devices are listed, and the chosen ones dialled once the plugin asks. */
    fun start() = handler.post {
        if (running) return@post
        running = true
        toldNoPermission = false
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        // Exported: a pairing's change is broadcast by the Bluetooth process, not by the system,
        // and a receiver that is not exported never hears it. Both actions are protected
        // broadcasts, which no other app can send, so exporting it lets nothing else in.
        reporting({ note("bt: cannot watch the adapter: $it") }) {
            ContextCompat.registerReceiver(app, receiver, filter, null, handler, ContextCompat.RECEIVER_EXPORTED)
        }
        look()
    }

    /** What the plugin wants read; [BridgeProtocol.Serial.NONE] when it has gone. */
    fun apply(serial: BridgeProtocol.Serial) = handler.post {
        wants = serial
        if (running) look()
    }

    /**
     * The owner chose or dropped a device, or a permission was granted: look again, and give a
     * link that ended on an error a new try.
     */
    fun rescan() = handler.post {
        toldNoPermission = false
        links.values.removeAll { it.dead }
        if (running) look()
    }

    /** With the server: every link closed, and no more watching. */
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
        for (l in links.values.toList()) shut(l)
        links.clear()
        publish(emptyList())
    }

    /** The paired devices now, each chosen one dialled while the plugin asks, the rest let go. */
    private fun look() {
        val adapter = adapter
        if (adapter == null || !allowed(app)) {
            if (adapter != null && !toldNoPermission) note("bt: Bluetooth is not allowed: no Bluetooth serial devices for Signal K")
            toldNoPermission = true
            for (l in links.values.toList()) shut(l)
            links.clear()
            publish(emptyList())
            return
        }
        val paired = try {
            adapter.bondedDevices.orEmpty().toList()
        } catch (e: SecurityException) {
            note("bt: cannot list the paired devices: $e")
            emptyList()
        }
        val chosen = prefs.bluetooth
        val on = try {
            adapter.isEnabled
        } catch (_: SecurityException) {
            false
        }
        val found = paired.mapNotNull { dev -> SerialIds.bluetooth(dev.address)?.let { id -> dev to id } }
            .sortedBy { (dev, _) -> nameOf(dev) }
        for ((dev, id) in found) {
            val wanted = wants.bluetooth && dev.address in chosen
            val link = links[dev.address]
            if (wanted && link == null) dial(dev.address, id)
            if (!wanted && link != null) {
                shut(link)
                links.remove(dev.address)
            }
        }
        val present = found.map { it.first.address }.toSet()
        for (l in links.values.toList()) if (l.address !in present) {
            shut(l)
            links.remove(l.address)
        }
        publish(
            found.map { (dev, id) ->
                val link = links[dev.address]
                val (state, detail) = when {
                    !wants.bluetooth || dev.address !in chosen -> Device.IDLE to null
                    !on -> Device.ERROR to "Bluetooth is off"
                    link != null -> link.state to link.detail
                    else -> Device.CONNECTING to null
                }
                Device(id, BridgeProtocol.BLUETOOTH, nameOf(dev), 0, state, detail, address = dev.address, chosen = dev.address in chosen)
            },
        )
    }

    /** Starts the link to [address]: dialled until it answers, read until it drops, and again. */
    private fun dial(address: String, id: String) {
        val link = Link(address, id)
        links[address] = link
        transportThread("bt-$id", onError = { e ->
            link.dead = true
            link.state = Device.ERROR
            link.detail = Failure.text(e)
            note("bt: $id stopped: $e")
            changed()
        }) { run(link) }
    }

    private fun run(link: Link) {
        val adapter = adapter ?: return
        val backoff = Backoff()
        val buffer = ByteArray(BUFFER)
        while (link.running) {
            if (!adapter.isEnabled) {
                link.state = Device.ERROR
                link.detail = "Bluetooth is off"
                changed()
                link.waiter.await(backoff.next())
                continue
            }
            link.state = Device.CONNECTING
            changed()
            var socket: BluetoothSocket? = null
            try {
                socket = adapter.getRemoteDevice(link.address).createRfcommSocketToServiceRecord(SPP)
                link.socket = socket
                if (!link.running) break
                socket.connect()
                backoff.reset()
                link.state = Device.OPEN
                link.detail = null
                note("bt: reading ${link.id}")
                changed()
                handler.post { handler.removeCallbacks(tick); handler.postDelayed(tick, TICK_MS) }
                val input = socket.inputStream
                while (link.running) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n > 0) {
                        link.bytes.addAndGet(n.toLong())
                        send(BridgeProtocol.serial(link.id, buffer, n))
                    }
                }
            } catch (e: SecurityException) {
                // Taken back: dialling again would only fail again. Granting it looks again (rescan).
                link.dead = true
                link.state = Device.ERROR
                link.detail = "Bluetooth is not allowed"
                note("bt: ${link.id}: $e")
                changed()
                return
            } catch (e: Exception) {
                if (!link.running) break
                link.detail = e.message
            } finally {
                link.socket = null
                reporting({}) { socket?.close() }
            }
            if (!link.running) break
            val wait = backoff.next()
            link.state = Device.CONNECTING
            if (link.detail == null) link.detail = "the link dropped"
            changed()
            link.waiter.await(wait)
        }
    }

    /** A link's state changed, on its own thread: the list is made again on ours. */
    private fun changed() = handler.post { if (running) look() }

    /** Ends a link: a connect or a read in progress ends when its socket is closed. */
    private fun shut(link: Link) {
        link.running = false
        link.waiter.wake()
        reporting({}) { link.socket?.close() }
    }

    private fun publish(devices: List<Device>) {
        listed = devices
        list(withBytes(devices))
    }

    private fun withBytes(devices: List<Device>) =
        devices.map { d -> links[d.address]?.let { d.copy(bytes = it.bytes.get()) } ?: d }

    /** What the device calls itself, else its address. */
    private fun nameOf(dev: BluetoothDevice): String = try {
        dev.name?.trim()?.takeIf { it.isNotEmpty() } ?: dev.address
    } catch (_: SecurityException) {
        dev.address
    }.take(MAX_NAME)

    companion object {
        /** The Serial Port Profile's service class, which every Bluetooth NMEA device answers on. */
        private val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        private const val BUFFER = 4096
        private const val TICK_MS = 2_000L
        private const val MAX_NAME = 64

        /**
         * Whether the app may talk to paired devices: BLUETOOTH_CONNECT from Android 12, asked for
         * in the Dock; below that the install-time BLUETOOTH permission, always held.
         */
        fun allowed(context: Context): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }
}
