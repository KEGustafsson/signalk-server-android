package fi.pocketsk

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import androidx.core.graphics.scale
import androidx.core.net.toUri
import fi.pocketsk.node.NodeRuntime
import fi.pocketsk.signalk.Webapps
import java.io.File

/**
 * The installed webapps on the phone (docs/PLAN.md §8.3): found in the packages on disk, and
 * pinned to the home screen as shortcuts that open them in the browser. A shortcut carries the
 * deep link [Webapps.link], which [MainActivity] takes: it starts the server first when it is
 * stopped and opens the webapp once the server answers, so a tablet at the helm needs one tap
 * from its home screen to the chart.
 */
internal object WebappShortcuts {

    /** A webapp found on disk, with its icon file when it has one there. */
    data class Found(val webapp: Webapps.Webapp, val icon: File?)

    /**
     * The webapps installed: the configuration directory's packages first, where the admin UI's
     * App Store puts them, then the live server's own dependencies. Blocking, a worker thread
     * only: it reads every package's `package.json` in both, a few hundred small files.
     */
    fun list(runtime: NodeRuntime): List<Found> {
        val roots = listOf(
            File(runtime.configDir, "node_modules"),
            File(runtime.current, "lib/node_modules/signalk-server/node_modules"),
        )
        // A name in both keeps the configuration directory's: that is the one the server serves.
        return roots.flatMap(::scan).distinctBy { it.webapp.name }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.webapp.title })
    }

    /** True when the home screen takes pinned shortcuts (most launchers since Android 8). */
    fun pinSupported(context: Context): Boolean = ShortcutManagerCompat.isRequestPinShortcutSupported(context)

    /**
     * Asks the home screen to pin [found]: the launcher shows its own dialog, so true means only
     * that it was asked. The shortcut's id is the package name, so pinning one again replaces it.
     */
    fun pin(context: Context, found: Found): Boolean {
        val w = found.webapp
        val intent = Intent(Intent.ACTION_VIEW, Webapps.link(w.name).toUri(), context, MainActivity::class.java)
        val info = ShortcutInfoCompat.Builder(context, "webapp:${w.name}")
            .setShortLabel(w.title)
            .setLongLabel(w.title)
            .setIcon(icon(context, found.icon))
            .setIntent(intent)
            .build()
        return ShortcutManagerCompat.requestPinShortcut(context, info, null)
    }

    /** The webapp's own icon when it is a bitmap Android can read, scaled for a launcher; else the app's. */
    private fun icon(context: Context, file: File?): IconCompat {
        val bitmap = file?.takeIf { it.isFile && it.length() <= MAX_ICON_BYTES }?.let(::decode)
            ?: return IconCompat.createWithResource(context, R.mipmap.ic_launcher)
        return IconCompat.createWithBitmap(fit(bitmap))
    }

    /**
     * [file] decoded at a fraction of its size when it is large: its bounds are read first, so a
     * small, well-compressed file of huge dimensions cannot make the decoder allocate them all.
     */
    private fun decode(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val options = BitmapFactory.Options().apply { inSampleSize = Webapps.sampleSize(bounds.outWidth, bounds.outHeight, ICON_PX) }
        return BitmapFactory.decodeFile(file.path, options)
    }

    private fun fit(b: Bitmap): Bitmap {
        val longest = maxOf(b.width, b.height)
        if (longest <= ICON_PX) return b
        val f = ICON_PX.toFloat() / longest
        return b.scale((b.width * f).toInt().coerceAtLeast(1), (b.height * f).toInt().coerceAtLeast(1))
    }

    private fun scan(root: File): List<Found> = buildList {
        for (dir in root.listFiles().orEmpty().sortedBy { it.name }) {
            if (dir.name.startsWith("@")) {
                for (sub in dir.listFiles().orEmpty().sortedBy { it.name }) read(sub)?.let(::add)
            } else if (!dir.name.startsWith(".")) {
                read(dir)?.let(::add)
            }
        }
    }

    /** [dir]'s webapp: its `package.json` parsed only when it is small and may be one ([Webapps.mayBeWebapp]). */
    private fun read(dir: File): Found? {
        val pkg = File(dir, "package.json")
        if (!pkg.isFile || pkg.length() > MAX_PACKAGE_BYTES) return null
        val text = try {
            pkg.readText()
        } catch (_: java.io.IOException) {
            return null
        }
        if (!Webapps.mayBeWebapp(text)) return null
        val webapp = Webapps.parse(text) ?: return null
        return Found(webapp, webapp.icon?.let { inside(dir, it) })
    }

    /**
     * [path] under [dir], or null when it leaves it: [Webapps.parse] keeps `..` out of the
     * name, and this keeps out a link inside the package that points elsewhere, so a shortcut's
     * icon is only ever read from the package itself.
     */
    private fun inside(dir: File, path: String): File? = try {
        val root = dir.canonicalFile
        File(dir, path).canonicalFile.takeIf { it.toPath().startsWith(root.toPath()) && it != root }
    } catch (_: java.io.IOException) {
        null
    }

    /** A package.json bigger than this is not read: Signal K's own are a few kilobytes. */
    private const val MAX_PACKAGE_BYTES = 256 * 1024L

    /** An icon file bigger than this is not decoded for a shortcut. */
    private const val MAX_ICON_BYTES = 2 * 1024 * 1024L

    /** The longest side a shortcut's icon is scaled to: a launcher's largest at xxxhdpi. */
    private const val ICON_PX = 192
}
