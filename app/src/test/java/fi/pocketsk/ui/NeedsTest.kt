package fi.pocketsk.ui

import fi.pocketsk.ui.Needs.Input
import fi.pocketsk.ui.Needs.Need
import org.junit.Assert.assertEquals
import org.junit.Test

class NeedsTest {

    @Test
    fun aServerWithNothingWrongNeedsNothing() {
        assertEquals(emptyList<Need>(), Needs.list(Input()))
    }

    @Test
    fun troubleComesBeforeWhatIsOnOffer() {
        val all = Input(
            serverFailed = true,
            permissionsMissing = true,
            gpsRestart = true,
            usbWaiting = true,
            cardMissing = true,
            mainServerTrouble = true,
            missingBuilds = true,
            batteryRestricted = true,
            passageNeedsOverlay = true,
            serverUpdate = true,
            appUpdate = true,
            inboxFiles = 3,
        )
        assertEquals(
            listOf(
                Need.SERVER_FAILED, Need.PERMISSIONS, Need.GPS_RESTART, Need.USB_PERMISSION, Need.CARD_MISSING,
                Need.MAIN_SERVER, Need.MISSING_BUILDS, Need.BATTERY, Need.PASSAGE_OVERLAY, Need.SERVER_UPDATE,
                Need.APP_UPDATE, Need.INBOX,
            ),
            Needs.list(all),
        )
    }

    @Test
    fun nothingThatStartsAnotherJobIsOfferedWhileOneRuns() {
        val busy = Input(idle = false, serverFailed = true, serverUpdate = true, inboxFiles = 2, appUpdate = true)
        // Not even the app's own update, whose installer replaces the process running the job.
        assertEquals(emptyList<Need>(), Needs.list(busy))
    }

    @Test
    fun withNoServerInstalledOnlyTheInstallIsAskedForOfTheServer() {
        val none = Input(serverInstalled = false, serverFailed = true, serverUpdate = true, batteryRestricted = true)
        assertEquals(listOf(Need.NO_SERVER), Needs.list(none))
    }

    @Test
    fun theMissingGrantsAreCountedWhereTheyApply() {
        assertEquals(0, Needs.permissionsToAllow(null, null, location = true, fullScreen = null, batteryUnrestricted = true))
        assertEquals(2, Needs.permissionsToAllow(false, true, location = false, fullScreen = true, batteryUnrestricted = true))
        assertEquals(5, Needs.permissionsToAllow(false, false, location = false, fullScreen = false, batteryUnrestricted = false))
    }
}
