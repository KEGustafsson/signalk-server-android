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
        assertEquals("could not open /x/y (Permission denied)", Failure.text(FileNotFoundException("/x/y (Permission denied)")))
        assertEquals("a file could not be opened", Failure.text(FileNotFoundException()))
        assertEquals("something went wrong (the log has the details)", Failure.text(IllegalStateException()))
        assertEquals("something went wrong (the log has the details)", Failure.text(IOException(" ")))
    }

    @Test
    fun npmsErrorCodeSaysWhatHappened() {
        val offline = "the npm registry could not be found: is the phone on the internet?"
        assertEquals(offline, Failure.npm("npm error code ENOTFOUND"))
        assertEquals(offline, Failure.npm("npm ERR! code EAI_AGAIN"))
        assertEquals("the npm registry did not answer", Failure.npm("npm error code ETIMEDOUT"))
        assertEquals("the phone has no route to the internet: is it on a network?", Failure.npm("npm error code ENETUNREACH"))
        assertEquals("the phone is out of storage space", Failure.npm("npm error code ENOSPC"))
        assertEquals(null, Failure.npm("npm error code E404"))
        assertEquals(null, Failure.npm("npm error errno ENOTFOUND"))
        assertEquals(null, Failure.npm("added 758 packages in 3m"))
    }
}
