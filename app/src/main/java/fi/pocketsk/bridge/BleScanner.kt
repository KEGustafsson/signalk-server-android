package fi.pocketsk.bridge

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import fi.pocketsk.net.Backoff
import fi.pocketsk.net.reporting

/**
 * The phone's Bluetooth LE scanner, for the plugin (docs/PLAN.md §7.1): every advertisement heard
 * goes to it as it came, as an `adv` line ([BridgeProtocol.adv]), and the plugin parses it for the
 * server's Bluetooth LE API and for plugins written for BlueZ (runtime-tools/preload/node-ble.js).
 * Nothing is connected to: the app only listens.
 *
 * Two scans, as the plugin asks ([BridgeProtocol.Ble]). The open one hears every device, for
 * finding sensors; Android stops a scan that names no device while the screen is off. The watch
 * scan names the devices a Bluetooth LE plugin was set up for, by address, so those keep being
 * heard in a pocket or a locker; an app's address filter cannot say whether an address is random,
 * and on some stacks a sensor with a random static address (a RuuviTag) is then heard only with
 * the screen on. A packet both scans hear is passed on once ([Repeats]), and restarts keep within
 * Android's five starts in 30 seconds ([ScanBudget]), past which a scan silently hears nothing.
 *
 * Scanning needs Nearby devices (BLUETOOTH_SCAN, Android 12 and later; the manifest says it is
 * never used for location, which also leaves out the few beacons Android counts as location), or
 * precise location below that, with location turned on. Without it nothing is scanned, the server
 * log says so once, and [rescan] tries again when a permission is granted. Every call into the
 * Bluetooth stack can throw, a [SecurityException] for a permission taken back above all; each
 * becomes a line in the server log, never a crash. Everything runs on "bridge-ble".
 */
// Lint cannot follow the permission check in [allowed] into the calls it guards, and each of them
// also catches the SecurityException a permission taken back in between would throw.
@SuppressLint("MissingPermission")
internal class BleScanner(
    context: Context,
    private val send: (String) -> Unit,
    private val note: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val thread = HandlerThread("bridge-ble").apply { start() }
    private val handler = Handler(thread.looper)

    // All below on [thread] only.
    private var running = false
    private var wants = BridgeProtocol.Ble.NONE
    /** What is scanning now: the open scan, and the addresses the watch scan names. */
    private var openOn = false
    private var watching: List<String> = emptyList()
    private val budget = ScanBudget()
    private val repeats = Repeats()
    private var toldNoPermission = false
    private var toldFailure = false
    private val apply = Runnable { look() }

    private val open = Callback("open")
    private val watch = Callback("watch")

    private inner class Callback(val name: String) : ScanCallback() {
        /**
         * The pause before this scan is started again after Android refused it; back to the first
         * once this scan hears anything. One per scan, so the other scan hearing its devices does
         * not cut short the pauses of one that keeps being refused.
         */
        private val failures = Backoff(firstMs = 5_000, maxMs = 300_000)

        override fun onScanResult(callbackType: Int, result: ScanResult) {
            failures.reset()
            heard(result)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            if (results.isNotEmpty()) failures.reset()
            for (r in results) heard(r)
        }

        /**
         * Android refused the scan after [android.bluetooth.le.BluetoothLeScanner.startScan]
         * returned: out of hardware filters, an internal error, too many starts. It is off now,
         * and is started again after a growing pause (5 s doubling to 5 min, back to 5 s once
         * the scan hears anything), since nothing else may ask for it for hours; a phone whose stack
         * has no Bluetooth LE scanning at all is not asked again.
         */
        override fun onScanFailed(errorCode: Int) {
            handler.post {
                if (this === open) openOn = false else watching = emptyList()
                if (!toldFailure) note("ble: the $name scan failed (Android's code $errorCode)")
                toldFailure = true
                if (running && errorCode != ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED) retry(failures.next())
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                // The stack drops every scan with the adapter; they are started afresh when it is back.
                BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> {
                    openOn = false
                    watching = emptyList()
                }
                BluetoothAdapter.STATE_ON -> look()
            }
        }
    }

    /** With the server: scans start once the plugin asks for them. */
    fun start() = handler.post {
        if (running) return@post
        running = true
        toldNoPermission = false
        toldFailure = false
        // Exported, as BluetoothSerial's is: the adapter's state is broadcast by the Bluetooth
        // process, not the system, and a receiver that is not exported never hears it. The action
        // is a protected broadcast, which no other app can send.
        reporting({ note("ble: cannot watch the adapter: $it") }) {
            ContextCompat.registerReceiver(app, receiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED), null, handler, ContextCompat.RECEIVER_EXPORTED)
        }
        look()
    }

    /** What the plugin wants scanned; [BridgeProtocol.Ble.NONE] when it has gone. */
    fun apply(ble: BridgeProtocol.Ble) = handler.post {
        if (ble == wants) return@post
        wants = ble
        if (running) look()
    }

    /** A permission may have been granted: try again what could not start. */
    fun rescan() = handler.post {
        toldNoPermission = false
        toldFailure = false
        if (running) look()
    }

    fun stop() = handler.post {
        if (!running) return@post
        running = false
        handler.removeCallbacks(apply)
        stopScans()
        reporting({ }) { app.unregisterReceiver(receiver) }
    }

    /** For good, when the service goes. */
    fun close() {
        stop()
        thread.quitSafely()
    }

    /** Brings the scans in line with what the plugin wants, as far as the budget lets it now. */
    private fun look() {
        handler.removeCallbacks(apply)
        val wantOpen = running && wants.scan
        val wantWatch = if (running) wants.watch else emptyList()
        if (openOn == wantOpen && watching == wantWatch) return
        if (!wantOpen && wantWatch.isEmpty()) {
            stopScans()
            return
        }
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
        if (!allowed(app)) {
            if (!toldNoPermission) note("ble: Bluetooth LE devices are asked for, but the app may not scan: allow Nearby devices (below Android 12: location) in the Dock")
            toldNoPermission = true
            return
        }
        // A start costs one of the budget's; a stop costs nothing and goes at once, or a scan the
        // plugin is done with would run on until the budget had room. A start held back is tried
        // again when there is, each scan going its own way meanwhile.
        // What is scanning is recorded call by call: a scan is off once its stop went through and
        // on only once its start did, so a start that threw (a permission taken back) is still to
        // do, and [rescan] or the adapter coming back tries it again.
        val now = SystemClock.elapsedRealtime()
        var wait = 0L
        if (openOn != wantOpen) {
            val delay = if (wantOpen) budget.delayFor(now) else 0L
            if (delay > 0) {
                wait = delay
            } else {
                reporting({ note("ble: the open scan: $it") }) {
                    scanner.stopScan(open)
                    openOn = false
                    if (wantOpen) {
                        scanner.startScan(null, settings(), open)
                        budget.started(now)
                        openOn = true
                    }
                }
            }
        }
        if (watching != wantWatch) {
            val delay = if (wantWatch.isNotEmpty()) budget.delayFor(now) else 0L
            if (delay > 0) {
                wait = maxOf(wait, delay)
            } else {
                reporting({ note("ble: the watch scan: $it") }) {
                    scanner.stopScan(watch)
                    watching = emptyList()
                    if (wantWatch.isNotEmpty()) {
                        scanner.startScan(filters(wantWatch), settings(), watch)
                        budget.started(now)
                        watching = wantWatch
                    }
                }
            }
        }
        if (wait > 0) retry(wait)
    }

    private fun retry(wait: Long) {
        handler.postDelayed(apply, wait + 100)
    }

    private fun stopScans() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner != null) {
            reporting({ }) { scanner.stopScan(open) }
            reporting({ }) { scanner.stopScan(watch) }
        }
        openOn = false
        watching = emptyList()
    }

    /** One result from either scan, to the plugin unless the other scan just passed the same packet on. */
    private fun heard(result: ScanResult) {
        val record = result.scanRecord ?: return
        val bytes = record.bytes ?: return
        val mac = result.device?.address ?: return
        val length = minOf(bytes.size, BridgeProtocol.MAX_ADV_BYTES)
        // Synchronised with nothing else: the callbacks of both scans come on the stack's own thread.
        synchronized(repeats) {
            if (!repeats.fresh(mac, bytes.contentHashCode(), SystemClock.elapsedRealtime())) return
        }
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) addressType(result.device) else null
        send(BridgeProtocol.adv(mac, result.rssi, bytes, length, result.isConnectable, type, System.currentTimeMillis()))
    }

    @RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun addressType(device: BluetoothDevice): String? = try {
        when (device.addressType) {
            BluetoothDevice.ADDRESS_TYPE_PUBLIC -> "public"
            BluetoothDevice.ADDRESS_TYPE_RANDOM -> "random"
            else -> null
        }
    } catch (_: SecurityException) {
        null
    }

    companion object {
        /** Balanced: a sensor that advertises every second or two is heard nearly every time, at a fraction of low latency's draw. */
        private fun settings(): ScanSettings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_BALANCED)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()

        /**
         * A filter for each address. The SDK's filter takes no address type (the form that does is
         * for system apps), so on some stacks a sensor with a random static address slips past it
         * and is heard by the open scan alone, with the screen on.
         */
        private fun filters(macs: List<String>): List<ScanFilter> =
            macs.map { ScanFilter.Builder().setDeviceAddress(it).build() }

        /**
         * Whether the app may scan: Nearby devices (BLUETOOTH_SCAN) from Android 12, asked for in
         * the Dock with BLUETOOTH_CONNECT; below that, precise location, which scan results need there.
         */
        fun allowed(context: Context): Boolean {
            val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION
            return ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        }
    }
}
