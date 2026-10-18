package fi.pocketsk.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The live server's HTTP port and SSL setting, and what counts as it answering. */
class ServerEndpointTest {
    @Test
    fun readsThePortTheAdminUiSet() {
        assertEquals(ServerEndpoint(3001, ssl = false), ServerEndpoint.of(3001, null))
        assertEquals(80, ServerEndpoint.of("80", false).port)
        assertEquals(8080, ServerEndpoint.of(8080L, false).port)
        assertEquals(8080, ServerEndpoint.of(8080.0, false).port)
    }

    @Test
    fun anythingElseIsSignalKsDefault() {
        for (bad in listOf(null, 0, -1, 70000, "x", "", 3000.5, true)) {
            assertEquals("$bad", ServerEndpoint.DEFAULT_PORT, ServerEndpoint.of(bad, null).port)
        }
    }

    @Test
    fun sslIsOnOnlyWhenTrue() {
        assertTrue(ServerEndpoint.of(null, true).ssl)
        assertFalse(ServerEndpoint.of(null, "true").ssl)
        assertFalse(ServerEndpoint.of(null, null).ssl)
    }

    @Test
    fun withSslTheRedirectIsTheAnswer() {
        val plain = ServerEndpoint(3000, ssl = false)
        val ssl = ServerEndpoint(3000, ssl = true)
        assertTrue(plain.alive(ok = true, httpCode = 0))
        assertFalse(plain.alive(ok = false, httpCode = 302))
        assertTrue(ssl.alive(ok = false, httpCode = 302))
        assertTrue(ssl.alive(ok = true, httpCode = 0))
        assertFalse(ssl.alive(ok = false, httpCode = 500))
        assertFalse(ssl.alive(ok = false, httpCode = 0))
    }
}
