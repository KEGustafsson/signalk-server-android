package fi.pocketsk.server

import android.content.Context
import android.net.Uri
import fi.pocketsk.bridge.BridgePlugin
import fi.pocketsk.net.Failure
import fi.pocketsk.net.transportThread
import fi.pocketsk.node.NodeRuntime
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The Dock's one backup or restore at a time (docs/PLAN.md §16 10.8), held by the process like
 * [fi.pocketsk.install.InstallJob], so a rotation or the system's file picker coming and going
 * still finds the one that runs. The file is wherever the owner put it through the system's
 * picker: the phone, a card, Drive or Nextcloud. The passphrase is used once and wiped.
 *
 * A restore runs only while the server is stopped and writes `.signalk`'s files under
 * [DataStore.LOCK]; a start asked for at any time before npm is done waits for it ([isRestoring]). Then the bridge's plugin is put
 * in place and the plugins `package.json` lists are installed, as Signal K's own restore does:
 * `npm install --ignore-scripts` in `.signalk`, the App Store's flags. That needs the network; the
 * settings are restored whether or not it has it, and a plugin npm could not install is missing
 * until the restore is run again with the network there (or it is installed from the App Store).
 */
internal object BackupJob {

    /** What one backup or restore came to. */
    sealed interface State {
        val restore: Boolean

        data class Working(override val restore: Boolean, val installing: Boolean = false) : State
        data class Saved(val files: Int) : State {
            override val restore = false
        }

        /** [npmCode] is npm's exit code (0 when the plugins are in), null when it ran out of time. */
        data class Restored(val files: Int, val npmCode: Int?) : State {
            override val restore = true
        }

        data class Failed(override val restore: Boolean, val why: Why, val detail: String = "") : State
    }

    enum class Why { WRONG_PASSPHRASE, NOT_A_BACKUP, SERVER_RUNNING, NO_SERVER_YET, FAILED }

    /** A screen's view of the job: null before the first. Called on the job's thread, or the observing one at first. */
    fun interface Observer {
        fun onState(state: State?)
    }

    private val observers = CopyOnWriteArraySet<Observer>()
    @Volatile var latest: State? = null
        private set

    val isRunning: Boolean get() = latest is State.Working

    /**
     * A restore runs, from the moment it is asked for until npm is done: the supervisor starts no
     * server meanwhile ([Supervisor]), so none reads half a restore or a plugin tree npm is writing.
     */
    val isRestoring: Boolean get() = (latest as? State.Working)?.restore == true

    /** Writes a backup to [uri]; false, nothing started, while another runs. Wipes [passphrase]. */
    @Synchronized
    fun save(context: Context, uri: Uri, passphrase: CharArray): Boolean {
        if (isRunning) return false.also { passphrase.fill(' ') }
        val app = context.applicationContext
        publish(State.Working(restore = false))
        work(app, restore = false) {
            val configDir = NodeRuntime(app).configDir
            val entries = synchronized(DataStore.LOCK) { Backup.collect(configDir) }
            val bytes = ByteArrayOutputStream().also {
                try {
                    AesZip.write(it, entries, passphrase)
                } finally {
                    passphrase.fill(' ')
                }
            }.toByteArray()
            (app.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("the file cannot be written")).use { it.write(bytes) }
            ServerStatus.log(app).note("backup of ${entries.size} settings files saved")
            State.Saved(entries.size)
        }
        return true
    }

    /** Restores the backup at [uri]; false, nothing started, while another runs. Wipes [passphrase]. */
    @Synchronized
    fun restore(context: Context, uri: Uri, passphrase: CharArray): Boolean {
        if (isRunning) return false.also { passphrase.fill(' ') }
        val app = context.applicationContext
        publish(State.Working(restore = true))
        work(app, restore = true) {
            val entries = try {
                AesZip.read(read(app, uri), passphrase)
            } finally {
                passphrase.fill(' ')
            }
            val runtime = NodeRuntime(app)
            if (!runtime.configDir.isDirectory) return@work State.Failed(true, Why.NO_SERVER_YET)
            val written = synchronized(DataStore.LOCK) {
                if (!stopped()) return@work State.Failed(true, Why.SERVER_RUNNING)
                Backup.restore(runtime.configDir, File(runtime.home, STAGING), entries, restoredMarker(runtime))
            }
            val log = ServerStatus.log(app)
            log.note("restored ${written.size} settings files from a backup: ${written.joinToString(", ")}")
            publish(State.Working(restore = true, installing = true))
            // The settings are in from here on: a failure now is the plugins', and says so, rather
            // than a "not restored" that would leave the owner thinking the old settings stand.
            val code = try {
                BridgePlugin.install(app, runtime)
                runtime.ensureExtracted()
                log.note("$ npm ${NPM_ARGS.joinToString(" ")} (in .signalk, the plugins package.json lists)")
                runtime.npm(*NPM_ARGS, cwd = runtime.configDir).drain(NPM_TIMEOUT_S) { log.line(it) }
            } catch (e: IOException) {
                log.note("the restored plugins could not be installed: ${e.message ?: e}")
                NPM_NOT_RUN
            }
            log.note(
                when (code) {
                    0 -> "the restored plugins are installed"
                    null -> "npm ran out of time installing the restored plugins"
                    NPM_NOT_RUN -> "npm did not run"
                    else -> "npm failed with exit code $code installing the restored plugins"
                },
            )
            State.Restored(written.size, code)
        }
        return true
    }

    fun observe(observer: Observer) {
        observers += observer
        observer.onState(latest)
    }

    fun detach(observer: Observer) {
        observers -= observer
    }

    private fun work(app: Context, restore: Boolean, body: () -> State) {
        transportThread(if (restore) "backup-restore" else "backup-save", onError = { e -> finish(app, restore, e) }) {
            publish(body())
        }
    }

    private fun finish(app: Context, restore: Boolean, e: Throwable) {
        val state = when (e) {
            is AesZip.WrongPassphrase -> State.Failed(restore, Why.WRONG_PASSPHRASE)
            is AesZip.NotOurs -> State.Failed(restore, Why.NOT_A_BACKUP, e.message.orEmpty())
            else -> State.Failed(restore, Why.FAILED, Failure.text(e))
        }
        ServerStatus.log(app).note("${if (restore) "restore" else "backup"} failed: $e")
        publish(state)
    }

    /** The file at [uri], refused past [AesZip.MAX_BYTES] before it is all read. */
    private fun read(app: Context, uri: Uri): ByteArray {
        val input = app.contentResolver.openInputStream(uri) ?: throw IOException("the file cannot be read")
        return input.use {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                if (out.size() > AesZip.MAX_BYTES) throw AesZip.NotOurs("over ${AesZip.MAX_BYTES / (1024 * 1024)} MB")
            }
            out.toByteArray()
        }
    }

    /** Whether no server runs or is on its way, which a restore needs: it must not change the files under one. */
    private fun stopped(): Boolean = ServerStatus.state.let { it is ServerState.Stopped || it is ServerState.Failed }

    @Synchronized
    private fun publish(state: State) {
        latest = state
        for (o in observers) o.onState(state)
    }

    /**
     * What the last restore wrote, until the USB folder's next sync has taken it as the phone's
     * ([Backup.restore], the supervisor's mirror). Beside `.signalk`, not in it, so it is not
     * copied to the USB folder itself.
     */
    fun restoredMarker(runtime: NodeRuntime): File = File(runtime.home, RESTORED)

    /** In `files/home`, beside `.signalk`: the same file system, and kept out of backups with it. */
    private const val STAGING = ".pocketsk-restore"
    private const val RESTORED = ".pocketsk-restored"

    /** The code a restore reports when npm could not be started at all. */
    private const val NPM_NOT_RUN = -1

    private val NPM_ARGS = arrayOf("install", "--ignore-scripts", "--omit=dev", "--no-audit", "--no-fund")
    private const val NPM_TIMEOUT_S = 20 * 60L
}
