package fi.pocketsk.bridge

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.os.ExecutorCompat
import fi.pocketsk.bridge.BridgeProtocol.Subscription
import fi.pocketsk.net.reporting

/**
 * The phone's own sensors, as the plugin asked for them ([apply]): the battery from the sticky
 * ACTION_BATTERY_CHANGED broadcast, the barometer averaged over the subscribed period, and the
 * GNSS receiver at the subscribed rate. They run only while a plugin wants them, on a thread of
 * their own ("bridge-sensors"), and every reading leaves through [send] as a protocol line.
 * Readings and their units: [Readings].
 *
 * GNSS comes from the platform's own GPS provider through AndroidX's LocationManagerCompat
 * (Play services' fused provider is a third-party library, and the boat wants the receiver, not
 * a network guess). It needs precise location granted; without it the plugin's GNSS stays off
 * and the log says so once, and [reapply] after a grant starts it.
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
    private var gnssOn = false
    /** The rate the running GNSS request was made with. */
    private var gnssHzOn = BridgeProtocol.GNSS_DEFAULT_HZ
    private val gnssRateChanged get() = subscription.gnssHz != gnssHzOn
    private var toldNoLocation = false
    /**
     * Speed and course were in the last fix sent, so a fix without them must say they are gone.
     * Kept across a stop and a new request: the server still holds what was sent before it.
     */
    private var sogSent = false
    private var cogSent = false
    private val locationManager = app.getSystemService(LocationManager::class.java)
    private val executor = ExecutorCompat.create(handler)

    private val gnssListener = LocationListenerCompat { location -> gnss(location) }

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

    /**
     * Tries again what the subscription wants and could not start: after a permission was granted
     * or the service gained the `location` foreground type. GNSS is registered afresh even when it
     * is on: a request made while the service had no location type (a start at boot) may have
     * been accepted and yet deliver nothing.
     */
    fun reapply() {
        handler.post {
            reporting({ note("sensors: $it") }) {
                if (gnssOn) stopGnss()
                switchTo(subscription)
            }
        }
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
        val wantGnss = BridgeProtocol.GNSS in next.sensors
        if (wantGnss && (!gnssOn || gnssRateChanged)) startGnss(next.gnssHz)
        else if (!wantGnss && gnssOn) stopGnss()
    }

    private fun startGnss(hz: Double) {
        val manager = locationManager ?: return
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            if (!toldNoLocation) note("precise location is not allowed: no GNSS for Signal K from this phone")
            toldNoLocation = true
            return
        }
        if (!manager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
            if (!toldNoLocation) note("this phone has no GPS receiver")
            toldNoLocation = true
            return
        }
        if (gnssOn) LocationManagerCompat.removeUpdates(manager, gnssListener)
        val intervalMs = (1000.0 / hz).toLong()
        val request = LocationRequestCompat.Builder(intervalMs)
            .setQuality(LocationRequestCompat.QUALITY_HIGH_ACCURACY)
            .setMinUpdateIntervalMillis(intervalMs)
            .build()
        try {
            LocationManagerCompat.requestLocationUpdates(manager, LocationManager.GPS_PROVIDER, request, executor, gnssListener)
        } catch (e: SecurityException) {
            // Revoked between the check above and here.
            gnssOn = false
            note("precise location was taken back: no GNSS for Signal K ($e)")
            return
        }
        gnssOn = true
        gnssHzOn = hz
        toldNoLocation = false
    }

    private fun stopGnss() {
        gnssOn = false
        val manager = locationManager ?: return
        try {
            LocationManagerCompat.removeUpdates(manager, gnssListener)
        } catch (_: SecurityException) {
            // Taken back already: the platform has dropped the request itself.
        }
    }

    private fun gnss(location: Location) {
        if (!gnssOn) return
        val fix = Readings.gnss(
            Readings.Fix(
                latitude = location.latitude,
                longitude = location.longitude,
                altitudeM = if (location.hasAltitude()) location.altitude else null,
                speedMs = if (location.hasSpeed()) location.speed else null,
                bearingDeg = if (location.hasBearing()) location.bearing else null,
            ),
        ) ?: return
        val ts = location.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        send(BridgeProtocol.sensor(Readings.POSITION_PATH, fix.position, Readings.SRC_GNSS, ts))
        // A fix without speed or course (a bearing is often dropped at rest) clears the last one sent.
        if (fix.speedOverGround != null || sogSent) send(BridgeProtocol.sensor(Readings.SOG_PATH, fix.speedOverGround, Readings.SRC_GNSS, ts))
        if (fix.courseOverGroundTrue != null || cogSent) send(BridgeProtocol.sensor(Readings.COG_PATH, fix.courseOverGroundTrue, Readings.SRC_GNSS, ts))
        sogSent = fix.speedOverGround != null
        cogSent = fix.courseOverGroundTrue != null
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
