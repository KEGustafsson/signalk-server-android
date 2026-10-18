package fi.pocketsk.bridge

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import fi.pocketsk.bridge.BridgeProtocol.Subscription
import fi.pocketsk.net.reporting

/**
 * The phone's own sensors, as the plugin asked for them ([apply]): the battery from the sticky
 * ACTION_BATTERY_CHANGED broadcast, and the barometer averaged over the subscribed period. They
 * run only while a plugin wants them, on a thread of their own ("bridge-sensors"), and every
 * reading leaves through [send] as a protocol line. Readings and their units: [Readings].
 */
internal class PhoneSensors(context: Context, private val send: (String) -> Unit, private val note: (String) -> Unit) {
    private val app = context.applicationContext
    private val thread = HandlerThread("bridge-sensors").apply { start() }
    private val handler = Handler(thread.looper)
    private val sensorManager = app.getSystemService(SensorManager::class.java)

    // All below on [thread] only.
    private var subscription = Subscription.NONE
    private var batteryOn = false
    private var lastBattery: Map<String, Double>? = null
    private var lastBatteryAt = 0L
    private var baroOn = false
    private val pressure = Readings.PressureAverage()
    private var toldNoBarometer = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = battery(intent)
    }

    private val baroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) = pressure.add(event.values[0])
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /** Re-reads the battery on a quiet phone, so it is told at least every [Readings.BATTERY_MAX_GAP_MS]. */
    private val batteryTick = object : Runnable {
        override fun run() {
            reporting({ note("battery: $it") }) {
                ContextCompat.registerReceiver(app, null, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)?.let(::battery)
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    private val baroTick = object : Runnable {
        override fun run() {
            pressure.take()?.let { send(BridgeProtocol.sensor(Readings.PRESSURE_PATH, it, Readings.SRC_BARO, System.currentTimeMillis())) }
            handler.postDelayed(this, periodMs(subscription.baroHz))
        }
    }

    /** Starts what [next] names and stops the rest; [Subscription.NONE] stops everything. */
    fun apply(next: Subscription) {
        handler.post { reporting({ note("sensors: $it") }) { switchTo(next) } }
    }

    /** Stops everything and ends the thread; this object is not used again. */
    fun close() {
        handler.post {
            reporting({}) { switchTo(Subscription.NONE) }
            thread.quitSafely()
        }
    }

    private fun switchTo(next: Subscription) {
        val rateChanged = next.baroHz != subscription.baroHz
        subscription = next
        val wantBattery = BridgeProtocol.BATTERY in next.sensors
        if (wantBattery && !batteryOn) {
            batteryOn = true
            lastBattery = null
            // Sticky: the current state arrives at once, then every change.
            ContextCompat.registerReceiver(app, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, handler, ContextCompat.RECEIVER_NOT_EXPORTED)
            handler.postDelayed(batteryTick, TICK_MS)
        } else if (!wantBattery && batteryOn) {
            batteryOn = false
            app.unregisterReceiver(batteryReceiver)
            handler.removeCallbacks(batteryTick)
        }
        val wantBaro = BridgeProtocol.BARO in next.sensors
        if (wantBaro && !baroOn) {
            val sensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PRESSURE)
            if (sensor == null) {
                if (!toldNoBarometer) note("this phone has no barometer")
                toldNoBarometer = true
            } else {
                baroOn = true
                pressure.take()
                sensorManager.registerListener(baroListener, sensor, BARO_SAMPLING_US, handler)
                handler.postDelayed(baroTick, periodMs(next.baroHz))
            }
        } else if (!wantBaro && baroOn) {
            baroOn = false
            sensorManager?.unregisterListener(baroListener)
            handler.removeCallbacks(baroTick)
        } else if (baroOn && rateChanged) {
            handler.removeCallbacks(baroTick)
            handler.postDelayed(baroTick, periodMs(next.baroHz))
        }
    }

    private fun battery(intent: Intent) {
        if (!batteryOn) return
        val temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
        val now = Readings.battery(
            Readings.Battery(
                level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
                scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
                voltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0),
                tenthsCelsius = temperature,
            ),
        )
        val at = SystemClock.elapsedRealtime()
        if (now.isEmpty() || !Readings.batteryDue(lastBattery, lastBatteryAt, now, at)) return
        lastBattery = now
        lastBatteryAt = at
        val ts = System.currentTimeMillis()
        for ((path, value) in now) send(BridgeProtocol.sensor(path, value, Readings.SRC_BATTERY, ts))
    }

    private fun periodMs(hz: Double): Long = (1000.0 / hz).toLong().coerceAtLeast(1000L)

    private companion object {
        const val TICK_MS = 60_000L
        /** The barometer sampled once a second: enough for a mean, and the sensor's low-power pace. */
        const val BARO_SAMPLING_US = 1_000_000
    }
}
