package app.howlingdog

/**
 * Phase 10 — RetroAchievements: the achievement model.
 *
 * Pure Kotlin, so it is covered by the pure-test harness. The JSON
 * comes from the native layer (nativeRaGetAchievements), which
 * builds it from the rc_client list; each entry carries the id,
 * title, description, points, state, and — for measured
 * achievements — the progress string and percent. Badge images
 * are out of scope for v1.1.0 (no image loader in the project),
 * so no badge URL is carried.
 *
 * States mirror rc_client_achievement_state: INACTIVE (0) means
 * the achievement can never be earned in this game, ACTIVE (1)
 * means it can, UNLOCKED (2) means the player earned it, and
 * DISABLED (3) means the game is not fully loaded or the
 * achievement is unsupported. The list screen shows unlocked and
 * active achievements; inactive ones are hidden.
 *
 * The JSON is parsed by the minimal parser below, not org.json:
 * org.json is a stub in android.jar (it throws "Stub!" on the
 * plain JVM), which is why UpdateChecker — the only other
 * org.json user — is not in the pure-test harness. The parser
 * only needs the native layer's shape: an array of flat objects
 * whose values are strings, numbers, or (for robustness)
 * true/false/null.
 */
data class RaAchievement(
    val id: Int,
    val title: String,
    val description: String,
    val points: Int,
    /** One of [RaAchievements.STATE_INACTIVE], [RaAchievements.STATE_ACTIVE], [RaAchievements.STATE_UNLOCKED], [RaAchievements.STATE_DISABLED]. */
    val state: Int,
    val unlocked: Boolean,
    val measuredProgress: String? = null,
    val measuredPercent: Double? = null,
) {
    val isUnlocked: Boolean get() = unlocked || state == RaAchievements.STATE_UNLOCKED
}

object RaAchievements {
    const val STATE_INACTIVE = 0
    const val STATE_ACTIVE = 1
    const val STATE_UNLOCKED = 2
    const val STATE_DISABLED = 3

    /**
     * Parse the achievements JSON from the native layer: an array
     * of objects with id, title, description, points, state,
     * unlocked (an int, 0/1), and optionally measured_progress /
     * measured_percent. Malformed JSON (or a non-array) parses to
     * an empty list — a bad payload must never crash the list
     * screen. A single malformed entry is skipped, not fatal.
     */
    fun parse(json: String): List<RaAchievement> {
        val values = try {
            JsonParser(json).parseArray()
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<RaAchievement>(values.size)
        for (value in values) {
            // A non-object entry (a stray string, a number) is
            // skipped, not fatal.
            val obj = value as? Map<String, Any?> ?: continue
            try {
                out.add(parseOne(obj))
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    private fun parseOne(obj: Map<String, Any?>): RaAchievement {
        // The native layer emits unlocked as an int (0/1) and
        // measured_progress as "" when there is none.
        val measuredProgress = (obj["measured_progress"] as? String)
            ?.takeIf { it.isNotEmpty() }
        val measuredPercent = (obj["measured_percent"] as? Number)?.toDouble()
        val unlockedRaw = obj["unlocked"]
        return RaAchievement(
            id = (obj["id"] as? Number)?.toInt() ?: 0,
            title = obj["title"] as? String ?: "",
            description = obj["description"] as? String ?: "",
            points = (obj["points"] as? Number)?.toInt() ?: 0,
            state = (obj["state"] as? Number)?.toInt() ?: STATE_DISABLED,
            unlocked = when (unlockedRaw) {
                is Number -> unlockedRaw.toInt() != 0
                is Boolean -> unlockedRaw
                else -> false
            },
            measuredProgress = measuredProgress,
            measuredPercent = measuredPercent,
        )
    }

    /** "3 / 12 unlocked" — the header line of the achievements screen. */
    fun summary(achievements: List<RaAchievement>): String {
        val unlocked = achievements.count { it.isUnlocked }
        return "$unlocked / ${achievements.size} unlocked"
    }

    /**
     * A minimal JSON parser for the native layer's shape: an
     * array of flat objects whose values are strings, numbers,
     * true/false, or null. Throws on malformed input (the caller
     * turns that into an empty list).
     */
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

        private fun parseObject(): Map<String, Any?> {
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
}
