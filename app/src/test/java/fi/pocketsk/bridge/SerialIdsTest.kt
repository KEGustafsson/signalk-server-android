package fi.pocketsk.bridge

import fi.pocketsk.bridge.SerialIds.Port
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SerialIdsTest {
    @Test
    fun anAdapterIsNamedByWhatItSaysAboutItself() {
        assertEquals(listOf("usb-1a86-7523"), SerialIds.ids(listOf(Port(0x1a86, 0x7523, null, 0, 1))))
        assertEquals(listOf("usb-0403-6001-a10k1b2c"), SerialIds.ids(listOf(Port(0x0403, 0x6001, "A10K1B2C", 0, 1))))
    }

    @Test
    fun twoThatSayTheSameAreToldApartByTheirOrder() {
        val ch340 = Port(0x1a86, 0x7523, null, 0, 1)
        assertEquals(listOf("usb-1a86-7523", "usb-1a86-7523-2", "usb-1a86-7523-3"), SerialIds.ids(listOf(ch340, ch340, ch340)))
        // Serial numbers keep two of the same chip apart whatever their order.
        assertEquals(
            listOf("usb-0403-6001-b2", "usb-0403-6001-a1"),
            SerialIds.ids(listOf(Port(0x0403, 0x6001, "B2", 0, 1), Port(0x0403, 0x6001, "A1", 0, 1))),
        )
    }

    @Test
    fun eachPortOfAMultiPortDeviceHasItsOwnId() {
        assertEquals(
            listOf("usb-0403-6010-ft1-p0", "usb-0403-6010-ft1-p1"),
            SerialIds.ids(listOf(Port(0x0403, 0x6010, "FT1", 0, 2), Port(0x0403, 0x6010, "FT1", 1, 2))),
        )
    }

    @Test
    fun aSerialNumberKeepsOnlyWhatAnIdMayCarry() {
        assertEquals("ab12", SerialIds.clean(" A-b/1.2 "))
        assertEquals("x".repeat(24), SerialIds.clean("X".repeat(40)))
        assertNull(SerialIds.clean("--"))
        assertNull(SerialIds.clean(null))
        // Every id is one the protocol reads back as a device id.
        val id = SerialIds.ids(listOf(Port(0xffff, 0xffff, "Z".repeat(40), 3, 4), Port(0xffff, 0xffff, "Z".repeat(40), 3, 4)))[1]
        assertEquals("usb-ffff-ffff-${"z".repeat(24)}-p3-2", id)
        assertEquals(
            BridgeProtocol.Serial(usb = true, bauds = mapOf(id to 9600)),
            (BridgeProtocol.parse("""{"t":"subscribe","sensors":[],"serial":{"usb":true,"devices":{"$id":9600}}}""") as BridgeProtocol.Incoming.Subscribe).subscription.serial,
        )
    }

    @Test
    fun aBluetoothDeviceIsItsAddress() {
        assertEquals("bt-001bdc0a1b2c", SerialIds.bluetooth("00:1B:DC:0A:1B:2C"))
        assertNull(SerialIds.bluetooth("00:1B:DC:0A:1B"))
        assertNull(SerialIds.bluetooth("00:1B:DC:0A:1B:ZZ"))
        // One the protocol reads back as a device id.
        val id = SerialIds.bluetooth("00:1B:DC:0A:1B:2C")!!
        assertEquals(
            mapOf(id to 4800),
            (BridgeProtocol.parse("""{"t":"subscribe","sensors":[],"serial":{"devices":{"$id":4800}}}""") as BridgeProtocol.Incoming.Subscribe)
                .subscription.serial.bauds,
        )
    }
}
