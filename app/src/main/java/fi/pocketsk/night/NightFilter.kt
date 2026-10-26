package fi.pocketsk.night

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import androidx.core.content.edit
import fi.pocketsk.App

/**
 * The helm screen (docs/PLAN.md §8.3): one overlay window over every app, the browser and its
 * webapps above all, that carries the red night filter, the dimmed screen and the screen kept on
 * ([NightRules]). An overlay rather than a webapp's CSS, so it works over any webapp and the Dock
 * alike, and holds the screen whatever is in front.
 *
 * The window takes no touch and no focus, so everything under it is used as if it were not there;
 * Android lets touches through it only while it is at most [NightRules.MAX_ALPHA] opaque. It sits
 * under the status bar and the navigation bar, as every app's overlay does since Android 8: those
 * stay as the phone draws them. A process showing an overlay is one Android keeps, so the filter
 * stays up with the app out of sight; when the process does go, its next start puts it back
 * ([App] calls [apply]). The process's one instance is [App]'s ([of]), which holds the window.
 *
 * The window is changed on the main thread only; [apply] may be called from any thread and posts
 * there.
 */
internal class NightFilter(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val prefs = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private var view: View? = null

    /** The owner's settings, in their own preferences file. */
    fun settings(): NightSettings = NightSettings(
        filter = prefs.getBoolean(FILTER, false),
        strength = prefs.getInt(STRENGTH, NightSettings.DEFAULT_STRENGTH),
        dim = prefs.getBoolean(DIM, false),
        brightness = prefs.getInt(BRIGHTNESS, NightSettings.DEFAULT_BRIGHTNESS),
        keepOn = prefs.getBoolean(KEEP_ON, false),
    )

    /** Keeps [settings] and puts them on the screen. */
    fun save(settings: NightSettings) {
        prefs.edit {
            putBoolean(FILTER, settings.filter)
            putInt(STRENGTH, settings.strength)
            putBoolean(DIM, settings.dim)
            putInt(BRIGHTNESS, settings.brightness)
            putBoolean(KEEP_ON, settings.keepOn)
        }
        apply()
    }

    /** Whether the owner allowed the app to draw over other apps, which the overlay needs. */
    fun allowed(): Boolean = Settings.canDrawOverlays(app)

    /** Shows, changes or takes away the overlay to match the settings and the permission. */
    fun apply() {
        main.post { show(NightRules.overlay(settings())?.takeIf { allowed() }) }
    }

    private fun show(overlay: NightRules.Overlay?) {
        val wm = app.getSystemService(WindowManager::class.java) ?: return
        val shown = view
        if (overlay == null) {
            if (shown != null) runCatching { wm.removeView(shown) }
            view = null
            return
        }
        val params = layoutParams(overlay)
        try {
            if (shown != null) {
                wm.updateViewLayout(shown, params)
            } else {
                val v = View(app).apply { setBackgroundColor(NightRules.COLOUR) }
                wm.addView(v, params)
                view = v
            }
        } catch (_: RuntimeException) {
            // The permission taken back in the settings (a BadTokenException, a SecurityException):
            // there is no overlay, and the Dock shows the permission as missing.
            view = null
        }
    }

    private fun layoutParams(overlay: NightRules.Overlay): WindowManager.LayoutParams {
        var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        if (overlay.keepOn) flags = flags or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            flags,
            PixelFormat.TRANSLUCENT,
        ).apply {
            alpha = overlay.alpha
            screenBrightness = overlay.brightness
            // Over a notch's edge too, so no strip of the screen is left untinted.
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    companion object {
        /** The process's night filter, the one [App] holds. */
        fun of(context: Context): NightFilter = (context.applicationContext as App).night

        private const val FILE = "night"
        private const val FILTER = "filter"
        private const val STRENGTH = "strength"
        private const val DIM = "dim"
        private const val BRIGHTNESS = "brightness"
        private const val KEEP_ON = "keep_on"
    }
}
