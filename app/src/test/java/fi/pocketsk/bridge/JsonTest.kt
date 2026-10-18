package fi.pocketsk.bridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class JsonTest {
    @Test
    fun readsWhatThePluginSends() {
        val v = Json.parse("""{"t":"subscribe","sensors":["battery","baro"],"rateHz":{"baro":0.1},"x":[true,false,null,-1.5e2,"a\"\\\n\u00e4"]}""")
        assertEquals(
            mapOf(
                "t" to "subscribe",
                "sensors" to listOf("battery", "baro"),
                "rateHz" to mapOf("baro" to 0.1),
                "x" to listOf(true, false, null, -150.0, "a\"\\\nä"),
            ),
            v,
        )
        assertEquals(emptyMap<String, Any?>(), Json.parse(" {} "))
        assertEquals(emptyList<Any?>(), Json.parse("[ ]"))
    }

    @Test
    fun refusesWhatIsNotJson() {
        for (bad in listOf("", "{", "{\"a\"}", "{\"a\":1,}", "[1 2]", "01", "1.", "-", "tru", "\"x", "\"\\q\"", "{} x", "\"\u0001\"", "{a:1}")) {
            assertThrows(bad, Json.ParseException::class.java) { Json.parse(bad) }
        }
    }

    @Test
    fun refusesDeepNesting() {
        Json.parse("[".repeat(16) + "]".repeat(16))
        assertThrows(Json.ParseException::class.java) { Json.parse("[".repeat(18) + "]".repeat(18)) }
    }

    @Test
    fun quotesAndNumbers() {
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\\u2028\"", Json.quote("a\"b\\c\n\u0001\u2028"))
        assertEquals("101325", Json.number(101325.0))
        assertEquals("0.25", Json.number(0.25))
        assertEquals("-3", Json.number(-3.0))
        assertEquals("null", Json.number(Double.NaN))
        assertEquals("null", Json.number(Double.POSITIVE_INFINITY))
        assertEquals("1.0E20", Json.number(1e20))
    }
}
