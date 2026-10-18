package fi.pocketsk.server

/**
 * Where the live server answers plain HTTP, as its `settings.json` says: `port` (3000 when it is
 * not set, signalk-server's own `getHttpPort`), and whether `ssl` is on. The admin UI can change
 * both, so the supervisor reads them before every run rather than assume Signal K's defaults.
 *
 * With SSL on, signalk-server serves HTTPS on `sslport` with a certificate of its own making and
 * keeps the HTTP port for one thing only: every request there is redirected to HTTPS
 * (`startRedirectToSsl`, 2.33.0's `dist/index.js`). That redirect is the health watch's answer
 * then: it comes from the server itself, and needs no trust in a self-signed certificate. The
 * browser and the notification use the HTTP port too, and follow it.
 *
 * Pure, over the values as the JSON parser hands them over, so the rules are unit-tested.
 */
internal data class ServerEndpoint(val port: Int, val ssl: Boolean) {

    /** A health poll's outcome: [ok] for `/signalk`'s endpoints, else the HTTP status it got (0 for none). */
    fun alive(ok: Boolean, httpCode: Int): Boolean = ok || (ssl && httpCode in 300..399)

    companion object {
        const val DEFAULT_PORT = 3000
        val DEFAULT = ServerEndpoint(DEFAULT_PORT, ssl = false)

        /**
         * From `settings.json`'s `port` and `ssl` values; anything that is not a port Node would
         * listen on (a number, or a string of one, from 1 to 65535) is the default, as is a
         * missing one. SSL counts as on only when it is `true`.
         */
        fun of(port: Any?, ssl: Any?): ServerEndpoint {
            val p = when (port) {
                is Int -> port
                is Long -> port.toInt().takeIf { it.toLong() == port }
                is Double -> port.toInt().takeIf { it.toDouble() == port }
                is String -> port.trim().toIntOrNull()
                else -> null
            }?.takeIf { it in 1..65535 } ?: DEFAULT_PORT
            return ServerEndpoint(p, ssl == true)
        }
    }
}
