package fi.pocketsk.bridge

/**
 * The little JSON the bridge needs (docs/PLAN.md §7): quoting for the lines it writes, and a
 * reader for the lines the plugin sends. Written here rather than taken from `org.json` because
 * that one is a stub in unit tests (they run without the Android runtime), and the protocol is
 * tested there; the reader is bounded in depth so a hostile line cannot exhaust the stack.
 *
 * Values come back as [Map] (keys in their order), [List], [String], [Double], [Boolean] or null.
 */
internal object Json {

    /** A line that is not JSON; the message says where. */
    class ParseException(message: String) : IllegalArgumentException(message)

    private const val MAX_DEPTH = 16

    /** [s] as a JSON string literal, quotes included. */
    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ' || c == ' ' || c == ' ') append("\\u%04x".format(c.code)) else append(c)
            }
        }
        append('"')
    }

    /** [d] as a JSON number; NaN and the infinities, which JSON cannot carry, as null. */
    fun number(d: Double): String = when {
        d.isNaN() || d.isInfinite() -> "null"
        d == Math.rint(d) && Math.abs(d) < 1e15 -> d.toLong().toString()
        else -> d.toString()
    }

    /** Parses one whole JSON text. */
    fun parse(text: String): Any? {
        val r = Reader(text)
        r.space()
        val value = r.value(0)
        r.space()
        if (r.i != text.length) throw ParseException("trailing characters at ${r.i}")
        return value
    }

    private class Reader(val s: String) {
        var i = 0

        fun space() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r')) i++
        }

        fun value(depth: Int): Any? {
            if (depth > MAX_DEPTH) throw ParseException("nested deeper than $MAX_DEPTH")
            if (i >= s.length) throw ParseException("ends early")
            return when (s[i]) {
                '{' -> obj(depth)
                '[' -> array(depth)
                '"' -> string()
                't' -> word("true", true)
                'f' -> word("false", false)
                'n' -> word("null", null)
                else -> num()
            }
        }

        fun obj(depth: Int): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            space()
            if (peek() == '}') { i++; return out }
            while (true) {
                space()
                if (peek() != '"') throw ParseException("expected a key at $i")
                val key = string()
                space()
                expect(':')
                space()
                out[key] = value(depth + 1)
                space()
                when (peek()) {
                    ',' -> i++
                    '}' -> { i++; return out }
                    else -> throw ParseException("expected , or } at $i")
                }
            }
        }

        fun array(depth: Int): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            space()
            if (peek() == ']') { i++; return out }
            while (true) {
                space()
                out += value(depth + 1)
                space()
                when (peek()) {
                    ',' -> i++
                    ']' -> { i++; return out }
                    else -> throw ParseException("expected , or ] at $i")
                }
            }
        }

        fun string(): String {
            i++
            val out = StringBuilder()
            while (true) {
                if (i >= s.length) throw ParseException("unterminated string")
                val c = s[i++]
                when {
                    c == '"' -> return out.toString()
                    c == '\\' -> {
                        if (i >= s.length) throw ParseException("unterminated escape")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> out.append(e)
                            'b' -> out.append('\b')
                            'f' -> out.append('\u000c')
                            'n' -> out.append('\n')
                            'r' -> out.append('\r')
                            't' -> out.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw ParseException("short \\u escape")
                                out.append(s.substring(i, i + 4).toIntOrNull(16)?.toChar() ?: throw ParseException("bad \\u escape at $i"))
                                i += 4
                            }
                            else -> throw ParseException("bad escape \\$e")
                        }
                    }
                    c < ' ' -> throw ParseException("control character in a string at ${i - 1}")
                    else -> out.append(c)
                }
            }
        }

        fun num(): Double {
            val start = i
            if (peek() == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            val text = s.substring(start, i)
            if (text.isEmpty() || !NUMBER.matches(text)) throw ParseException("bad value at $start")
            return text.toDouble()
        }

        fun word(w: String, v: Any?): Any? {
            if (!s.startsWith(w, i)) throw ParseException("bad value at $i")
            i += w.length
            return v
        }

        fun peek(): Char = if (i < s.length) s[i] else '\u0000'

        fun expect(c: Char) {
            if (peek() != c) throw ParseException("expected $c at $i")
            i++
        }
    }

    private val NUMBER = Regex("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")
}
