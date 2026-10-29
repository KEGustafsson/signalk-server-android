package fi.pocketsk.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import fi.pocketsk.BuildConfig
import fi.pocketsk.LegacyPlatform
import fi.pocketsk.bridge.Json
import fi.pocketsk.net.transportThread
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The app's own update (docs/PLAN.md §10): looks at this repository's latest GitHub release,
 * downloads the APK this phone takes, checks it and hands it to Android's installer, the flow
 * Obtainium and other installers use. Held by the process, not a screen, like
 * [fi.pocketsk.install.InstallJob]: a download takes a minute, and a rotation or the system's
 * confirmation dialog must find the same one.
 *
 * What it checks before Android sees the file ([AppRelease.refusal]): the SHA-256 the release
 * publishes beside the APK, that Android reads it as this app and a newer build of it, and that it
 * is signed by the key that signed the app installed. The installer refuses an update signed by
 * another key in any case; checking first spares the owner a confirmation that could only fail.
 *
 * Installing replaces the app's process; the server, if it ran, is started again by
 * [fi.pocketsk.server.BootReceiver] on the package-replaced broadcast. Nothing here runs on the
 * main thread.
 */
internal object AppUpdater {

    /** Where the update stands, as the Dock shows it. */
    sealed interface State {
        /** Not looked yet. */
        data object Idle : State
        data object Checking : State
        /** The latest release is not newer than this build, or has no APK for this phone. */
        data object UpToDate : State
        data class Available(val release: AppRelease.Release) : State
        data class Downloading(val release: AppRelease.Release, val done: Long, val total: Long) : State
        /** Handed to Android's installer, which asks the owner. */
        data class Installing(val release: AppRelease.Release) : State
        /** The look or the download failed; [release] is what was on offer, if anything was. */
        data class Failed(val reason: String, val release: AppRelease.Release?) : State
        /** The download arrived, but is not one to install. */
        data class Refused(val refusal: AppRelease.Refusal, val release: AppRelease.Release) : State
    }

    /** A screen's view of the update. Called on whichever thread changed it. */
    fun interface Observer {
        fun onState(state: State)
    }

    private val observers = CopyOnWriteArraySet<Observer>()
    @Volatile var state: State = State.Idle
        private set

    /** Adds [observer] and tells it at once where the update stands. */
    fun observe(observer: Observer) {
        observers += observer
        observer.onState(state)
    }

    fun detach(observer: Observer) {
        observers -= observer
    }

    /** Whether this install updates itself: not when a store that keeps it current installed it. */
    fun selfUpdates(context: Context): Boolean = AppRelease.selfUpdates(installer(context))

    /**
     * Looks at the latest release on a thread of its own, taking GitHub's answer from the day's
     * copy when it is younger than [maxAgeMs]. Nothing happens while a look or an update is under
     * way, or after the APK has been handed to the installer.
     */
    @Synchronized
    fun check(context: Context, maxAgeMs: Long) {
        if (busy()) return
        val app = context.applicationContext
        publish(State.Checking)
        transportThread("app-update-check", onError = { publish(State.Failed("$it", null)) }) {
            publish(look(app, maxAgeMs))
        }
    }

    /**
     * Downloads the release on offer, checks it and hands it to the installer, on a thread of its
     * own. The caller has made sure the app may ask to install packages
     * ([PackageManager.canRequestPackageInstalls]); Android asks the owner to confirm.
     */
    @Synchronized
    fun update(context: Context) {
        val release = when (val s = state) {
            is State.Available -> s.release
            is State.Failed -> s.release ?: return
            is State.Refused -> s.release
            else -> return
        }
        val app = context.applicationContext
        publish(State.Downloading(release, 0, release.apk.size))
        transportThread("app-update", onError = { publish(State.Failed("$it", release)) }) {
            val download = try {
                download(app, release)
            } catch (e: IOException) {
                publish(State.Failed(e.message ?: "$e", release))
                return@transportThread
            }
            val refusal = check(app, download)
            if (refusal != null) {
                download.apk.delete()
                publish(State.Refused(refusal, release))
                return@transportThread
            }
            publish(State.Installing(release))
            try {
                install(app, download.apk)
            } catch (e: IOException) {
                publish(State.Failed(e.message ?: "$e", release))
            } catch (e: RuntimeException) {
                // SecurityException, IllegalStateException: the installer refused the session.
                publish(State.Failed(e.message ?: "$e", release))
            }
        }
    }

    /**
     * The installer's confirmation screen ([UpdateReceiver]), kept for the Dock to show: Android
     * lets an app start an activity only while it is in front, and the owner may have left it
     * while the APK downloaded. Observers hear the state again, so a Dock in front takes it now.
     */
    @Synchronized
    fun confirmWith(confirmation: Intent) {
        if (state !is State.Installing) return
        this.confirmation = confirmation
        publish(state)
    }

    /** The confirmation waiting to be shown, handed out once; null when none waits. */
    @Synchronized
    fun takeConfirmation(): Intent? = confirmation.also { confirmation = null }

    private var confirmation: Intent? = null

    /**
     * What the installer said ([UpdateReceiver]): an install that did not go through puts the
     * release back on offer with the reason, unless the owner simply cancelled it.
     */
    @Synchronized
    fun installEnded(status: Int, message: String?) {
        val release = (state as? State.Installing)?.release ?: return
        confirmation = null
        publish(
            if (status == PackageInstaller.STATUS_FAILURE_ABORTED) State.Available(release)
            else State.Failed(message ?: "installer status $status", release),
        )
    }

    private fun busy(): Boolean = state is State.Checking || state is State.Downloading || state is State.Installing

    @Synchronized
    private fun publish(s: State) {
        state = s
        for (o in observers) o.onState(s)
    }

    /** The latest release, as a state: on offer when it is newer than this build. */
    private fun look(context: Context, maxAgeMs: Long): State {
        // An APK left from an update that never finished: the next one downloads afresh.
        File(context.cacheDir, DOWNLOADS).deleteRecursively()
        val text = try {
            latestRelease(File(context.cacheDir, RELEASE_CACHE), maxAgeMs)
        } catch (e: IOException) {
            return State.Failed(e.message ?: "$e", null)
        }
        val release = try {
            AppRelease.parse(text, Build.SUPPORTED_ABIS.toList())
        } catch (e: Json.ParseException) {
            File(context.cacheDir, RELEASE_CACHE).delete()
            return State.Failed("GitHub's answer could not be read: ${e.message}", null)
        }
        return if (release != null && AppRelease.isNewer(release, BuildConfig.VERSION_CODE.toLong())) State.Available(release)
        else State.UpToDate
    }

    /**
     * GitHub's answer for the latest release, from [cache] while it is younger than [maxAgeMs]:
     * without a token GitHub answers sixty requests an hour from one address, and the Dock looks
     * whenever it comes to the front. A cached answer stands in when GitHub cannot be reached.
     */
    private fun latestRelease(cache: File, maxAgeMs: Long): String = synchronized(CACHE_LOCK) {
        if (cache.isFile && System.currentTimeMillis() - cache.lastModified() < maxAgeMs) return cache.readText()
        val text = try {
            get(URL_LATEST)
        } catch (e: IOException) {
            if (cache.isFile) return cache.readText() else throw e
        }
        val tmp = File(cache.path + ".tmp")
        try {
            tmp.writeText(text)
            if (!tmp.renameTo(cache)) tmp.delete()
        } catch (_: IOException) {
            // Only a cache: the next look asks GitHub again.
            tmp.delete()
        }
        text
    }

    /** A downloaded APK, the SHA-256 its release publishes and the one it has. */
    private class Download(val apk: File, val expectedSha256: String, val actualSha256: String)

    /**
     * The release's APK in the cache, with its checksum as published and as computed while it
     * arrived; the caller compares them. The file is written beside its name and renamed once whole.
     */
    private fun download(context: Context, release: AppRelease.Release): Download {
        val expected = AppRelease.checksumOf(get(release.checksum.url), release.apk.name)
            ?: throw IOException("${release.checksum.name} does not give the APK's checksum")
        val dir = File(context.cacheDir, DOWNLOADS).apply { mkdirs() }
        val partial = File(dir, "${release.apk.name}.part")
        val apk = File(dir, release.apk.name)
        val limit = if (release.apk.size > 0) release.apk.size else MAX_APK_BYTES
        val digest = MessageDigest.getInstance("SHA-256")
        open(release.apk.url).let { c ->
            try {
                c.inputStream.use { input ->
                    partial.outputStream().use { out ->
                        val buffer = ByteArray(BUFFER)
                        var done = 0L
                        var shown = 0L
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            done += n
                            if (done > limit) throw IOException("${release.apk.name} is longer than GitHub said")
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                            if (done - shown >= PROGRESS_STEP) {
                                shown = done
                                publish(State.Downloading(release, done, release.apk.size))
                            }
                        }
                    }
                }
            } finally {
                c.disconnect()
            }
        }
        if (!partial.renameTo(apk)) throw IOException("could not move $partial to $apk")
        return Download(apk, expected, digest.digest().joinToString("") { "%02x".format(it) })
    }

    /** Why the download may not be installed, or null: [AppRelease.refusal] over what Android reads of it. */
    private fun check(context: Context, d: Download): AppRelease.Refusal? {
        val pm = context.packageManager
        val info = archiveInfo(pm, d.apk)
        return AppRelease.refusal(
            expectedSha256 = d.expectedSha256,
            actualSha256 = d.actualSha256,
            apkPackage = info?.packageName,
            apkVersionCode = info?.longVersionCode,
            ownPackage = context.packageName,
            installed = BuildConfig.VERSION_CODE.toLong(),
            sameSigner = info?.let { sameSigner(pm, context.packageName, it) },
        )
    }

    /**
     * What Android reads of [apk], its signer among it. Android 10 reads an archive's
     * certificates only when the deprecated GET_SIGNATURES is asked for too, so both flags go
     * ([AppRelease.archiveFlags]); later releases take either.
     */
    private fun archiveInfo(pm: PackageManager, apk: File): PackageInfo? {
        val flags = AppRelease.archiveFlags(PackageManager.GET_SIGNING_CERTIFICATES)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageArchiveInfo(apk.path, PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            LegacyPlatform.packageArchiveInfo(pm, apk.path, flags)
        }
    }

    /**
     * Whether [apk] is signed by the key that signed the installed app; null when Android gave
     * no signer for the archive, which leaves the check to the installer. An APK with one signer
     * gives its certificate history, as SigningInfo prefers: one of its certificates must be the
     * installed app's, which also lets an APK whose key was rotated through. One with several
     * signers gives them all, and each must be one the installed app has.
     */
    private fun sameSigner(pm: PackageManager, own: String, apk: PackageInfo): Boolean? {
        val info = apk.signingInfo ?: return null
        val certs = if (info.hasMultipleSigners()) info.apkContentsSigners else info.signingCertificateHistory
        if (certs.isNullOrEmpty()) return null
        val known = { c: Signature -> pm.hasSigningCertificate(own, c.toByteArray(), PackageManager.CERT_INPUT_RAW_X509) }
        return if (info.hasMultipleSigners()) certs.all(known) else certs.any(known)
    }

    /**
     * Hands [apk] to Android's installer in a session; [UpdateReceiver] hears how it went, and
     * opens the confirmation Android asks the owner for.
     */
    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Asked for, not promised: Android skips the confirmation only when this app
                // installed itself last time, which an APK opened in a browser did not.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out, BUFFER)
                        session.fsync(out)
                    }
                }
                // Mutable, as the installer fills in its status; explicit, as Android requires of
                // a mutable PendingIntent.
                val status = PendingIntent.getBroadcast(
                    context,
                    0,
                    Intent(context, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
                )
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            throw e
        }
    }

    /** The package that installed this app, as Android says; null when it does not know. */
    private fun installer(context: Context): String? = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        } else {
            LegacyPlatform.installerPackageName(context.packageManager, context.packageName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = TIMEOUT_MS
        c.readTimeout = TIMEOUT_MS
        c.setRequestProperty("User-Agent", "PocketSK")
        if (url.startsWith(API)) {
            c.setRequestProperty("Accept", "application/vnd.github+json")
            c.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        val code = c.responseCode
        if (code != HttpURLConnection.HTTP_OK) {
            c.disconnect()
            throw IOException("GitHub answered $code for $url")
        }
        return c
    }

    /** A small answer from GitHub as text, blocking. */
    private fun get(url: String): String {
        val c = open(url)
        try {
            val out = ByteArrayOutputStream()
            c.inputStream.use { input ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    out.write(buffer, 0, n)
                    if (out.size() > MAX_TEXT_BYTES) throw IOException("GitHub's answer is too long")
                }
            }
            return out.toString(Charsets.UTF_8.name())
        } finally {
            c.disconnect()
        }
    }

    private const val API = "https://api.github.com/"

    /** This repository's latest release; the `prebuilds` Release is never marked latest. */
    const val URL_LATEST = "${API}repos/KEGustafsson/signalk-server-android/releases/latest"

    /** Where a person reads the app's releases. */
    const val URL_RELEASES = "https://github.com/KEGustafsson/signalk-server-android/releases"

    /** GitHub's answer, in the cache Android may clear: it is fetched again. */
    private const val RELEASE_CACHE = "app-release.json"

    /** Where the APK is downloaded to; emptied before each look. */
    private const val DOWNLOADS = "app-update"

    /** How long the Dock's look keeps GitHub's answer: a day, as for the server's versions. */
    const val CHECK_MAX_AGE_MS = 24L * 60 * 60 * 1000

    private const val TIMEOUT_MS = 30_000
    private const val BUFFER = 64 * 1024
    private const val MAX_TEXT_BYTES = 1024 * 1024
    /** A release that says no size: the universal APK is 75 MB. */
    private const val MAX_APK_BYTES = 300L * 1024 * 1024
    private const val PROGRESS_STEP = 512L * 1024
    private val CACHE_LOCK = Any()
}
