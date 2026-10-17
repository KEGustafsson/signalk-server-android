package fi.pocketsk

import fi.pocketsk.LocalNetwork.Need
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalNetworkTest {
    private val android16 = 36
    private val android17 = 37

    @Test
    fun enforcedFromAndroid17() {
        assertEquals("CINNAMON_BUN is API 37", 37, android.os.Build.VERSION_CODES.CINNAMON_BUN)
        assertEquals(android.Manifest.permission.ACCESS_LOCAL_NETWORK, LocalNetwork.PERMISSION)
        assertEquals(Need.NONE, LocalNetwork.forServer(android16, lanAccess = true))
        assertEquals(Need.NONE, LocalNetwork.forHost(android16, "192.168.1.9"))
        assertFalse(LocalNetwork.forServerUrl(android16, "http://192.168.1.9:3000"))
    }

    @Test
    fun aServerOthersReachNeedsItALoopbackOnlyOneDoesNot() {
        assertEquals(Need.REQUIRED, LocalNetwork.forServer(android17, lanAccess = true))
        assertEquals(Need.NONE, LocalNetwork.forServer(android17, lanAccess = false))
    }

    @Test
    fun aLocalHostNeedsItAPublicOneDoesNotANameIsAsked() {
        assertEquals(Need.NONE, LocalNetwork.forHost(android17, null))
        assertEquals(Need.REQUIRED, LocalNetwork.forHost(android17, "192.168.1.9"))
        assertEquals(Need.REQUIRED, LocalNetwork.forHost(android17, "[fd12::7]"))
        assertEquals("unbracketed too", Need.REQUIRED, LocalNetwork.forHost(android17, "fd12::7"))
        assertEquals(Need.NONE, LocalNetwork.forHost(android17, "8.8.8.8"))
        assertEquals("a name may be either: asked, not required", Need.OPTIONAL, LocalNetwork.forHost(android17, "ydwg.example.org"))
    }

    @Test
    fun reachingAServerByUrlNeedsItForALocalOneAndForTheSearchButNeverForLoopback() {
        assertTrue(LocalNetwork.forServerUrl(android17, "http://192.168.1.9:3000"))
        assertTrue(LocalNetwork.forServerUrl(android17, "http://boat.local:3000"))
        assertTrue("no server yet: mDNS finds one", LocalNetwork.forServerUrl(android17, null))
        assertFalse(LocalNetwork.forServerUrl(android17, "https://203.0.113.7"))
        assertFalse(LocalNetwork.forServerUrl(android17, "http://127.0.0.1:3000"))
        assertFalse(LocalNetwork.forServerUrl(android17, "http://localhost:3000"))
        assertFalse(LocalNetwork.forServerUrl(android17, "http://[::1]:3000"))
    }
}
