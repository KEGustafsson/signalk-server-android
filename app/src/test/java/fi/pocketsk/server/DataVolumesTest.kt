package fi.pocketsk.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which card a media broadcast is about: its path is `/storage/<uuid>`. */
class DataVolumesTest {
    @Test
    fun aBroadcastNamesItsCardByTheMountPath() {
        assertTrue(DataVolumes.isVolume("/storage/1234-ABCD", "1234-ABCD"))
        assertTrue(DataVolumes.isVolume("/storage/1234-abcd", "1234-ABCD"))
        assertFalse(DataVolumes.isVolume("/storage/5678-EF01", "1234-ABCD"))
        assertFalse(DataVolumes.isVolume("/storage/emulated/0", "1234-ABCD"))
        assertFalse(DataVolumes.isVolume(null, "1234-ABCD"))
    }
}
