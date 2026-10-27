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
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.telephony.TelephonyManager
import androidx.core.content.ContextCompat
import androidx.core.location.GnssStatusCompat
import androidx.core.location.LocationListenerCompat
import androidx.core.location.LocationManagerCompat
import androidx.core.location.LocationRequestCompat
import androidx.core.os.ExecutorCompat
import fi.pocketsk.bridge.BridgeProtocol.Subscription
import fi.pocketsk.net.reporting

/**
 * The phone's own sensors, as the plugin asked for them ([apply]): the battery from the sticky
 * ACTION_BATTERY_CHANGED broadcast, the barometer averaged over the subscribed period, the GNSS
 * receiver at the subscribed rate with the count of satellites in its fix, the boat's attitude
 * and rate of turn from the rotation sensor and the gyroscope, its magnetic heading, the cabin's
 * temperature, humidity and light where the phone has those sensors, and the cellular network it
 * is on. They run only while a plugin wants them, on a thread of their own ("bridge-sensors"),
 * and every reading leaves through [send] as a protocol line. Readings and their units:
 * [Readings]; the geometry of the attitude, and how the phone's mounting is found ([level]):
 * [Attitude].
 *
 * The attitude uses the game rotation vector, which has no magnetometer and so is not thrown by
 * the steel and wiring around a mounted phone; only when the plugin also wants the heading does
 * it switch to the rotation vector, which has a north, the magnetic one.
 *
 * GNSS comes from the platform's own GPS provider through AndroidX's LocationManagerCompat
 * (Play services' fused provider is a third-party library, and the boat wants the receiver, not
 * a network guess). It needs precise location granted; without it the plugin's GNSS stays off
 * and the log says so once, and [reapply] after a grant starts it.
 *
 * The cellular reading asks for no permission: the signal strength, the operator's name and
 * roaming are open to every app, while the network's generation (LTE, 5G) would need the phone
 * permission, which a Signal K server has no other use for. It is the modem's last report, read
 * once a period rather than listened to, since a listener below Android 12 is a deprecated API.
 */
internal class PhoneSensors(context: Context, private val send: (String) -> Unit, private val note: (String) -> Unit) {
    private val app = context.applicationContext
    private val thread = HandlerThread("bridge-sensors").apply { start() }
    private val handler = Handler(thread.looper)
    private val sensorManager = app.getSystemService(SensorManager::class.java)

    // All below on [thread] only.
    private var subscription = Subscription.NONE

    /** The paths a reading was sent for, by their source: what [clear] nulls when the sensor stops. Handler thread only. */
    private val published = HashMap<String, String>()

    /**
     * The paths [clear] nulled, by their source, until the next plugin connects: [connected]
     * sends their nulls again. A null that left may still not have reached the plugin: the
     * plugin going is what stops the sensors, so its own nulls find no peer, and one queued
     * just before a peer closed goes with the queue. Nulls the plugin already had cost nothing.
     * A path read again leaves it. Handler thread only.
     */
    private val cleared = HashMap<String, String>()
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
    /**
     * The log has said the GPS is waiting for a fix, and whether one has come, since the plugin
     * asked for GNSS: a receiver indoors may never get one, and without these lines a GPS that
     * is on and a GPS that is not look the same in the log. A [reapply] keeps them.
     */
    private var toldWaiting = false
    private var hadFix = false
    /** Satellites used in the last fix, from the GNSS status; null until the receiver says. */
    private var satellites: Int? = null
    private val locationManager = app.getSystemService(LocationManager::class.java)
    private val executor = ExecutorCompat.create(handler)

    private val sensorPrefs = SensorPrefs(app)
    /** The rotation sensor registered: a Sensor.TYPE_* while the attitude or heading runs, else 0. */
    private var motionType = 0
    private var motionHzOn = BridgeProtocol.ATTITUDE_DEFAULT_HZ
    private var gyroOn = false
    private var rotation: Attitude.Rotation? = null
    private var rotationAt = 0L
    private var bowOffsetDegOn = 0.0
    private var mount = Attitude.mount(sensorPrefs.level, 0.0)
    private val turnRate = Readings.Mean()
    private var toldNoRotation = false
    private var toldNoCompass = false
    private var environmentOn = false
    private val cabinTemperature = Readings.Mean()
    private val cabinHumidity = Readings.Mean()
    private val cabinLight = Readings.Mean()
    /**
     * The last value each cabin sensor gave. They report on change only, so a steady cabin sends
     * nothing for a period, and the value then is still the last one.
     */
    private var lastTemperature: Double? = null
    private var lastHumidity: Double? = null
    private var lastLight: Double? = null
    private var toldNoEnvironment = false
    private val telephony = app.getSystemService(TelephonyManager::class.java)
    private var cellularOn = false
    private var cellularHzOn = BridgeProtocol.CELLULAR_DEFAULT_HZ
    private var toldNoCellular = false

    private val gnssListener = LocationListenerCompat { location -> gnss(location) }

    private val gnssStatus = object : GnssStatusCompat.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatusCompat) {
            satellites = (0 until status.satelliteCount).count { status.usedInFix(it) }
        }
    }

    private val rotationListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            Attitude.rotation(event.values)?.let {
                rotation = it
                rotationAt = SystemClock.elapsedRealtime()
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    /** Rate of turn from each gyroscope sample, through the latest rotation, averaged per period. */
    private val gyroListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val r = rotation ?: return
            val v = event.values
            turnRate.add(Attitude.rateOfTurn(r, Attitude.Vec(v[0].toDouble(), v[1].toDouble(), v[2].toDouble())))
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val environmentListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val v = event.values[0].toDouble()
            when (event.sensor.type) {
                Sensor.TYPE_AMBIENT_TEMPERATURE -> Readings.cabinTemperature(v)?.let(cabinTemperature::add)
                Sensor.TYPE_RELATIVE_HUMIDITY -> Readings.cabinHumidity(v)?.let(cabinHumidity::add)
                Sensor.TYPE_LIGHT -> Readings.cabinLight(v)?.let(cabinLight::add)
            }
        }
        override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
    }

    private val motionTick = object : Runnable {
        override fun run() {
            motion()
            handler.postDelayed(this, motionPeriodMs(subscription.attitudeHz))
        }
    }

    private val environmentTick = object : Runnable {
        override fun run() {
            environment()
            handler.postDelayed(this, periodMs(subscription.environmentHz))
        }
    }

    private val cellularTick = object : Runnable {
        override fun run() {
            if (!cellularOn) return
            if (cellular()) handler.postDelayed(this, periodMs(subscription.cellularHz))
        }
    }

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
            pressure.take()?.let { put(Readings.PRESSURE_PATH, Readings.SRC_BARO, BridgeProtocol.sensor(Readings.PRESSURE_PATH, it, Readings.SRC_BARO, System.currentTimeMillis())) }
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

    /**
     * "Level now": the boat lies level and still, and the phone's present tilt becomes its
     * mounting ([Attitude.mount]), kept in [SensorPrefs] for every later start. Needs the
     * rotation sensor running (the attitude or heading subscribed); the log says how it went.
     */
    fun level() {
        handler.post {
            reporting({ note("attitude: $it") }) {
                val r = freshRotation()
                if (r == null) {
                    note("attitude: cannot level while the attitude is not running; it runs with the server, with attitude on in the bridge plugin's settings")
                    return@reporting
                }
                val down = r.downInPhone
                sensorPrefs.level = down
                mount = Attitude.mount(down, Math.toRadians(bowOffsetDegOn))
                note("attitude: levelled; roll and pitch read 0 with the phone as it sits now")
            }
        }
    }

    /** A plugin connected: the clears since the last one go to it again, before any new reading. */
    fun connected() {
        handler.post {
            val ts = System.currentTimeMillis()
            for ((path, src) in cleared) send(BridgeProtocol.sensor(path, null as Double?, src, ts))
            cleared.clear()
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
            clear(Readings.SRC_BATTERY)
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
            clear(Readings.SRC_BARO)
        } else if (baroOn && rateChanged) {
            handler.removeCallbacks(baroTick)
            handler.postDelayed(baroTick, periodMs(next.baroHz))
        }
        val wantGnss = BridgeProtocol.GNSS in next.sensors
        if (wantGnss && (!gnssOn || gnssRateChanged)) startGnss(next.gnssHz)
        else if (!wantGnss) {
            // Also when a restart refused for a revoked grant left the GPS off with the notes made.
            if (gnssOn) stopGnss()
            toldWaiting = false
            hadFix = false
            clear(Readings.SRC_GNSS)
            sogSent = false
            cogSent = false
        }
        switchMotion(next)
        switchEnvironment(next, rateChanged = next.environmentHz != previousEnvironmentHz)
        previousEnvironmentHz = next.environmentHz
        switchCellular(next)
    }

    private var previousEnvironmentHz = BridgeProtocol.ENVIRONMENT_DEFAULT_HZ

    /**
     * The rotation sensor while the attitude or the heading is wanted (the one with a north only
     * for the heading), the gyroscope with the attitude, and the tick that reports them.
     */
    private fun switchMotion(next: Subscription) {
        if (next.bowOffsetDeg != bowOffsetDegOn) {
            bowOffsetDegOn = next.bowOffsetDeg
            mount = Attitude.mount(sensorPrefs.level, Math.toRadians(bowOffsetDegOn))
        }
        val wantAttitude = BridgeProtocol.ATTITUDE in next.sensors
        val wantHeading = BridgeProtocol.HEADING in next.sensors
        val type = if (wantAttitude || wantHeading) rotationType(wantHeading) else 0
        if (wantHeading && type != Sensor.TYPE_ROTATION_VECTOR && !toldNoCompass) {
            note("this phone has no compass (rotation vector): no magnetic heading for Signal K")
            toldNoCompass = true
        }
        if (type != motionType) {
            if (motionType != 0) {
                sensorManager?.unregisterListener(rotationListener)
                handler.removeCallbacks(motionTick)
                rotation = null
            }
            motionType = type
            if (type != 0) {
                sensorManager.registerListener(rotationListener, sensorManager.getDefaultSensor(type), MOTION_SAMPLING_US, handler)
                motionHzOn = next.attitudeHz
                handler.postDelayed(motionTick, motionPeriodMs(next.attitudeHz))
            }
        } else if (type != 0 && next.attitudeHz != motionHzOn) {
            motionHzOn = next.attitudeHz
            handler.removeCallbacks(motionTick)
            handler.postDelayed(motionTick, motionPeriodMs(next.attitudeHz))
        }
        val gyro = if (wantAttitude && type != 0) sensorManager?.getDefaultSensor(Sensor.TYPE_GYROSCOPE) else null
        if (gyro != null && !gyroOn) {
            gyroOn = true
            turnRate.take()
            sensorManager.registerListener(gyroListener, gyro, MOTION_SAMPLING_US, handler)
        } else if (gyro == null && gyroOn) {
            gyroOn = false
            sensorManager?.unregisterListener(gyroListener)
        }
        // What is no longer sent is cleared: the attitude and rate of turn, the heading, or both.
        if (!wantAttitude || type == 0) clear(Readings.SRC_IMU)
        if (!wantHeading || type != Sensor.TYPE_ROTATION_VECTOR) clear(Readings.SRC_COMPASS)
    }

    /**
     * The rotation sensor to use: the rotation vector, which has a magnetic north, for the
     * heading; otherwise the game rotation vector, steadier for roll and pitch beside steel; the
     * other where the phone lacks one; 0 where it has neither.
     */
    private fun rotationType(magnetic: Boolean): Int {
        val manager = sensorManager ?: return 0
        val order = if (magnetic) listOf(Sensor.TYPE_ROTATION_VECTOR, Sensor.TYPE_GAME_ROTATION_VECTOR) else listOf(Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR)
        val type = order.firstOrNull { manager.getDefaultSensor(it) != null } ?: 0
        if (type == 0 && !toldNoRotation) {
            note("this phone has no rotation sensor: no attitude or heading for Signal K")
            toldNoRotation = true
        }
        return type
    }

    /** The cabin's temperature, humidity and light sensors, those the phone has, while wanted. */
    private fun switchEnvironment(next: Subscription, rateChanged: Boolean) {
        val want = BridgeProtocol.ENVIRONMENT in next.sensors
        if (want && !environmentOn) {
            val manager = sensorManager ?: return
            val present = listOf(Sensor.TYPE_AMBIENT_TEMPERATURE, Sensor.TYPE_RELATIVE_HUMIDITY, Sensor.TYPE_LIGHT).mapNotNull(manager::getDefaultSensor)
            if (present.isEmpty()) {
                if (!toldNoEnvironment) note("this phone has no temperature, humidity or light sensor")
                toldNoEnvironment = true
                return
            }
            environmentOn = true
            for (m in listOf(cabinTemperature, cabinHumidity, cabinLight)) m.take()
            lastTemperature = null
            lastHumidity = null
            lastLight = null
            for (s in present) manager.registerListener(environmentListener, s, ENVIRONMENT_SAMPLING_US, handler)
            handler.postDelayed(environmentTick, periodMs(next.environmentHz))
        } else if (!want && environmentOn) {
            environmentOn = false
            sensorManager?.unregisterListener(environmentListener)
            handler.removeCallbacks(environmentTick)
            clear(Readings.SRC_ENVIRONMENT)
        } else if (environmentOn && rateChanged) {
            handler.removeCallbacks(environmentTick)
            handler.postDelayed(environmentTick, periodMs(next.environmentHz))
        }
    }

    /** The cellular reading while wanted, on a phone with a cellular radio: one at once, then one a period. */
    private fun switchCellular(next: Subscription) {
        val want = BridgeProtocol.CELLULAR in next.sensors
        if (want && !cellularOn) {
            if (telephony == null || !hasCellularRadio()) {
                if (!toldNoCellular) note("this phone has no cellular radio: no cellular signal for Signal K")
                toldNoCellular = true
                return
            }
            cellularOn = true
            cellularHzOn = next.cellularHz
            handler.post(cellularTick)
        } else if (!want && cellularOn) {
            cellularOn = false
            handler.removeCallbacks(cellularTick)
            clear(Readings.SRC_CELLULAR)
        } else if (cellularOn && next.cellularHz != cellularHzOn) {
            cellularHzOn = next.cellularHz
            handler.removeCallbacks(cellularTick)
            handler.postDelayed(cellularTick, periodMs(next.cellularHz))
        }
    }

    /**
     * Whether the phone has a radio to read: from Android 13 the feature the telephony calls below
     * are tied to (a phone without it throws UnsupportedOperationException from them), before that
     * telephony at all.
     */
    private fun hasCellularRadio(): Boolean = app.packageManager.hasSystemFeature(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) PackageManager.FEATURE_TELEPHONY_RADIO_ACCESS else PackageManager.FEATURE_TELEPHONY,
    )

    /**
     * One cellular reading: the bars, the best cell's power, the operator and roaming. With no
     * signal report, or one with no measurement in it (no SIM, the modem off in flight mode), the
     * strength and power are null, so the server stops showing the last ones as current. A call
     * the platform refuses clears all four the same way and stops the reading, with a line in the
     * log, until the sensors are switched again ([apply], [reapply]); returns whether to go on.
     */
    private fun cellular(): Boolean {
        val tm = telephony ?: return false
        val ts = System.currentTimeMillis()
        // Only the platform's calls are inside: a failure to read the phone, not to send.
        val reading = try {
            val strength = tm.signalStrength
            val cells = strength?.cellSignalStrengths.orEmpty().map { Readings.Cell(it.level, it.dbm) }
            Readings.Cellular(
                strength = strength?.let { Readings.cellularStrength(it.level, cells) },
                dbm = Readings.cellularDbm(cells),
                operator = tm.networkOperatorName,
                roaming = tm.isNetworkRoaming,
            )
        } catch (e: RuntimeException) {
            // SecurityException or UnsupportedOperationException from a vendor's telephony: asking
            // again every period would only fill the log. The next [apply] or [reapply] tries again.
            cellularOn = false
            note("cellular: cannot read the cellular network ($e); no cellular signal for Signal K")
            null
        }
        val r = reading ?: Readings.Cellular.NONE
        put(Readings.CELLULAR_STRENGTH_PATH, Readings.SRC_CELLULAR, BridgeProtocol.sensor(Readings.CELLULAR_STRENGTH_PATH, r.strength, Readings.SRC_CELLULAR, ts))
        put(Readings.CELLULAR_DBM_PATH, Readings.SRC_CELLULAR, BridgeProtocol.sensor(Readings.CELLULAR_DBM_PATH, r.dbm, Readings.SRC_CELLULAR, ts))
        put(Readings.CELLULAR_OPERATOR_PATH, Readings.SRC_CELLULAR, BridgeProtocol.sensorText(Readings.CELLULAR_OPERATOR_PATH, r.operator, Readings.SRC_CELLULAR, ts))
        put(Readings.CELLULAR_ROAMING_PATH, Readings.SRC_CELLULAR, BridgeProtocol.sensorFlag(Readings.CELLULAR_ROAMING_PATH, r.roaming, Readings.SRC_CELLULAR, ts))
        return reading != null
    }

    /** The latest rotation, unless the sensor has gone quiet: an old one is no attitude. */
    private fun freshRotation(): Attitude.Rotation? =
        rotation?.takeIf { SystemClock.elapsedRealtime() - rotationAt <= ROTATION_STALE_MS }

    private fun motion() {
        val r = freshRotation() ?: return
        val ts = System.currentTimeMillis()
        val e = Attitude.euler(r, mount)
        val northed = motionType == Sensor.TYPE_ROTATION_VECTOR && BridgeProtocol.HEADING in subscription.sensors
        if (BridgeProtocol.ATTITUDE in subscription.sensors) {
            put(Readings.ATTITUDE_PATH, Readings.SRC_IMU, BridgeProtocol.sensor(Readings.ATTITUDE_PATH, Readings.attitude(e, withYaw = northed), Readings.SRC_IMU, ts))
            turnRate.take()?.let { put(Readings.RATE_OF_TURN_PATH, Readings.SRC_IMU, BridgeProtocol.sensor(Readings.RATE_OF_TURN_PATH, it, Readings.SRC_IMU, ts)) }
        }
        if (northed) put(Readings.HEADING_MAGNETIC_PATH, Readings.SRC_COMPASS, BridgeProtocol.sensor(Readings.HEADING_MAGNETIC_PATH, e.yaw, Readings.SRC_COMPASS, ts))
    }

    /** Each cabin sensor's mean for the period, or its last value when it reported no change. */
    private fun environment() {
        val ts = System.currentTimeMillis()
        (cabinTemperature.take() ?: lastTemperature)?.let {
            lastTemperature = it
            put(Readings.INSIDE_TEMPERATURE_PATH, Readings.SRC_ENVIRONMENT, BridgeProtocol.sensor(Readings.INSIDE_TEMPERATURE_PATH, it, Readings.SRC_ENVIRONMENT, ts))
        }
        (cabinHumidity.take() ?: lastHumidity)?.let {
            lastHumidity = it
            put(Readings.INSIDE_HUMIDITY_PATH, Readings.SRC_ENVIRONMENT, BridgeProtocol.sensor(Readings.INSIDE_HUMIDITY_PATH, it, Readings.SRC_ENVIRONMENT, ts))
        }
        (cabinLight.take() ?: lastLight)?.let {
            lastLight = it
            put(Readings.INSIDE_ILLUMINANCE_PATH, Readings.SRC_ENVIRONMENT, BridgeProtocol.sensor(Readings.INSIDE_ILLUMINANCE_PATH, it, Readings.SRC_ENVIRONMENT, ts))
        }
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
        if (gnssOn) {
            LocationManagerCompat.removeUpdates(manager, gnssListener)
            LocationManagerCompat.unregisterGnssStatusCallback(manager, gnssStatus)
        }
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
        if (!hadFix && !toldWaiting) {
            toldWaiting = true
            // Requested anyway: the fixes start once location is turned on.
            if (!LocationManagerCompat.isLocationEnabled(manager)) note("gnss: location is turned off in the phone's settings; no fixes until it is on")
            note("gnss: the phone's GPS is on; waiting for a fix, which needs a view of the sky")
        }
        // The satellites in each fix; a phone that cannot tell, or a grant taken back just now,
        // costs only the count, never the fixes.
        try {
            if (!LocationManagerCompat.registerGnssStatusCallback(manager, executor, gnssStatus)) satellites = null
        } catch (_: SecurityException) {
            satellites = null
        }
    }

    private fun stopGnss() {
        gnssOn = false
        satellites = null
        val manager = locationManager ?: return
        LocationManagerCompat.unregisterGnssStatusCallback(manager, gnssStatus)
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
        if (!hadFix) {
            hadFix = true
            note("gnss: first fix" + (satellites?.let { ", $it satellites" } ?: ""))
        }
        put(Readings.POSITION_PATH, Readings.SRC_GNSS, BridgeProtocol.sensor(Readings.POSITION_PATH, fix.position, Readings.SRC_GNSS, ts))
        // A fix without speed or course (a bearing is often dropped at rest) clears the last one sent.
        if (fix.speedOverGround != null || sogSent) put(Readings.SOG_PATH, Readings.SRC_GNSS, BridgeProtocol.sensor(Readings.SOG_PATH, fix.speedOverGround, Readings.SRC_GNSS, ts))
        if (fix.courseOverGroundTrue != null || cogSent) put(Readings.COG_PATH, Readings.SRC_GNSS, BridgeProtocol.sensor(Readings.COG_PATH, fix.courseOverGroundTrue, Readings.SRC_GNSS, ts))
        sogSent = fix.speedOverGround != null
        cogSent = fix.courseOverGroundTrue != null
        satellites?.let { put(Readings.SATELLITES_PATH, Readings.SRC_GNSS, BridgeProtocol.sensor(Readings.SATELLITES_PATH, it.toDouble(), Readings.SRC_GNSS, ts)) }
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
        for ((path, value) in now) put(path, Readings.SRC_BATTERY, BridgeProtocol.sensor(path, value, Readings.SRC_BATTERY, ts))
    }

    /** Sends [line], a reading for [path] from [src], and remembers the path for [clear]. */
    private fun put(path: String, src: String, line: String) {
        published[path] = src
        cleared.remove(path)
        send(line)
    }

    /**
     * A sensor stopped (the plugin no longer asks for it, the power profile left it out, the
     * plugin went): each path it sent a reading for gets a null, which is how Signal K says there
     * is no reading now, so the server stops showing the last one as current. Each is kept in
     * [cleared] and sent again to the next plugin that connects.
     */
    private fun clear(src: String) {
        val paths = published.filterValues { it == src }.keys
        if (paths.isEmpty()) return
        val ts = System.currentTimeMillis()
        for (path in paths.toList()) {
            send(BridgeProtocol.sensor(path, null as Double?, src, ts))
            published.remove(path)
            cleared[path] = src
        }
    }

    private fun periodMs(hz: Double): Long = (1000.0 / hz).toLong().coerceAtLeast(1000L)

    private fun motionPeriodMs(hz: Double): Long = (1000.0 / hz).toLong().coerceAtLeast(100L)

    private companion object {
        const val TICK_MS = 60_000L
        /** The barometer sampled once a second: enough for a mean, and the sensor's low-power pace. */
        const val BARO_SAMPLING_US = 1_000_000
        /** The rotation sensor and the gyroscope at 50 Hz, SENSOR_DELAY_GAME's pace: a roll is a few seconds long. */
        const val MOTION_SAMPLING_US = 20_000
        /** The cabin's sensors once a second, like the barometer. */
        const val ENVIRONMENT_SAMPLING_US = 1_000_000
        /** A rotation older than this is a sensor gone quiet, not the boat's attitude. */
        const val ROTATION_STALE_MS = 2_000L
    }
}
