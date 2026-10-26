package fi.pocketsk.server

import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import fi.pocketsk.R
import java.io.File

/**
 * Where the server's data may go ([DataStore]): the app's folder on the phone's shared storage
 * ([phone]), and every removable volume, an SD card or a USB drive ([list]), on which Android gives
 * the app a folder of its own (`Android/data/fi.pocketsk/files`). Such a folder needs no
 * permission, is a real path the server's node can use as the app can (same user), is what a
 * computer sees over USB, and goes with the app when it is uninstalled.
 *
 * A volume is known by its UUID, which stays when its mount path or the phone's order of volumes
 * changes. [list] touches the disk (Android makes the folders it reports): off the main thread.
 */
internal object DataVolumes {
    /** One card: [root] is where the data goes on it; [free] and [total] are bytes. */
    data class Volume(val uuid: String, val name: String, val root: File, val mounted: Boolean, val free: Long, val total: Long)

    fun list(context: Context): List<Volume> {
        val storage = context.getSystemService(StorageManager::class.java) ?: return emptyList()
        // Null entries are volumes not mounted now: Android gives them no folder.
        return context.getExternalFilesDirs(null).filterNotNull().mapNotNull { dir ->
            val volume = storage.getStorageVolume(dir) ?: return@mapNotNull null
            val uuid = volume.uuid
            if (!volume.isRemovable || uuid == null) return@mapNotNull null
            Volume(
                uuid = uuid,
                name = volume.getDescription(context),
                root = File(dir, DataStore.CARD_DIR),
                mounted = volume.state == Environment.MEDIA_MOUNTED,
                free = free(dir),
                total = total(dir),
            )
        }
    }

    /**
     * The bytes free to the app on [dir]'s volume, or on that of its nearest folder that exists (a
     * copy's destination is made as it is written); 0 when there is none.
     */
    fun free(dir: File): Long = statFs(dir)?.availableBytes ?: 0L

    /** The size of [dir]'s volume in bytes, as [free] finds it. */
    fun total(dir: File): Long = statFs(dir)?.totalBytes ?: 0L

    private fun statFs(dir: File): StatFs? {
        var d: File? = dir
        while (d != null && !d.exists()) d = d.parentFile
        return try {
            d?.let { StatFs(it.path) }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * The data's folder on the phone's shared storage, `Android/data/fi.pocketsk/files/signalk`,
     * where a computer on the phone's USB finds it; null while that storage is not available.
     */
    fun phone(context: Context): File? = context.getExternalFilesDir(null)
        ?.takeIf { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
        ?.let { File(it, DataStore.CARD_DIR) }

    /**
     * The USB inbox ([Inbox]) on the phone's shared storage, which a computer over USB can write;
     * null while that storage is not available.
     */
    fun inbox(context: Context): File? = context.getExternalFilesDir(null)?.let { File(it, Inbox.DIR) }

    /** The card [uuid], when it is in the phone and mounted. */
    fun mounted(context: Context, uuid: String): Volume? = list(context).firstOrNull { it.uuid == uuid && it.mounted }

    /** Whether a media broadcast's path, `/storage/<uuid>`, is the volume [uuid]. */
    fun isVolume(path: String?, uuid: String): Boolean = path != null && File(path).name.equals(uuid, ignoreCase = true)
}

/** [ServerState.Storage] in a line, as the notification, the Dock and the spike screen say it. */
internal fun storageText(context: Context, state: ServerState.Storage): String = when {
    state.waiting -> context.getString(R.string.server_storage_waiting, state.volume)
    state.volume == null -> context.getString(R.string.server_storage_moving_phone)
    else -> context.getString(R.string.server_storage_moving, state.volume)
}
