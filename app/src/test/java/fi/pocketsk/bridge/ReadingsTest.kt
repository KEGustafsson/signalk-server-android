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
}
