package fi.pocketsk.bridge

import fi.pocketsk.bridge.BridgeProtocol.Incoming
import fi.pocketsk.bridge.BridgeProtocol.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BridgeProtocolTest {
    @Test
    fun linesTheAppWrites() {
        assertEquals("""{"t":"hello","v":1,"app":"1.123"}""", BridgeProtocol.hello("1.123"))
        assertEquals(
            """{"t":"sensor","path":"environment.outside.pressure","value":101325,"src":"android.baro","ts":5}""",
            BridgeProtocol.sensor("environment.outside.pressure", 101325.0, "android.baro", 5),
        )
        assertEquals(
            """{"t":"sensor","path":"navigation.attitude","value":{"roll":0.02,"yaw":null},"src":"android.imu","ts":0}""",
            BridgeProtocol.sensor("navigation.attitude", linkedMapOf("roll" to 0.02, "yaw" to null), "android.imu", 0),
        )
        assertEquals(
            """{"t":"sensor","path":"navigation.speedOverGround","value":null,"src":"android.gnss","ts":7}""",
            BridgeProtocol.sensor("navigation.speedOverGround", null, "android.gnss", 7),
        )
    }

    @Test
    fun aSubscribeIsReadAndClamped() {
        assertEquals(
            Incoming.Subscribe(Subscription(setOf("battery", "baro", "gnss"), baroHz = 0.5)),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":["battery","baro","gnss","imu",7],"rateHz":{"baro":0.5}}"""),
        )
        assertEquals(
            Incoming.Subscribe(Subscription(setOf("gnss"), gnssHz = BridgeProtocol.GNSS_MAX_HZ)),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":["gnss"],"rateHz":{"gnss":100}}"""),
        )
        assertEquals(
            Incoming.Subscribe(Subscription(setOf("gnss"), gnssHz = 0.5)),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":["gnss"],"rateHz":{"gnss":0.5}}"""),
        )
        assertEquals(
            Incoming.Subscribe(Subscription(setOf("baro"), 1.0)),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":["baro"],"rateHz":{"baro":50}}"""),
        )
        assertEquals(
            Incoming.Subscribe(Subscription(emptySet(), BridgeProtocol.BARO_MIN_HZ)),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":[],"rateHz":{"baro":0.0000001}}"""),
        )
        assertEquals(
            Incoming.Subscribe(Subscription(setOf("battery"), BridgeProtocol.BARO_DEFAULT_HZ)),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":["battery"],"rateHz":{"baro":-1}}"""),
        )
    }

    @Test
    fun anythingElseIsDroppedWithAReason() {
        assertEquals(Incoming.Dropped("not an object"), BridgeProtocol.parse("[1]"))
        assertEquals(Incoming.Dropped("unknown type alarm"), BridgeProtocol.parse("""{"t":"alarm"}"""))
        assertEquals(Incoming.Dropped("unknown type null"), BridgeProtocol.parse("{}"))
        assertEquals(Incoming.Dropped("subscribe without a sensor list"), BridgeProtocol.parse("""{"t":"subscribe"}"""))
        assertEquals(Incoming.Dropped("line too long"), BridgeProtocol.parse("x".repeat(BridgeProtocol.MAX_LINE + 1)))
        assertTrue((BridgeProtocol.parse("{") as Incoming.Dropped).reason.startsWith("not JSON"))
    }
}
