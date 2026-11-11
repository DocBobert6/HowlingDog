package app.howlingdog

/**
 * RetroAchievements leaderboards and rich presence (v1.2.0,
 * item 2): the pure data types and JSON parsing for the
 * leaderboard payloads the native layer builds
 * (bridge/jni/ra_client.c). The native layer is the one that
 * talks to rcheevos; this file only turns its JSON into typed
 * values the UI can show, so it is testable without a live
 * account.
 *
 * Submission is automatic — rc_client evaluates the trackers
 * in rc_client_do_frame and submits an attempt through the
 * same async HTTP plumbing as achievements — so the app never
 * submits a score by hand. Rich presence is likewise
 * automatic: rc_client's periodic ping sends the current
 * presence string to the server, so the app only reads the
 * message for display.
 */

data class RaLeaderboard(
    val id: Int,
    val title: String,
    val description: String,
    /** 0 time, 1 score, 2 value (see [RaLeaderboardFormats]). */
    val format: Int,
    val lowerIsBetter: Boolean,
    val state: Int,
    /** The current attempt's value ("" when not attempting). */
    val trackerValue: String,
)

data class RaLeaderboardEntry(
    val user: String,
    /** The score, already formatted by rcheevos (a time, a points total, or a value). */
    val display: String,
    val rank: Int,
    val index: Int,
    /** When the score was submitted (seconds since the epoch). */
    val submitted: Long,
)

data class RaLeaderboardEntryList(
    val entries: List<RaLeaderboardEntry>,
    val totalEntries: Int,
    /** The logged-in user's rank in the full list, or -1 when they have no entry. */
    val userIndex: Int,
)

data class RaScoreboardTopEntry(
    val user: String,
    val display: String,
    val rank: Int,
)

/** The scoreboard the server sends back after an attempt is submitted. */
data class RaScoreboard(
    val submittedScore: String,
    val bestScore: String,
    val newRank: Int,
    val numEntries: Int,
    val top: List<RaScoreboardTopEntry>,
)

/** Leaderboard event types (rcheevos RC_CLIENT_EVENT_*, mirrored in ra_client.h). */
object RaLeaderboardEvents {
    const val STARTED = 2
    const val FAILED = 3
    const val SUBMITTED = 4
    const val TRACKER_SHOW = 10
    const val TRACKER_HIDE = 11
    const val TRACKER_UPDATE = 12
    const val SCOREBOARD = 13
}

/** Leaderboard formats (rc_client_leaderboard_format). */
object RaLeaderboardFormats {
    const val TIME = 0
    const val SCORE = 1
    const val VALUE = 2
}

object RaLeaderboards {
    /**
     * Parse the leaderboards JSON from the native layer: an
     * array of objects with id, title, description, format,
     * lower_is_better (an int, 0/1), state, and tracker_value.
     * Malformed JSON (or a non-array) parses to an empty list —
     * a bad payload must never crash the list screen. A single
     * malformed entry is skipped, not fatal.
     */
    fun parseLeaderboards(json: String): List<RaLeaderboard> {
        val values = try {
            RaJson.parseArray(json)
        } catch (_: Exception) {
            return emptyList()
        }
        val out = ArrayList<RaLeaderboard>(values.size)
        for (value in values) {
            val obj = value as? Map<String, Any?> ?: continue
            try {
                out.add(parseOneBoard(obj))
            } catch (_: Exception) {
                continue
            }
        }
        return out
    }

    private fun parseOneBoard(obj: Map<String, Any?>): RaLeaderboard {
        val lowerRaw = obj["lower_is_better"]
        return RaLeaderboard(
            id = (obj["id"] as? Number)?.toInt() ?: 0,
            title = obj["title"] as? String ?: "",
            description = obj["description"] as? String ?: "",
            format = (obj["format"] as? Number)?.toInt() ?: RaLeaderboardFormats.SCORE,
            lowerIsBetter = when (lowerRaw) {
                is Number -> lowerRaw.toInt() != 0
                is Boolean -> lowerRaw
                else -> false
            },
            state = (obj["state"] as? Number)?.toInt() ?: 0,
            trackerValue = obj["tracker_value"] as? String ?: "",
        )
    }

    /**
     * Parse the entries JSON from the native layer: an object
     * with entries (an array of user / display / rank / index /
     * submitted), total_entries, and user_index. Returns null
     * on malformed input — the caller shows an error, not a
     * crash.
     */
    fun parseEntries(json: String): RaLeaderboardEntryList? {
        val obj = try {
            RaJson.parseObject(json)
        } catch (_: Exception) {
            return null
        }
        val rawEntries = obj["entries"] as? List<*> ?: return null
        val entries = ArrayList<RaLeaderboardEntry>(rawEntries.size)
        for (value in rawEntries) {
            val entry = value as? Map<String, Any?> ?: continue
            try {
                entries.add(
                    RaLeaderboardEntry(
                        user = entry["user"] as? String ?: "",
                        display = entry["display"] as? String ?: "",
                        rank = (entry["rank"] as? Number)?.toInt() ?: 0,
                        index = (entry["index"] as? Number)?.toInt() ?: 0,
                        submitted = (entry["submitted"] as? Number)?.toLong() ?: 0L,
                    ),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return RaLeaderboardEntryList(
            entries = entries,
            totalEntries = (obj["total_entries"] as? Number)?.toInt() ?: entries.size,
            userIndex = (obj["user_index"] as? Number)?.toInt() ?: -1,
        )
    }

    /**
     * Parse the scoreboard JSON the native layer builds from a
     * LEADERBOARD_SCOREBOARD event: submitted_score,
     * best_score, new_rank, num_entries, and top (an array of
     * user / display / rank). Returns null on malformed input.
     */
    fun parseScoreboard(json: String): RaScoreboard? {
        val obj = try {
            RaJson.parseObject(json)
        } catch (_: Exception) {
            return null
        }
        val rawTop = obj["top"] as? List<*> ?: emptyList<Any?>()
        val top = ArrayList<RaScoreboardTopEntry>(rawTop.size)
        for (value in rawTop) {
            val entry = value as? Map<String, Any?> ?: continue
            try {
                top.add(
                    RaScoreboardTopEntry(
                        user = entry["user"] as? String ?: "",
                        display = entry["display"] as? String ?: "",
                        rank = (entry["rank"] as? Number)?.toInt() ?: 0,
                    ),
                )
            } catch (_: Exception) {
                continue
            }
        }
        return RaScoreboard(
            submittedScore = obj["submitted_score"] as? String ?: "",
            bestScore = obj["best_score"] as? String ?: "",
            newRank = (obj["new_rank"] as? Number)?.toInt() ?: 0,
            numEntries = (obj["num_entries"] as? Number)?.toInt() ?: top.size,
            top = top,
        )
    }

    /**
     * Parse the tracker JSON the native layer builds from a
     * LEADERBOARD_TRACKER_SHOW / UPDATE event: {"display": ...}.
     * Returns the display string, or null when there is none
     * (a hide event carries no payload at all).
     */
    fun parseTrackerDisplay(json: String?): String? {
        if (json == null) return null
        val obj = try {
            RaJson.parseObject(json)
        } catch (_: Exception) {
            return null
        }
        return (obj["display"] as? String)?.takeIf { it.isNotEmpty() }
    }

    /** "Time" / "Score" / "Value" — the label for a board's format. */
    fun formatName(format: Int): String = when (format) {
        RaLeaderboardFormats.TIME -> "Time"
        RaLeaderboardFormats.VALUE -> "Value"
        else -> "Score"
    }
}
