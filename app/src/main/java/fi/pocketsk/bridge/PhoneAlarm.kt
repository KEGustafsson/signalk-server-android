package fi.pocketsk.bridge

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import fi.pocketsk.AlarmActivity
import fi.pocketsk.R
import fi.pocketsk.bridge.BridgeProtocol.Alarm
import fi.pocketsk.net.reporting
import fi.pocketsk.server.ServerStatus
import fi.pocketsk.server.SignalKService

/**
 * The boat's alarms on the phone (docs/PLAN.md §7.1, alarms in): an anchor alarm that wakes a
 * phone asleep in a pocket, which no webapp can do. The plugin sends the alarms ([update]),
 * [AlarmBook] says what to show and whether to ring, and this does it: a notification on a
 * channel of its own with a full-screen intent ([AlarmActivity], over the lock screen, the screen
 * turned on), the phone's alarm sound looped on the alarm stream, and vibration, until the alarm
 * is silenced, on the phone ([silence], which the plugin is told of) or anywhere else on the boat
 * (the server's next list has it without sound).
 *
 * The sound is played here rather than as the channel's: a notification's sound plays once, or
 * until the shade is pulled down when insistent, while an alarm must go on until someone silences
 * it; and on the alarm stream it is heard with the phone on silent and through Do Not Disturb's
 * default, which lets alarms through. The channel is silent for that reason. Without the
 * notification permission the phone still rings and vibrates; without the full-screen permission
 * (Android 14 and later, the Dock asks for it) the alarm is a heads-up notification and the screen
 * stays dark, but rings all the same.
 *
 * When the plugin goes (the server restarting), the alarms stand for [LOST_MS], so a restart
 * neither silences an anchor alarm nor rings it twice; then they are cleared, since nothing would
 * tell the phone they ended. Everything runs on "bridge-alarm".
 */
internal class PhoneAlarm(
    context: Context,
    private val send: (String) -> Unit,
    private val note: (String) -> Unit,
) {
    private val app = context.applicationContext
    private val thread = HandlerThread("bridge-alarm").apply { start() }
    private val handler = Handler(thread.looper)
    private val book = AlarmBook()
    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ALARM)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    // All below on [thread] only.
    private var shown = AlarmBook.View.NONE
    /** What the notification shows now, so an unchanged list does not post it again. */
    private var posted: List<Any?>? = null
    private var ringing = false
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var vibrator: Vibrator? = null

    private val forget = Runnable {
        note("alarm: the server's plugin did not come back; the phone's alarms are cleared")
        show(book.clear())
    }

    init {
        handler.post { reporting({ note("alarm: cannot make the alarm channel: $it") }) { createChannel() } }
    }

    /** The plugin's list of the boat's alarms. */
    fun update(list: List<Alarm>) = handler.post {
        handler.removeCallbacks(forget)
        show(book.update(list))
    }

    /** The owner's Silence for the alarm [id]: quiet on the phone now, and on the server as it allows. */
    fun silence(id: String) = handler.post {
        val alarm = book.silence(id) ?: return@post
        note("alarm: ${alarm.path} silenced on the phone")
        if (alarm.action != null) send(BridgeProtocol.silence(id))
        show(book.view())
    }

    /**
     * A permission may have been granted (notifications, full-screen alarms): an alarm that could
     * not be shown is posted now, as if it had just begun.
     */
    fun refresh() = handler.post { if (posted == null) notify(shown, alert = shown.ring) }

    /** The plugin went: the alarms stand a while for it to come back. */
    fun lost() = handler.post {
        handler.removeCallbacks(forget)
        if (shown.alarms.isNotEmpty()) handler.postDelayed(forget, LOST_MS)
    }

    /** The server stopped: no alarms. */
    fun stop() = handler.post {
        handler.removeCallbacks(forget)
        show(book.clear())
    }

    /** For good, when the service goes. */
    fun close() = handler.post {
        handler.removeCallbacks(forget)
        show(book.clear())
        thread.quitSafely()
    }

    private fun show(view: AlarmBook.View) {
        val before = shown.alarms.associate { it.id to it.state }
        for (a in view.alarms) if (before[a.id] != a.state) note("alarm: ${a.state} ${a.path}: ${a.message}")
        val rang = shown.ring
        shown = view
        ServerStatus.publishAlarms(view)
        ring(view.ring)
        notify(view, alert = view.ring && !rang)
    }

    // ---- notification --------------------------------------------------------------------------

    /**
     * The most urgent alarm, with Silence (or Acknowledge, for an emergency, which Signal K does not
     * let be silenced) while there is something to do about it, and the full-screen intent while
     * it rings. Posted again only when what it shows changes; [alert] when an alarm has just begun
     * to ring, which is when the full-screen screen opens.
     */
    private fun notify(view: AlarmBook.View, alert: Boolean) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        val head = view.head
        if (head == null) {
            posted = null
            nm.cancel(ID)
            return
        }
        val key = listOf(head.id, head.state, head.message, head.action, view.alarms.size, view.ring, head.id in view.quiet)
        if (key == posted && !alert) return
        // Recorded only once posted, so a permission granted while the alarm stands posts it ([refresh]).
        if (!mayNotify()) return
        posted = key
        reporting({ note("alarm: cannot show the alarm: $it") }) { nm.notify(ID, build(view, head, alert)) }
    }

    private fun build(view: AlarmBook.View, head: Alarm, alert: Boolean): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            app,
            0,
            Intent(app, AlarmActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION),
            flags,
        )
        val text = head.message.ifEmpty { head.path }
        val more = view.alarms.size - 1
        val b = NotificationCompat.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(app.getString(R.string.alarm_title, stateLabel(app, head.state)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n${head.path}"))
            .setContentIntent(open)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(!alert)
        if (more > 0) b.setSubText(app.resources.getQuantityString(R.plurals.alarm_more, more, more))
        if (view.ring) b.setFullScreenIntent(open, true)
        if (head.action != null || head.id !in view.quiet) {
            val silence = PendingIntent.getService(
                app,
                SILENCE_CODE,
                Intent(app, SignalKService::class.java).setAction(SignalKService.ACTION_SILENCE).putExtra(SignalKService.EXTRA_ALARM, head.id),
                flags,
            )
            b.addAction(0, app.getString(actionLabel(head)), silence)
        }
        return b.build()
    }

    /** Without the permission the alarm is not shown, but it still rings. */
    private fun mayNotify(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(app, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Silent and vibration-free: the sound and the vibration are this class's own (see above). */
    private fun createChannel() {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.alarm_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                description = app.getString(R.string.alarm_channel_description)
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    // ---- sound and vibration -------------------------------------------------------------------

    private fun ring(on: Boolean) {
        if (on == ringing) return
        ringing = on
        if (on) startRinging() else stopRinging()
    }

    private fun startRinging() {
        val audio = app.getSystemService(AudioManager::class.java)
        if (audio != null) {
            if (audio.getStreamVolume(AudioManager.STREAM_ALARM) == 0) note("alarm: the phone's alarm volume is at 0: the alarm vibrates but is not heard")
            focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attributes).build().also {
                reporting({}) { audio.requestAudioFocus(it) }
            }
        }
        player = sounds().firstNotNullOfOrNull(::play)
        if (player == null) note("alarm: no alarm sound could be played; the phone vibrates only")
        vibrator = app.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }?.also { v ->
            val effect = VibrationEffect.createWaveform(VIBRATION, 0)
            reporting({ note("alarm: cannot vibrate: $it") }) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_ALARM))
                } else {
                    v.vibrate(effect)
                }
            }
        }
    }

    private fun stopRinging() {
        player?.let { p -> reporting({}) { p.stop() }; p.release() }
        player = null
        vibrator?.let { v -> reporting({}) { v.cancel() } }
        vibrator = null
        focus?.let { f -> reporting({}) { app.getSystemService(AudioManager::class.java)?.abandonAudioFocusRequest(f) } }
        focus = null
    }

    /** The phone's alarm sound, else its ringtone, else its notification sound: whichever plays. */
    private fun sounds(): List<Uri> = listOf(RingtoneManager.TYPE_ALARM, RingtoneManager.TYPE_RINGTONE, RingtoneManager.TYPE_NOTIFICATION)
        .mapNotNull { RingtoneManager.getDefaultUri(it) }

    /** [uri] looped on the alarm stream, or null when it cannot be played (no such sound set). */
    private fun play(uri: Uri): MediaPlayer? {
        val p = MediaPlayer()
        return try {
            p.setAudioAttributes(attributes)
            p.setDataSource(app, uri)
            p.isLooping = true
            p.prepare()
            p.start()
            p
        } catch (e: Exception) {
            // Broadly: a missing sound, a provider that refuses it, a codec; the next is tried.
            p.release()
            null
        }
    }

    companion object {
        private const val CHANNEL = "alarms"

        /** The service's own notifications are 1 and 2. */
        private const val ID = 3
        private const val SILENCE_CODE = 20

        /** How long the alarms stand without the plugin, for a server restart to bring it back. */
        private const val LOST_MS = 2 * 60_000L

        /** Off, on, off, on, a pause: repeated from the start. */
        private val VIBRATION = longArrayOf(0, 800, 400, 800, 1200)

        /** The state as the alarm's title says it. */
        fun stateLabel(context: Context, state: String): String = context.getString(
            when (state) {
                "emergency" -> R.string.alarm_state_emergency
                "alarm" -> R.string.alarm_state_alarm
                "warn" -> R.string.alarm_state_warn
                else -> R.string.alarm_state_alert
            },
        )

        /** Acknowledge where the server allows only that (an emergency), else Silence. */
        fun actionLabel(alarm: Alarm): Int = if (alarm.action == Alarm.ACKNOWLEDGE) R.string.alarm_acknowledge else R.string.alarm_silence
    }
}
