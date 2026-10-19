package fi.pocketsk.bridge

import fi.pocketsk.bridge.BridgeProtocol.Device
import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceListTest {
    private val sent = mutableListOf<String>()
    private val shown = mutableListOf<List<Device>>()
    private val list = DeviceList({ sent += it }, { shown += it })

    private val gps = Device("usb-1a86-7523", BridgeProtocol.USB, "GPS", 4800, Device.OPEN)
    private val ais = Device("bt-001bdc0a1b2c", BridgeProtocol.BLUETOOTH, "AIS", 0, Device.CONNECTING, address = "00:1B:DC:0A:1B:2C")
    private val headset = Device("bt-00aabbccddee", BridgeProtocol.BLUETOOTH, "Headset", 0, Device.IDLE, chosen = false)

    @Test
    fun bothKindsAreOneListAndThePluginHearsOnlyTheChosen() {
        list.update(BridgeProtocol.USB, listOf(gps))
        list.update(BridgeProtocol.BLUETOOTH, listOf(ais, headset))
        assertEquals(listOf(gps, ais, headset), shown.last())
        assertEquals(BridgeProtocol.devices(listOf(gps, ais)), sent.last())
    }

    @Test
    fun thePluginHearsOfChangesOnlyButTheDockSeesEveryCount() {
        list.update(BridgeProtocol.USB, listOf(gps))
        list.update(BridgeProtocol.USB, listOf(gps.copy(bytes = 100)))
        assertEquals(1, sent.size)
        assertEquals(2, shown.size)
        assertEquals(100L, shown.last().single().bytes)
        list.update(BridgeProtocol.USB, emptyList())
        assertEquals(2, sent.size)
    }

    @Test
    fun aNewPluginIsToldTheListAgain() {
        list.update(BridgeProtocol.USB, listOf(gps))
        list.resend()
        assertEquals(2, sent.size)
        assertEquals(sent[0], sent[1])
    }
}
