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
        is FileNotFoundException -> e.message?.takeIf { it.isNotBlank() }?.let { "not found: $it" } ?: "not found"
        else -> e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
    }

    /** The host Android's resolver names in quotes: `Unable to resolve host "registry.npmjs.org": …`. */
    private fun host(message: String?): String? = message?.let { QUOTED.find(it)?.groupValues?.get(1) }

    private val QUOTED = Regex("\"([^\"]+)\"")
}
