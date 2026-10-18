package fi.pocketsk.server

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File

/**
 * The server's state and log as the screens see them, held for the process rather than by the
 * service: a screen opened after the server gave up (no service any more) still shows why, and
 * one rotation hands the same state to the next screen, the way [fi.pocketsk.install.InstallJob]
 * does for an install. One observer at a time, the screen in front.
 */
internal object ServerStatus {

    /** A screen's view of the server. Called on the main thread. */
    fun interface Observer {
        /** [state] changed, or the log grew; read [ServerLog.tail] for the latter. */
        fun onChange(state: ServerState)
    }

    @Volatile var state: ServerState = ServerState.Stopped
        private set

    @Volatile private var observer: Observer? = null
    private var log: ServerLog? = null
    private val main = Handler(Looper.getMainLooper())
    private var logPending = false

    /** The process's [ServerLog], made on first use. */
    @Synchronized
    fun log(context: Context): ServerLog = log ?: ServerLog(File(context.applicationContext.filesDir, "logs")).also {
        it.listener = ::logged
        log = it
    }

    /** Makes [observer] the one told, and tells it the state at once. Main thread. */
    fun observe(observer: Observer) {
        this.observer = observer
        observer.onChange(state)
    }

    /** Stops telling [observer], unless another has taken its place already. Main thread. */
    fun detach(observer: Observer) {
        if (this.observer === observer) this.observer = null
    }

    /** From the supervisor, on its threads. */
    fun publish(state: ServerState) {
        this.state = state
        main.post { observer?.onChange(this.state) }
    }

    /**
     * The log grew: the screen hears of it at most every [LOG_GAP_MS], so a chatty plugin does
     * not re-render a 300-line view for every line, and the last line of a burst is never left
     * unshown, since the post that comes due reads the log as it is then.
     */
    private fun logged() {
        synchronized(this) {
            if (logPending) return
            logPending = true
        }
        main.postDelayed({
            synchronized(this) { logPending = false }
            observer?.onChange(state)
        }, LOG_GAP_MS)
    }

    private const val LOG_GAP_MS = 500L
}
