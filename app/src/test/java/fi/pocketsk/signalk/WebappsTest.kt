package fi.pocketsk.signalk

import fi.pocketsk.signalk.Webapps.Webapp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The webapps the Dock lists and pins to the home screen. */
class WebappsTest {

    @Test
    fun aWebappIsReadFromItsPackage() {
        val json = """
            {"name":"@signalk/freeboard-sk","version":"2.12.0",
             "keywords":["signalk-node-server-plugin","signalk-webapp"],
             "signalk":{"displayName":"Freeboard-SK","appIcon":"./public/assets/icons/icon-72x72.png"}}
        """.trimIndent()
        assertEquals(
            Webapp("@signalk/freeboard-sk", "Freeboard-SK", "public/assets/icons/icon-72x72.png"),
            Webapps.parse(json),
        )
    }

    @Test
    fun withoutADisplayNameOrIconTheNameStands() {
        assertEquals(
            Webapp("kip", "kip", null),
            Webapps.parse("""{"name":"kip","keywords":["signalk-webapp"],"signalk":{"displayName":"  ","appIcon":"../../etc/x.png"}}"""),
        )
        assertNull(Webapps.parse("""{"name":"kip","keywords":["signalk-webapp"],"signalk":{"appIcon":"/etc/x.png"}}""")!!.icon)
    }

    @Test
    fun otherPackagesAreNotWebapps() {
        assertNull(Webapps.parse("""{"name":"signalk-to-nmea0183","keywords":["signalk-node-server-plugin"]}"""))
        assertNull(Webapps.parse("""{"name":"@signalk/vesselpositions","keywords":["signalk-embeddable-webapp"]}"""))
        assertNull(Webapps.parse("""{"name":"Bad Name","keywords":["signalk-webapp"]}"""))
        assertNull(Webapps.parse("not json"))
        assertNull(Webapps.parse("[]"))
    }

    @Test
    fun onlyPackageNamesRideInALink() {
        assertTrue(Webapps.validName("@mxtommy/kip"))
        assertTrue(Webapps.validName("signalk-instrumentpanel"))
        assertFalse(Webapps.validName("../admin"))
        assertFalse(Webapps.validName("kip?x=1"))
        assertFalse(Webapps.validName("evil.example/x"))
        assertFalse(Webapps.validName("@a/b/c"))
        assertFalse(Webapps.validName(""))
        assertFalse(Webapps.validName("Ax"))
        assertFalse(Webapps.validName("?x"))
        assertFalse(Webapps.validName("@Scope/x"))
        assertTrue(Webapps.validName("-x~y"))
        assertEquals("pocketsk://webapp/@mxtommy/kip", Webapps.link("@mxtommy/kip"))
        assertEquals("@mxtommy/kip", Webapps.nameOf("webapp", "/@mxtommy/kip"))
        assertEquals("kip", Webapps.nameOf("webapp", "/kip/"))
        assertNull(Webapps.nameOf("admin", "/kip"))
        assertNull(Webapps.nameOf("webapp", "/../admin"))
        assertNull(Webapps.nameOf("webapp", null))
        assertEquals("http://127.0.0.1:3000/@mxtommy/kip/", Webapps.url("http://127.0.0.1:3000", "@mxtommy/kip"))
    }

    @Test
    fun aHugeIconIsDecodedSmallButNotBelowALaunchersSize() {
        assertEquals(1, Webapps.sampleSize(72, 72, 192))
        assertEquals(1, Webapps.sampleSize(383, 200, 192))
        assertEquals(2, Webapps.sampleSize(384, 384, 192))
        assertEquals(64, Webapps.sampleSize(20_000, 12_000, 192))
        assertTrue(20_000 / Webapps.sampleSize(20_000, 12_000, 192) >= 192)
    }
}
