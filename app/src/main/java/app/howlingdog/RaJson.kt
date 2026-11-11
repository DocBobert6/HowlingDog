package app.howlingdog

/**
 * A minimal JSON parser for the native layer's shape: an array
 * (or a single object) of flat objects whose values are
 * strings, numbers, true/false, null, or nested arrays /
 * objects. Shared by [RaAchievements] and [RaLeaderboards]
 * (v1.2.0, item 2) — the parser used to be private to
 * RaAchievements, and the leaderboard payloads are the same
 * shape. Throws on malformed input; the callers turn that
 * into an empty list (a bad payload must never crash a list
 * screen).
 */
internal object RaJson {
    /** Parse a JSON array; throws on malformed input. */
    fun parseArray(json: String): List<Any?> = JsonParser(json).parseArray()

    /** Parse a JSON object; throws on malformed input. */
    fun parseObject(json: String): Map<String, Any?> = JsonParser(json).parseObject()
}


private class JsonParser(private val text: String) {
    private var pos = 0

    fun parseArray(): List<Any?> {
        skipWs()
        if (!consume('[')) throw IllegalArgumentException("expected '['")
        val out = ArrayList<Any?>()
        skipWs()
        if (consume(']')) return out
        while (true) {
            skipWs()
            out.add(parseValue())
            skipWs()
            when {
                consume(',') -> continue
                consume(']') -> return out
                else -> throw IllegalArgumentException("expected ',' or ']'")
            }
        }
    }

    fun parseObject(): Map<String, Any?> {
        if (!consume('{')) throw IllegalArgumentException("expected '{'")
        val out = LinkedHashMap<String, Any?>()
        skipWs()
        if (consume('}')) return out
        while (true) {
            skipWs()
            val key = parseString()
            skipWs()
            if (!consume(':')) throw IllegalArgumentException("expected ':'")
            skipWs()
            out[key] = parseValue()
            skipWs()
            when {
                consume(',') -> continue
                consume('}') -> return out
                else -> throw IllegalArgumentException("expected ',' or '}'")
            }
        }
    }

    private fun parseValue(): Any? {
        skipWs()
        if (pos >= text.length) throw IllegalArgumentException("unexpected end")
        return when (val c = text[pos]) {
            '"' -> parseString()
            '{' -> parseObject()
            '[' -> parseArray()
            't' -> { expect("true"); true }
            'f' -> { expect("false"); false }
            'n' -> { expect("null"); null }
            else -> parseNumber()
        }
    }

    private fun parseString(): String {
        if (!consume('"')) throw IllegalArgumentException("expected '\"'")
        val sb = StringBuilder()
        while (pos < text.length) {
            val c = text[pos++]
            when (c) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= text.length) break
                    when (val esc = text[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 > text.length) throw IllegalArgumentException("bad \\u escape")
                            val code = text.substring(pos, pos + 4).toIntOrNull(16)
                                ?: throw IllegalArgumentException("bad \\u escape")
                            sb.append(code.toChar())
                            pos += 4
                        }
                        else -> throw IllegalArgumentException("bad escape")
                    }
                }
                else -> sb.append(c)
            }
        }
        throw IllegalArgumentException("unterminated string")
    }

    private fun parseNumber(): Number {
        val start = pos
        if (pos < text.length && text[pos] == '-') pos++
        while (pos < text.length && (text[pos].isDigit() || text[pos] == '.' || text[pos] == 'e' || text[pos] == 'E' || text[pos] == '+' || text[pos] == '-')) pos++
        val raw = text.substring(start, pos)
        if (raw.isEmpty()) throw IllegalArgumentException("expected a number")
        return raw.toLongOrNull() ?: raw.toDoubleOrNull()
            ?: throw IllegalArgumentException("bad number: $raw")
    }

    private fun expect(word: String) {
        if (!text.startsWith(word, pos)) throw IllegalArgumentException("expected $word")
        pos += word.length
    }

    private fun skipWs() {
        while (pos < text.length && text[pos].isWhitespace()) pos++
    }

    private fun consume(c: Char): Boolean {
        if (pos < text.length && text[pos] == c) {
            pos++
            return true
        }
        return false
    }
}
