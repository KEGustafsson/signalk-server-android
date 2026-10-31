package fi.pocketsk.satellite

import fi.pocketsk.satellite.Satellite.Outcome
import fi.pocketsk.satellite.Satellite.Why
import fi.pocketsk.signalk.SignalKClient
import fi.pocketsk.signalk.SignalKClient.Access
import fi.pocketsk.signalk.SignalKClient.Failure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Satellite mode's rules: what can be followed, the phone's own server, the access request. */
class SatelliteTest {

    @Test
    fun onlyPlainHttpServersAreFollowed() {
        assertEquals("192.168.1.9" to 3000, Satellite.target("http://192.168.1.9:3000"))
        assertEquals("pi.local" to 3000, Satellite.target("pi.local"))
        assertEquals("fe80::1" to 3000, Satellite.target("http://[fe80::1]:3000"))
        assertNull("https needs a certificate the phone trusts", Satellite.target("https://192.168.1.9:3443"))
        assertNull(Satellite.target("not a server"))
    }

    @Test
    fun thePhonesLabelIsItsClientIdsFirstEightHex() {
        assertEquals("pocketsk.0b1c2d3e", Satellite.label("0B1C2D3E-4f5a-6b7c-8d9e-0f1a2b3c4d5e"))
        assertEquals("pocketsk.0b1c2d3e", Satellite.label("0b1c-2d3e-4f5a"))
        assertEquals("a client id shorter than that is padded", "pocketsk.ab000000", Satellite.label("ab"))
    }

    @Test
    fun thePhonesOwnServerIsNotOffered() {
        val own = setOf("192.168.1.20", "FE80::A%wlan0")
        assertTrue(Satellite.own("http://192.168.1.20:3000", own))
        assertTrue(Satellite.own("http://[fe80::a]:3000", own))
        assertTrue(Satellite.own("http://127.0.0.1:3000", emptySet()))
        assertFalse(Satellite.own("http://192.168.1.9:3000", own))
    }

    private fun failed(failure: Failure, code: Int = 0) = SignalKClient.Result.Failed(failure, "detail", code)

    private fun ok(href: String? = null, state: String? = null, permission: String? = null, token: String? = null) =
        SignalKClient.Result.Ok(Access(href, state, permission, token))

    @Test
    fun theRequestsAnswer() {
        assertEquals(Outcome.Pending("/signalk/v1/requests/1"), Satellite.afterRequest(ok(href = "/signalk/v1/requests/1", state = "PENDING")))
        assertEquals(Outcome.Refused(Why.NOT_SIGNAL_K), Satellite.afterRequest(ok()))
        assertEquals("no security: follow without a token", Outcome.Approved(null), Satellite.afterRequest(failed(Failure.BAD_RESPONSE, 404)))
        assertEquals(Outcome.Refused(Why.NO_DEVICE_REQUESTS), Satellite.afterRequest(failed(Failure.UNAUTHORIZED, 403)))
        assertEquals(Outcome.Refused(Why.ALREADY_ASKED), Satellite.afterRequest(failed(Failure.BAD_RESPONSE, 400)))
        assertEquals(Outcome.Retry("detail"), Satellite.afterRequest(failed(Failure.UNREACHABLE)))
        assertEquals(Outcome.Retry("detail"), Satellite.afterRequest(failed(Failure.BAD_RESPONSE, 503)))
        assertEquals(Outcome.Refused(Why.NOT_SIGNAL_K), Satellite.afterRequest(failed(Failure.BAD_RESPONSE, 302)))
    }

    @Test
    fun aPollsAnswer() {
        val href = "/signalk/v1/requests/1"
        assertEquals(Outcome.Pending(href), Satellite.afterPoll(href, ok(state = "PENDING")))
        assertEquals(Outcome.Approved("tok"), Satellite.afterPoll(href, ok(state = "COMPLETED", permission = "APPROVED", token = "tok")))
        assertEquals(Outcome.Denied, Satellite.afterPoll(href, ok(state = "COMPLETED", permission = "DENIED")))
        assertEquals("approved with no token is no answer", Outcome.Refused(Why.NOT_SIGNAL_K), Satellite.afterPoll(href, ok(state = "COMPLETED", permission = "APPROVED")))
        assertEquals("completed as a refusal (403 inside)", Outcome.Refused(Why.NOT_SIGNAL_K), Satellite.afterPoll(href, ok(state = "COMPLETED")))
        assertEquals(Outcome.Refused(Why.EXPIRED), Satellite.afterPoll(href, failed(Failure.BAD_RESPONSE, 404)))
        assertEquals(Outcome.Retry("detail"), Satellite.afterPoll(href, failed(Failure.UNREACHABLE)))
        assertEquals(Outcome.Refused(Why.NOT_SIGNAL_K), Satellite.afterPoll(href, ok(state = "ODD")))
    }
}
