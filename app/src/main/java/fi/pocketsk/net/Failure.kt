package fi.pocketsk.net

import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * What went wrong, in words the owner can act on, for the Dock and the notifications. An
 * exception's own `toString` leads with its class name ("java.net.UnknownHostException: Unable to
 * resolve host …"), which said nothing to anyone who is not reading the code; the log keeps that
 * form, since whoever helps reads the log.
 *
 * The network failures a phone on a boat meets every day get a sentence of their own; anything
 * else keeps its message, without the class name. Pure, so the wording is unit-tested.
 */
internal object Failure {

    /** [e] as a short phrase, lower case, to follow "failed:" or stand in brackets. */
    fun text(e: Throwable): String = when (e) {
        is UnknownHostException -> host(e.message)?.let { "$it could not be found: is the phone on the internet?" }
            ?: "a name could not be looked up: is the phone on the internet?"
        is SocketTimeoutException -> "no answer in time"
        is NoRouteToHostException, is ConnectException -> "could not connect"
        is SSLException -> "the secure connection failed"
        // Android throws it for a file that is there but cannot be opened, too: no claim that it is missing.
        is FileNotFoundException -> e.message?.takeIf { it.isNotBlank() }?.let { "could not open $it" } ?: "a file could not be opened"
        else -> e.message?.takeIf { it.isNotBlank() } ?: UNKNOWN
    }

    /**
     * npm's own error code in a line of its output (`npm error code ENOTFOUND`), as a phrase, or
     * null for any other line. npm exits 1 for all of them, so "exit code 1" told the owner
     * nothing; the code says whether the phone was offline, the registry silent or the storage
     * full.
     */
    fun npm(line: String): String? = when (NPM_CODE.find(line)?.groupValues?.get(1)) {
        "ENOTFOUND", "EAI_AGAIN" -> "the npm registry could not be found: is the phone on the internet?"
        "ETIMEDOUT", "ESOCKETTIMEDOUT", "ECONNRESET", "ECONNREFUSED", "ENETUNREACH" -> "the npm registry did not answer"
        "ENOSPC" -> "the phone is out of storage space"
        else -> null
    }

    /** The host Android's resolver names in quotes: `Unable to resolve host "registry.npmjs.org": …`. */
    private fun host(message: String?): String? = message?.let { QUOTED.find(it)?.groupValues?.get(1) }

    private val QUOTED = Regex("\"([^\"]+)\"")
    private val NPM_CODE = Regex("""^npm (?:error|ERR!) code (E[A-Z_]+)\b""")

    /** For an exception that carries no message at all. */
    private const val UNKNOWN = "something went wrong (the log has the details)"
}
