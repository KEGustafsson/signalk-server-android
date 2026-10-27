package fi.pocketsk.power

import fi.pocketsk.bridge.BridgeProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerRulesTest {
    private val asked = BridgeProtocol.Subscription(
        sensors = BridgeProtocol.SENSORS,
        gnssHz = 5.0,
        attitudeHz = 10.0,
        serial = BridgeProtocol.Serial.NONE,
        ble = BridgeProtocol.Ble(scan = true, watch = listOf("AA:BB:CC:DD:EE:FF")),
    )

    @Test
    fun harbourAndPassageServeWhatIsAsked() {
        assertEquals(asked, PowerRules.subscription(PowerProfile.HARBOR, asked))
        assertEquals(asked, PowerRules.subscription(PowerProfile.PASSAGE, asked))
    }

    @Test
    fun batterySaverKeepsGnssAtOnceASecondAndTheBattery() {
        val s = PowerRules.subscription(PowerProfile.SAVER, asked)
        assertEquals(setOf(BridgeProtocol.GNSS, BridgeProtocol.BATTERY), s.sensors)
        assertEquals(1.0, s.gnssHz, 0.0)
        assertEquals(BridgeProtocol.Ble.NONE, s.ble)
        assertEquals(asked.serial, s.serial)
        assertEquals(asked.bowOffsetDeg, s.bowOffsetDeg, 0.0)
    }

    @Test
    fun batterySaverAddsNothingThatWasNotAsked() {
        val slow = asked.copy(sensors = setOf(BridgeProtocol.BARO), gnssHz = 0.2)
        val s = PowerRules.subscription(PowerProfile.SAVER, slow)
        assertEquals(emptySet<String>(), s.sensors)
        assertEquals(0.2, s.gnssHz, 0.0)
    }

    @Test
    fun onlyPassageDims() {
        assertTrue(PowerRules.dims(PowerProfile.PASSAGE))
        assertFalse(PowerRules.dims(PowerProfile.HARBOR))
        assertFalse(PowerRules.dims(PowerProfile.SAVER))
    }
}
