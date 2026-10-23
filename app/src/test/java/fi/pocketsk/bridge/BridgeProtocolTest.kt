package fi.pocketsk.bridge

import fi.pocketsk.bridge.BridgeProtocol.Incoming
import fi.pocketsk.bridge.BridgeProtocol.Subscription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun aNameAndAYesOrNoAreSensorLinesToo() {
        assertEquals(
            """{"t":"sensor","path":"communication.cellular.operator","value":"Elisa FI","src":"android.cellular","ts":3}""",
            BridgeProtocol.sensorText("communication.cellular.operator", " Elisa\n\t FI ", "android.cellular", 3),
        )
        assertEquals(
            "cut to the longest text, quotes escaped",
            """{"t":"sensor","path":"a.b","value":"\"${"x".repeat(BridgeProtocol.MAX_TEXT - 1)}","src":"android.x","ts":0}""",
            BridgeProtocol.sensorText("a.b", "\"" + "x".repeat(100), "android.x", 0),
        )
        assertEquals(
            "a character is not cut in two",
            """{"t":"sensor","path":"a.b","value":"${"x".repeat(BridgeProtocol.MAX_TEXT - 1)}","src":"android.x","ts":0}""",
            BridgeProtocol.sensorText("a.b", "x".repeat(BridgeProtocol.MAX_TEXT - 1) + "\uD83D\uDEA2", "android.x", 0),
        )
        assertEquals(
            "nothing left is no name",
            """{"t":"sensor","path":"a.b","value":null,"src":"android.x","ts":0}""",
            BridgeProtocol.sensorText("a.b", " \u0007 ", "android.x", 0),
        )
        assertEquals(
            """{"t":"sensor","path":"a.b","value":null,"src":"android.x","ts":0}""",
            BridgeProtocol.sensorText("a.b", null, "android.x", 0),
        )
        assertEquals(
            """{"t":"sensor","path":"communication.cellular.roaming","value":false,"src":"android.cellular","ts":9}""",
            BridgeProtocol.sensorFlag("communication.cellular.roaming", false, "android.cellular", 9),
        )
        assertEquals(
            """{"t":"sensor","path":"communication.cellular.roaming","value":null,"src":"android.cellular","ts":9}""",
            BridgeProtocol.sensorFlag("communication.cellular.roaming", null, "android.cellular", 9),
        )
    }

    @Test
    fun theCellularReadingIsSubscribedWithItsPeriod() {
        assertEquals(
            Incoming.Subscribe(Subscription(setOf("cellular"), cellularHz = 0.1)),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":["cellular"],"rateHz":{"cellular":0.1}}"""),
        )
        assertEquals(
            Incoming.Subscribe(Subscription(setOf("cellular"), cellularHz = BridgeProtocol.CELLULAR_MAX_HZ)),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":["cellular"],"rateHz":{"cellular":20}}"""),
        )
        assertEquals(
            Incoming.Subscribe(Subscription(setOf("cellular"))),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":["cellular"],"rateHz":{"cellular":"often"}}"""),
        )
        assertEquals(BridgeProtocol.CELLULAR_DEFAULT_HZ, Subscription(setOf("cellular")).cellularHz, 0.0)
    }

    @Test
    fun theMotionAndCabinSensorsAndTheBowOffsetAreRead() {
        assertEquals(
            Incoming.Subscribe(
                Subscription(
                    setOf("attitude", "heading", "environment"),
                    attitudeHz = BridgeProtocol.ATTITUDE_MAX_HZ,
                    environmentHz = 0.2,
                    bowOffsetDeg = -90.0,
                ),
            ),
            BridgeProtocol.parse(
                """{"t":"subscribe","sensors":["attitude","heading","environment"],"rateHz":{"attitude":50,"environment":0.2},"bowOffsetDeg":-90}""",
            ),
        )
        assertEquals(
            Incoming.Subscribe(Subscription(setOf("attitude"), bowOffsetDeg = 180.0)),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":["attitude"],"bowOffsetDeg":400}"""),
        )
        assertEquals(
            Incoming.Subscribe(Subscription(setOf("attitude"))),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":["attitude"],"bowOffsetDeg":"aft"}"""),
        )
    }

    @Test
    fun theSerialDevicesAreToldOfAndTheirBytesPassedOnAsBase64() {
        val devices = listOf(
            BridgeProtocol.Device("usb-1a86-7523", BridgeProtocol.USB, "USB Serial", 4800, BridgeProtocol.Device.OPEN, bytes = 99, address = "/dev/bus/usb/001/002"),
            BridgeProtocol.Device("usb-0403-6001-a1", BridgeProtocol.USB, "FT232R \"AIS\"", 38400, BridgeProtocol.Device.ERROR, "gone"),
        )
        // The byte counts and where Android has the device are the Dock's, not the plugin's.
        assertEquals(
            """{"t":"devices","list":[{"id":"usb-1a86-7523","kind":"usb","name":"USB Serial","baud":4800,"state":"open"},""" +
                """{"id":"usb-0403-6001-a1","kind":"usb","name":"FT232R \"AIS\"","baud":38400,"state":"error","detail":"gone"}]}""",
            BridgeProtocol.devices(devices),
        )
        assertEquals("""{"t":"devices","list":[]}""", BridgeProtocol.devices(emptyList()))
        val read = "${'$'}GPRMC,1\r\n".toByteArray() + ByteArray(8)
        assertEquals(
            """{"t":"serial","id":"usb-1a86-7523","data":"JEdQUk1DLDENCg=="}""",
            BridgeProtocol.serial("usb-1a86-7523", read, 10),
        )
        assertThrows(IllegalArgumentException::class.java) {
            BridgeProtocol.serial("usb-1a86-7523", ByteArray(BridgeProtocol.MAX_SERIAL_BYTES + 1), BridgeProtocol.MAX_SERIAL_BYTES + 1)
        }
    }

    @Test
    fun aSubscribeSaysWhichSerialDevicesToReadAndAtWhatRate() {
        assertEquals(
            BridgeProtocol.Serial(usb = true, baud = 9600, bauds = mapOf("usb-0403-6001-a1" to 38400)),
            serialOf("""{"usb":true,"baud":9600,"devices":{"usb-0403-6001-a1":38400,"usb-1a86-7523":1234,"../etc":4800,"usb-x":"fast"}}"""),
        )
        val s = serialOf("""{"usb":true,"baud":4801}""")
        assertEquals(BridgeProtocol.Serial(usb = true), s)
        assertEquals(4800, s.baudFor("usb-1a86-7523"))
        assertEquals(BridgeProtocol.Serial.NONE, serialOf("""{"usb":"yes"}"""))
        assertEquals(BridgeProtocol.Serial(bluetooth = true), serialOf("""{"bt":true}"""))
        assertEquals(BridgeProtocol.Serial.NONE, serialOf("""{"bt":1}"""))
        assertEquals(
            Incoming.Subscribe(Subscription(setOf("gnss"))),
            BridgeProtocol.parse("""{"t":"subscribe","sensors":["gnss"],"serial":[1]}"""),
        )
        val many = (1..40).joinToString(",") { """"usb-0000-%04d":4800""".format(it) }
        assertEquals(BridgeProtocol.MAX_DEVICE_RATES, serialOf("""{"usb":true,"devices":{$many}}""").bauds.size)
    }

    private fun serialOf(serial: String): BridgeProtocol.Serial =
        (BridgeProtocol.parse("""{"t":"subscribe","sensors":[],"serial":$serial}""") as Incoming.Subscribe).subscription.serial

    @Test
    fun theAlarmsAreReadEntryByEntry() {
        val id = "0c1f3f5e-6f43-4d8e-9a8b-6a3b1c2d4e5f"
        val line = """{"t":"alarms","list":[""" +
            """{"id":"$id","path":"notifications.navigation.anchor","state":"alarm","message":"Anchor\n\u0007dragging","sound":true,"action":"silence"},""" +
            """{"id":"notifications.mob","path":"notifications.mob","state":"emergency","message":7,"action":"clear"},""" +
            """{"id":"x y","path":"notifications.a","state":"alarm"},{"id":"b","path":"navigation.position","state":"alarm"},""" +
            """{"id":"c","path":"notifications.c","state":"normal"},5]}"""
        assertEquals(
            Incoming.Alarms(
                listOf(
                    BridgeProtocol.Alarm(id, "notifications.navigation.anchor", "alarm", "Anchor  dragging", sound = true, action = BridgeProtocol.Alarm.SILENCE),
                    BridgeProtocol.Alarm("notifications.mob", "notifications.mob", "emergency", "", sound = true, action = null),
                ),
            ),
            BridgeProtocol.parse(line),
        )
        assertEquals(Incoming.Alarms(emptyList()), BridgeProtocol.parse("""{"t":"alarms","list":[]}"""))
        val urn = "notifications.navigation.closestApproach.urn:mrn:imo:mmsi:230099999"
        assertEquals(
            Incoming.Alarms(listOf(BridgeProtocol.Alarm(urn, urn, "alarm", "", sound = true, action = null))),
            BridgeProtocol.parse("""{"t":"alarms","list":[{"id":"$urn","path":"$urn","state":"alarm"}]}"""),
        )
        assertEquals(Incoming.Dropped("alarms without a list"), BridgeProtocol.parse("""{"t":"alarms"}"""))
        val many = (0..BridgeProtocol.MAX_ALARMS).joinToString(",", "[", "]") { """{"id":"a$it","path":"notifications.a$it","state":"alarm"}""" }
        assertEquals(Incoming.Dropped("alarms with 33 entries"), BridgeProtocol.parse("""{"t":"alarms","list":$many}"""))
        val message = (BridgeProtocol.parse("""{"t":"alarms","list":[{"id":"a","path":"notifications.a","state":"warn","message":"${"x".repeat(300)}","sound":false}]}""") as Incoming.Alarms).list.single()
        assertEquals(200, message.message.length)
        assertEquals(false, message.sound)
        assertTrue(BridgeProtocol.Alarm("a", "notifications.a", "emergency", "", true, null).rank > message.rank)
    }

    @Test
    fun aSilenceNamesTheAlarm() {
        assertEquals("""{"t":"silence","id":"notifications.mob"}""", BridgeProtocol.silence("notifications.mob"))
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
