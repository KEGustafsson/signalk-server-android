package fi.pocketsk.install

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

/** Which slot is live and which one the next install goes to. */
class SlotsTest {
    @Test
    fun readsTheSlotALinkNames() {
        assertEquals("A", Slots.nameOf("slots/A"))
        assertEquals("B", Slots.nameOf("/data/user/0/fi.pocketsk/files/sk/slots/B/"))
    }

    @Test
    fun anythingElseIsNoSlot() {
        assertNull(Slots.nameOf(null))
        assertNull(Slots.nameOf(""))
        assertNull(Slots.nameOf("slots/C"))
        assertNull(Slots.nameOf("slots/a"))
    }

    @Test
    fun installsGoToTheSlotThatIsNotLive() {
        assertEquals("B", Slots.inactive("A"))
        assertEquals("A", Slots.inactive("B"))
        // A fresh phone has no live slot; the first install goes to A.
        assertEquals("A", Slots.inactive(null))
    }

    @Test
    fun linksAreRelativeAndOnlyToSlots() {
        assertEquals("slots/B", Slots.linkTarget("B"))
        assertEquals("B", Slots.nameOf(Slots.linkTarget("B")))
        assertThrows(IllegalArgumentException::class.java) { Slots.linkTarget("../home") }
    }
}
