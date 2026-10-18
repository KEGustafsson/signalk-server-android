package fi.pocketsk.node

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Reading the launcher's group record, and refusing every id that `kill(-id)` would misread. */
class NodeProcessTest {
    @Test
    fun readsTheLaunchersRecord() {
        assertEquals(12345, NodeProcess.parseGroupId("12345\n"))
        assertEquals(2, NodeProcess.parseGroupId(" 2 "))
    }

    @Test
    fun refusesIdsThatWouldSignalTheWrongProcesses() {
        // kill(0) is the app's own group, kill(-1) everything the app may signal, kill(+n) one pid.
        assertNull(NodeProcess.parseGroupId("0\n"))
        assertNull(NodeProcess.parseGroupId("1\n"))
        assertNull(NodeProcess.parseGroupId("-42\n"))
    }

    @Test
    fun refusesAnythingButOneNumber() {
        assertNull(NodeProcess.parseGroupId(""))
        assertNull(NodeProcess.parseGroupId("12 34"))
        assertNull(NodeProcess.parseGroupId("pid"))
        assertNull(NodeProcess.parseGroupId("99999999999"))
    }
}
