package fi.pocketsk.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The address the notification shows for the server. */
class LanAddressTest {
    @Test
    fun wifiBeatsTheHotspotWhichBeatsEthernet() {
        val all = listOf("eth0" to "10.0.0.5", "swlan0" to "192.168.43.1", "wlan0" to "192.168.1.20")
        assertEquals("192.168.1.20", LanAddress.pick(all))
        assertEquals("192.168.43.1", LanAddress.pick(all.drop(1).dropLast(1) + ("eth0" to "10.0.0.5")))
        assertEquals("10.0.0.5", LanAddress.pick(listOf("eth0" to "10.0.0.5", "tun0" to "10.8.0.2")))
    }

    @Test
    fun mobileDataIsNeverTheAnswer() {
        assertNull(LanAddress.pick(listOf("rmnet_data0" to "100.71.3.4", "v4-rmnet_data0" to "192.0.0.4")))
        assertEquals("10.8.0.2", LanAddress.pick(listOf("rmnet0" to "100.71.3.4", "tun0" to "10.8.0.2")))
    }

    @Test
    fun loopbackLinkLocalAndJunkAreSkipped() {
        assertNull(LanAddress.pick(listOf("lo" to "127.0.0.1", "wlan0" to "169.254.10.2", "wlan1" to "0.0.0.0")))
        assertNull(LanAddress.pick(listOf("wlan0" to "fe80::1", "wlan0" to "300.1.1.1")))
        assertNull(LanAddress.pick(emptyList()))
    }
}
