package fi.pocketsk.bridge

import fi.pocketsk.bridge.BridgeProtocol.Alarm
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmBookTest {
    private val book = AlarmBook()
    private val anchor = Alarm("a", "notifications.navigation.anchor", "alarm", "Anchor dragging", sound = true, action = Alarm.SILENCE)
    private val engine = Alarm("e", "notifications.propulsion.port.temperature", "warn", "Engine hot", sound = true, action = Alarm.SILENCE)

    @Test
    fun anAlarmWithSoundRingsAndTheMostUrgentHeadsTheList() {
        val view = book.update(listOf(engine, anchor))
        assertTrue(view.ring)
        assertEquals(listOf(anchor, engine), view.alarms)
        assertEquals(anchor, view.head)
    }

    @Test
    fun anAlarmTheServerSilencedIsShownButDoesNotRing() {
        val view = book.update(listOf(anchor.copy(sound = false)))
        assertFalse(view.ring)
        assertEquals(1, view.alarms.size)
    }

    @Test
    fun silenceQuietsThePhoneAtOnceUntilTheAlarmGetsWorse() {
        book.update(listOf(anchor))
        assertEquals(anchor, book.silence("a"))
        assertFalse(book.view().ring)
        assertEquals(setOf("a"), book.view().quiet)
        // The server could not silence it: still quiet here.
        assertFalse(book.update(listOf(anchor)).ring)
        assertTrue(book.update(listOf(anchor.copy(state = "emergency"))).ring)
        assertTrue(book.view().quiet.isEmpty())
    }

    @Test
    fun anAlarmThatGoesAndComesBackRingsAgain() {
        book.update(listOf(anchor))
        book.silence("a")
        assertFalse(book.update(emptyList()).ring)
        assertTrue(book.update(listOf(anchor)).ring)
    }

    @Test
    fun silencingOneLeavesTheOtherRinging() {
        book.update(listOf(anchor, engine))
        book.silence("a")
        assertTrue(book.view().ring)
        book.silence("e")
        assertFalse(book.view().ring)
    }

    @Test
    fun silenceOfAnUnknownAlarmDoesNothing() {
        book.update(listOf(anchor))
        assertNull(book.silence("x"))
        assertTrue(book.view().ring)
    }

    @Test
    fun clearForgetsEverything() {
        book.update(listOf(anchor))
        book.silence("a")
        assertEquals(AlarmBook.View.NONE, book.clear())
        assertNull(book.view().head)
        assertTrue(book.update(listOf(anchor)).ring)
    }
}
