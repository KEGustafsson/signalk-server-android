package fi.pocketsk.satellite

import fi.pocketsk.signalk.SignalKClient
import fi.pocketsk.signalk.SignalKUrl
import java.net.URI
import java.net.URISyntaxException

/**
 * Satellite mode's rules (docs/PLAN.md §8.4, §16 13.1): this phone's server follows the boat's
 * main Signal K server, so the webapps on the phone show the boat's data and keep the phone's own
 * when the Wi-Fi drops. The bridge's plugin holds the link (its lib/satellite.js, the main
 * server's delta stream with the token in its Authorization header); the token comes from the
 * main server's device access request, which its admin approves, and stays in the app's
 * preferences, handed to the plugin over the bridge socket and never written to `.signalk`.
 *
 * signalk-server's own Signal K connection is not used: in 2.35.1 it opens the stream without the
 * token and sends it as a message the server does not read, so a main server with security on
 * gives it nothing (tried on the desktop, 2026-10-30).
 *
 * Pure, and tested: which addresses can be followed, what counts as this phone's own server
 * among the ones found, and what each answer to the access request means.
 */
internal object Satellite {

    /**
     * The main server, `http://host:port`, and the device token its admin granted (null when it
     * runs without security): what the bridge's plugin follows ([fi.pocketsk.bridge.BridgeProtocol.satellite]).
     */
    data class Link(val url: String, val token: String?)

    /**
     * The main server's host and port from an address as the Dock has it (found over mDNS or
     * typed); null when it is not one this step can follow. HTTPS is not: the app's own requests
     * and the plugin's stream check the certificate, and a boat's server has none a phone trusts.
     */
    fun target(url: String): Pair<String, Int>? {
        val normal = SignalKUrl.normalise(url) ?: return null
        val uri = try {
            URI(normal)
        } catch (_: URISyntaxException) {
            return null
        }
        if (uri.scheme != "http") return null
        val host = uri.host?.removeSurrounding("[", "]") ?: return null
        return host to uri.port
    }

    /**
     * Whether [url], a server found over mDNS, is this phone's own: its address is one of
     * [ownHosts] (the phone's addresses, as `InetAddress.getHostAddress` writes them). The phone's
     * server advertises itself on the boat's network while LAN access is on, and following itself
     * would be a loop.
     */
    fun own(url: String, ownHosts: Set<String>): Boolean {
        val host = target(url)?.first ?: return false
        return host.lowercase() in ownHosts.map { it.substringBefore('%').lowercase() } ||
            host == "localhost" || host.startsWith("127.") || host == "::1"
    }

    /** Where an access request stands after one answer from the main server. */
    sealed interface Outcome {
        /** Waiting for the admin; poll [href]. */
        data class Pending(val href: String) : Outcome

        /** Approved: follow with [token]; null when the server runs without security and needs none. */
        data class Approved(val token: String?) : Outcome

        /** The admin said no. */
        data object Denied : Outcome

        /** Nothing to wait for: [why] says what the owner can do about it. */
        data class Refused(val why: Why) : Outcome

        /** The server did not answer this time (Wi-Fi, a restart): ask again later. */
        data class Retry(val detail: String?) : Outcome
    }

    enum class Why {
        /** The main server takes no device access requests (its Security settings). */
        NO_DEVICE_REQUESTS,

        /** It already has one from this phone pending, whose answer this phone lost. */
        ALREADY_ASKED,

        /** It forgot the request (it restarted, or let it lapse): ask again. */
        EXPIRED,

        /** It did not answer as a Signal K server does. */
        NOT_SIGNAL_K,
    }

    /**
     * What the answer to `POST /signalk/v1/access/requests` means. signalk-server answers 202 with
     * the request's `href` to poll; 404 when it runs without security, where reading needs no
     * token; 403 when it takes no device requests; 400 for a second request from a client id that
     * has one pending.
     */
    fun afterRequest(result: SignalKClient.Result<SignalKClient.Access>): Outcome = when (result) {
        is SignalKClient.Result.Ok -> result.value.href?.let { Outcome.Pending(it) } ?: Outcome.Refused(Why.NOT_SIGNAL_K)
        is SignalKClient.Result.Failed -> when {
            result.failure == SignalKClient.Failure.UNREACHABLE -> Outcome.Retry(result.detail)
            result.httpCode == HTTP_NOT_FOUND -> Outcome.Approved(null)
            result.httpCode == HTTP_FORBIDDEN -> Outcome.Refused(Why.NO_DEVICE_REQUESTS)
            result.httpCode == HTTP_BAD_REQUEST -> Outcome.Refused(Why.ALREADY_ASKED)
            result.httpCode >= HTTP_SERVER_ERROR -> Outcome.Retry(result.detail)
            else -> Outcome.Refused(Why.NOT_SIGNAL_K)
        }
    }

    /**
     * What one poll of the request's `href` means: `PENDING` until the admin decides, then
     * `COMPLETED` with `accessRequest.permission` APPROVED (and the token) or DENIED. A 404 is a
     * request the server no longer has.
     */
    fun afterPoll(href: String, result: SignalKClient.Result<SignalKClient.Access>): Outcome = when (result) {
        is SignalKClient.Result.Ok -> {
            val access = result.value
            when {
                access.state == PENDING -> Outcome.Pending(href)
                access.state != COMPLETED -> Outcome.Refused(Why.NOT_SIGNAL_K)
                access.permission == APPROVED && !access.token.isNullOrEmpty() -> Outcome.Approved(access.token)
                access.permission == DENIED -> Outcome.Denied
                else -> Outcome.Refused(Why.NOT_SIGNAL_K)
            }
        }
        is SignalKClient.Result.Failed -> when {
            result.failure == SignalKClient.Failure.UNREACHABLE -> Outcome.Retry(result.detail)
            result.httpCode == HTTP_NOT_FOUND -> Outcome.Refused(Why.EXPIRED)
            result.httpCode >= HTTP_SERVER_ERROR -> Outcome.Retry(result.detail)
            else -> Outcome.Refused(Why.NOT_SIGNAL_K)
        }
    }

    private const val PENDING = "PENDING"
    private const val COMPLETED = "COMPLETED"
    private const val APPROVED = "APPROVED"
    private const val DENIED = "DENIED"
    private const val HTTP_BAD_REQUEST = 400
    private const val HTTP_FORBIDDEN = 403
    private const val HTTP_NOT_FOUND = 404
    private const val HTTP_SERVER_ERROR = 500
}
