package fi.pocketsk.ui

/**
 * What wants the owner's tap now (docs/PLAN.md §8.1, §16 16), most pressing first, each one thing
 * dealt with on one tab: the bottom bar puts a dot on that tab, and the tab marks the row whose
 * page deals with it. Home shows none of them, as the owner asked: it is the server and its one
 * action alone.
 *
 * Pure, so the rules of what counts and in which order are tested; [DockScreen] reads the facts
 * from [AppModel] into an [Input] and gives each [Need]'s tab its dot.
 */
internal object Needs {

    /** In the order they are shown: the server's own trouble first, then what stops it working well, then what is merely on offer. */
    enum class Need {
        /** The server stopped with a failure: the log says why. */
        SERVER_FAILED,

        /** No server installed: nothing runs until one is. */
        NO_SERVER,

        /** Notifications or local network not allowed: the server's notice, or the boat's network, are missing. */
        PERMISSIONS,

        /** The server runs without the phone's GPS, which a start from the screen gives it. */
        GPS_RESTART,

        /** A USB serial device waits for Android's permission. */
        USB_PERMISSION,

        /** The card the data is on is not in the phone: the server waits for it. */
        CARD_MISSING,

        /** The boat's main server no longer takes this phone, or the request for it ended without access. */
        MAIN_SERVER,

        /** Native addons installed without an Android build: their plugins do not load. */
        MISSING_BUILDS,

        /** Android may stop the server to save the battery. */
        BATTERY,

        /** Passage dims through the helm screen's overlay, which is not allowed. */
        PASSAGE_OVERLAY,

        /** A newer release of the server. */
        SERVER_UPDATE,

        /** A newer build of the app. */
        APP_UPDATE,

        /** Files copied over USB wait to be taken into the server's data. */
        INBOX,
    }

    /** The facts the list is made from, read from [AppModel]. */
    data class Input(
        val serverFailed: Boolean = false,
        val serverInstalled: Boolean = true,
        /** Nothing touches the server's files now: no install, backup, restore or reset runs. */
        val idle: Boolean = true,
        val permissionsMissing: Boolean = false,
        val gpsRestart: Boolean = false,
        val usbWaiting: Boolean = false,
        val cardMissing: Boolean = false,
        val mainServerTrouble: Boolean = false,
        val missingBuilds: Boolean = false,
        val batteryRestricted: Boolean = false,
        val passageNeedsOverlay: Boolean = false,
        val serverUpdate: Boolean = false,
        val appUpdate: Boolean = false,
        val inboxFiles: Int = 0,
    )

    /**
     * What needs the owner now. While an install, a backup, a restore or a reset runs, nothing is
     * offered that would start another (an update, the inbox), and a server stopped by it is not
     * a failure; the install itself shows on the Server tab.
     */
    fun list(input: Input): List<Need> = buildList {
        if (input.serverFailed && input.idle && input.serverInstalled) add(Need.SERVER_FAILED)
        if (!input.serverInstalled && input.idle) add(Need.NO_SERVER)
        if (input.permissionsMissing) add(Need.PERMISSIONS)
        if (input.gpsRestart) add(Need.GPS_RESTART)
        if (input.usbWaiting) add(Need.USB_PERMISSION)
        if (input.cardMissing) add(Need.CARD_MISSING)
        if (input.mainServerTrouble) add(Need.MAIN_SERVER)
        if (input.missingBuilds) add(Need.MISSING_BUILDS)
        if (input.batteryRestricted && input.serverInstalled) add(Need.BATTERY)
        if (input.passageNeedsOverlay) add(Need.PASSAGE_OVERLAY)
        if (input.serverUpdate && input.idle && input.serverInstalled) add(Need.SERVER_UPDATE)
        // The installer replaces the app's process, which runs the backup, restore or install.
        if (input.appUpdate && input.idle) add(Need.APP_UPDATE)
        if (input.inboxFiles > 0 && input.idle) add(Need.INBOX)
    }.sortedBy { it.ordinal }

    /**
     * How many of the phone's grants are missing, for the Phone tab's Permissions row: those the
     * server needs (notifications, local network) and those that make it better (location,
     * full-screen alarms, an unrestricted battery). Null-valued grants do not apply on this phone.
     */
    fun permissionsToAllow(
        notifications: Boolean?,
        localNetwork: Boolean?,
        location: Boolean,
        fullScreen: Boolean?,
        batteryUnrestricted: Boolean,
    ): Int = listOf(notifications, localNetwork, location, fullScreen, batteryUnrestricted).count { it == false }
}
