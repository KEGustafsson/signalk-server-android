package fi.pocketsk.net

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class FailureTest {

    @Test
    fun anUnknownHostIsNamedAndTheInternetSuspected() {
        val e = UnknownHostException("Unable to resolve host \"registry.npmjs.org\": No address associated with hostname")
        assertEquals("registry.npmjs.org could not be found: is the phone on the internet?", Failure.text(e))
        assertEquals("a name could not be looked up: is the phone on the internet?", Failure.text(UnknownHostException()))
    }

    @Test
    fun theDailyNetworkFailuresGetASentence() {
        assertEquals("no answer in time", Failure.text(SocketTimeoutException("timeout")))
        assertEquals("could not connect", Failure.text(ConnectException("failed to connect to /192.168.1.9 (port 3000): ECONNREFUSED")))
        assertEquals("could not connect", Failure.text(NoRouteToHostException("No route to host")))
        assertEquals("the secure connection failed", Failure.text(SSLHandshakeException("Chain validation failed")))
    }

    @Test
    fun anythingElseKeepsItsMessageWithoutTheClassName() {
        assertEquals("npm view gave no version list", Failure.text(IOException("npm view gave no version list")))
        assertEquals("not found: /x/y", Failure.text(FileNotFoundException("/x/y")))
        assertEquals("not found", Failure.text(FileNotFoundException()))
        assertEquals("IllegalStateException", Failure.text(IllegalStateException()))
        assertEquals("IOException", Failure.text(IOException(" ")))
    }
}
