package fi.pocketsk.bridge

import fi.pocketsk.bridge.Readings.Battery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingsTest {
    @Test
    fun theBatteryInSignalKUnits() {
        assertEquals(
            mapOf(
                "electrical.batteries.phone.capacity.stateOfCharge" to 0.42,
                "electrical.batteries.phone.voltage" to 3.912,
                "electrical.batteries.phone.temperature" to 302.65,
            ),
            Readings.battery(Battery(level = 42, scale = 100, voltageMv = 3912, tenthsCelsius = 295)),
        )
        assertEquals(emptyMap<String, Double>(), Readings.battery(Battery(level = -1, scale = 100, voltageMv = 0, tenthsCelsius = null)))
        assertEquals(1.0, Readings.battery(Battery(120, 100, 0, null)).values.single(), 0.0)
    }

    @Test
    fun theBatteryIsToldWhenItChangesButNotTooOften() {
        val a = mapOf("x.capacity.stateOfCharge" to 0.50, "x.voltage" to 3.90, "x.temperature" to 300.0)
        assertTrue(Readings.batteryDue(null, 0, a, 0))
        assertFalse("a change within the gap waits", Readings.batteryDue(a, 0, a + ("x.voltage" to 4.2), 9_999))
        assertFalse("nothing changed", Readings.batteryDue(a, 0, a, 10_000))
        assertTrue("a percent", Readings.batteryDue(a, 0, a + ("x.capacity.stateOfCharge" to 0.51), 10_000))
        assertTrue("10 mV", Readings.batteryDue(a, 0, a + ("x.voltage" to 3.91), 10_000))
        assertFalse("5 mV", Readings.batteryDue(a, 0, a + ("x.voltage" to 3.905), 10_000))
        assertTrue("half a degree", Readings.batteryDue(a, 0, a + ("x.temperature" to 300.5), 10_000))
        assertTrue("a path came or went", Readings.batteryDue(a, 0, a - "x.temperature", 10_000))
        assertTrue("quiet for five minutes", Readings.batteryDue(a, 0, a, 300_000))
    }

    @Test
    fun aGnssFixInSignalKUnits() {
        val g = Readings.gnss(Readings.Fix(latitude = 60.45, longitude = 22.27, altitudeM = 3.5, speedMs = 2.5f, bearingDeg = 90f))!!
        assertEquals(mapOf("latitude" to 60.45, "longitude" to 22.27, "altitude" to 3.5), g.position)
        assertEquals(2.5, g.speedOverGround!!, 1e-9)
        assertEquals(Math.PI / 2, g.courseOverGroundTrue!!, 1e-6)
    }

    @Test
    fun whatAFixDoesNotKnowIsLeftOut() {
        val g = Readings.gnss(Readings.Fix(latitude = -33.9, longitude = 151.2))!!
        assertEquals(setOf("latitude", "longitude"), g.position.keys)
        assertNull(g.speedOverGround)
        assertNull(g.courseOverGroundTrue)
        val odd = Readings.gnss(Readings.Fix(0.0, 0.0, altitudeM = Double.NaN, speedMs = -1f, bearingDeg = Float.NaN))!!
        assertEquals(setOf("latitude", "longitude"), odd.position.keys)
        assertNull(odd.speedOverGround)
        assertNull(odd.courseOverGroundTrue)
    }

    @Test
    fun aCourseIsNormalisedIntoOneTurn() {
        assertEquals(0.0, Readings.gnss(Readings.Fix(0.0, 0.0, bearingDeg = 360f))!!.courseOverGroundTrue!!, 1e-9)
        assertEquals(Math.toRadians(350.0), Readings.gnss(Readings.Fix(0.0, 0.0, bearingDeg = -10f))!!.courseOverGroundTrue!!, 1e-6)
    }

    @Test
    fun aFixOffTheEarthIsNoFix() {
        assertNull(Readings.gnss(Readings.Fix(91.0, 0.0)))
        assertNull(Readings.gnss(Readings.Fix(0.0, -181.0)))
        assertNull(Readings.gnss(Readings.Fix(Double.NaN, 0.0)))
    }

    @Test
    fun pressureIsAveragedPerPeriodInPascals() {
        val avg = Readings.PressureAverage()
        assertNull(avg.take())
        avg.add(1013.0f)
        avg.add(1015.0f)
        avg.add(Float.NaN)
        avg.add(5000f)
        assertEquals(101400.0, avg.take()!!, 0.01)
        assertNull("each period starts empty", avg.take())
    }

    @Test
    fun anAttitudeHasYawOnlyWithANorth() {
        val e = Attitude.Euler(0.1, -0.2, 1.5)
        assertEquals(mapOf("roll" to 0.1, "pitch" to -0.2), Readings.attitude(e, withYaw = false))
        assertEquals(mapOf("roll" to 0.1, "pitch" to -0.2, "yaw" to 1.5), Readings.attitude(e, withYaw = true))
    }

    @Test
    fun theCabinsSensorsAreInSignalKsUnits() {
        assertEquals(294.15, Readings.cabinTemperature(21.0)!!, 1e-9)
        assertNull(Readings.cabinTemperature(-273.0))
        assertEquals(0.55, Readings.cabinHumidity(55.0)!!, 1e-9)
        assertNull(Readings.cabinHumidity(101.0))
        assertEquals(320.0, Readings.cabinLight(320.0)!!, 1e-9)
        assertNull(Readings.cabinLight(-1.0))
        assertNull(Readings.cabinLight(Double.NaN))
    }

    @Test
    fun aMeanIsTakenPerPeriod() {
        val m = Readings.Mean()
        assertNull(m.take())
        m.add(1.0)
        m.add(3.0)
        m.add(Double.NaN)
        assertEquals(2.0, m.take()!!, 1e-9)
        assertNull("each period starts empty", m.take())
    }
}
