package fi.pocketsk.install

import fi.pocketsk.bridge.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * signalk-server's release notes from GitHub ([ReleaseNotes]), kept a day in [cache] the way npm's
 * versions are ([InstallManager.listAvailable]). Without a token GitHub answers sixty requests an
 * hour from one address, which a boat that opens the notes now and then never meets; a cached
 * answer stands in when GitHub cannot be reached or refuses. Blocking: a worker thread only.
 */
internal class ReleaseNotesSource(
    private val cache: File,
    private val now: () -> Long = System::currentTimeMillis,
    private val fetch: () -> String = ::download,
) {

    /**
     * The releases, fetched again when [cache] is older than [maxAgeMs]. Only an answer that reads
     * as releases is kept, so a proxy's page or a cut answer never replaces a good cache, which
     * then stands in. Throws when neither GitHub nor the cache has them.
     *
     * One look at a time across instances: a rotation makes a new activity whose open notes load
     * while the old one's worker still runs, and two writers of the one temporary file could
     * publish a mix of both answers. The second look then finds the first one's fresh cache.
     */
    @Throws(IOException::class)
    fun releases(maxAgeMs: Long): List<ReleaseNotes.Release> = synchronized(LOCK) {
        if (cache.isFile && now() - cache.lastModified() < maxAgeMs) return cached()
        try {
            val text = fetch()
            ReleaseNotes.parse(text).also { keep(text) }
        } catch (e: IOException) {
            if (cache.isFile) cached() else throw e
        } catch (e: Json.ParseException) {
            if (cache.isFile) cached() else throw unreadable(e)
        }
    }

    /** The cached answer, dropped when it cannot be read or parsed so the next look asks GitHub. */
    private fun cached(): List<ReleaseNotes.Release> = try {
        ReleaseNotes.parse(cache.readText())
    } catch (e: Json.ParseException) {
        cache.delete()
        throw unreadable(e)
    } catch (e: IOException) {
        cache.delete()
        throw e
    }

    private fun unreadable(e: Json.ParseException) = IOException("GitHub's answer could not be read: ${e.message}")

    private fun keep(text: String) {
        val tmp = File(cache.path + ".tmp")
        try {
            cache.parentFile?.mkdirs()
            tmp.writeText(text)
            if (!tmp.renameTo(cache)) tmp.delete()
        } catch (_: IOException) {
            // Only a cache: the next look asks GitHub again.
            tmp.delete()
        }
    }

    companion object {
        /** The newest thirty: half a year of signalk-server's releases, what an update spans. */
        const val URL_RELEASES = "https://api.github.com/repos/SignalK/signalk-server/releases?per_page=30"

        /** Where a person reads them all. */
        const val URL_PAGE = "https://github.com/SignalK/signalk-server/releases"

        private const val TIMEOUT_MS = 15_000
        private const val MAX_BYTES = 4 * 1024 * 1024
        private const val BUFFER = 64 * 1024
        private val LOCK = Any()

        /** GitHub's answer, blocking. */
        private fun download(): String {
            val c = URL(URL_RELEASES).openConnection() as HttpURLConnection
            try {
                c.connectTimeout = TIMEOUT_MS
                c.readTimeout = TIMEOUT_MS
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                c.setRequestProperty("User-Agent", "PocketSK")
                val code = c.responseCode
                if (code != HttpURLConnection.HTTP_OK) throw IOException("GitHub answered $code")
                val out = ByteArrayOutputStream()
                c.inputStream.use { input ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        if (out.size() > MAX_BYTES) throw IOException("GitHub's answer is too long")
                    }
                }
                return out.toString(Charsets.UTF_8.name())
            } finally {
                c.disconnect()
            }
        }
    }
}
