package app.howlingdog

import java.io.File
import java.util.Locale

/**
 * 2.0, feature 6 — the Arcade Manager's stats: input heatmaps
 * and the virtual revenue / time tracker. Pure Kotlin (no
 * Android), unit-tested on the JVM (ArcadeStatsTest).
 *
 * The counts are presses, not time held: each time a direction
 * is actuated (the stick crosses into a new direction zone) or
 * a button goes from up to down, that control's counter goes up
 * by one. That is the "wear" — which controls get used most.
 * The counts are recorded at the shared pushMask() point (touch
 * + gamepad), from the mask change between pushes.
 *
 * A virtual coin drop is one Coin press, worth $0.25 — the
 * revenue is what the player would have spent in real money
 * feeding a cabinet. The playtime beside it comes from the
 * library's totalPlayMillis (feature 1), not from here.
 *
 * NO DIP switches anywhere: neither automatic difficulty
 * adjustment nor a manual DIP panel. The Arcade Manager counts
 * and shows; it never changes how a game plays.
 */
data class ArcadeCounts(
    val up: Long = 0,
    val down: Long = 0,
    val left: Long = 0,
    val right: Long = 0,
    val upLeft: Long = 0,
    val upRight: Long = 0,
    val downLeft: Long = 0,
    val downRight: Long = 0,
    val a: Long = 0,
    val b: Long = 0,
    val c: Long = 0,
    val d: Long = 0,
    val coin: Long = 0,
    val start: Long = 0,
) {
    /** Every press counted, directions + buttons. */
    val totalPresses: Long get() =
        up + down + left + right + upLeft + upRight + downLeft + downRight +
            a + b + c + d + coin + start

    /** Presses on the stick / D-pad directions. */
    val totalDirectionPresses: Long get() =
        up + down + left + right + upLeft + upRight + downLeft + downRight

    /** Presses on the buttons. */
    val totalButtonPresses: Long get() = a + b + c + d + coin + start

    /** Add two counts (merging a session's counts into the stored ones). */
    operator fun plus(other: ArcadeCounts): ArcadeCounts = ArcadeCounts(
        up = up + other.up,
        down = down + other.down,
        left = left + other.left,
        right = right + other.right,
        upLeft = upLeft + other.upLeft,
        upRight = upRight + other.upRight,
        downLeft = downLeft + other.downLeft,
        downRight = downRight + other.downRight,
        a = a + other.a,
        b = b + other.b,
        c = c + other.c,
        d = d + other.d,
        coin = coin + other.coin,
        start = start + other.start,
    )

    /** True when nothing has been counted yet. */
    fun isEmpty(): Boolean = totalPresses == 0L

    /** The count for one direction bucket ("up", "upLeft", ...), or 0 for an unknown name. */
    fun directionCount(name: String): Long = when (name) {
        "up" -> up
        "down" -> down
        "left" -> left
        "right" -> right
        "upLeft" -> upLeft
        "upRight" -> upRight
        "downLeft" -> downLeft
        "downRight" -> downRight
        else -> 0L
    }

    /** The count for one button ("A", "B", "C", "D", "Coin", "Start"), or 0 for an unknown id. */
    fun buttonCount(id: String): Long = when (id) {
        "A" -> a
        "B" -> b
        "C" -> c
        "D" -> d
        "Coin" -> coin
        "Start" -> start
        else -> 0L
    }
}

/**
 * One row in the Arcade Manager: a game, its display name, its
 * playtime, and its counts. The display name is resolved by
 * [ArcadeStats.buildArcadeEntries]: the library entry's
 * [GameEntry.effectiveName], or the file stem for a game no
 * longer in the library.
 */
data class ArcadeManagerEntry(
    val mainFile: String,
    val displayName: String,
    val playtimeMillis: Long,
    val counts: ArcadeCounts,
)

object ArcadeStats {

    /** What one virtual coin drop is worth, in cents. */
    const val COIN_VALUE_CENTS = 25L

    /**
     * 2.0.1, audit fix 3 — build the Arcade Manager's rows from
     * every game with stats or playtime: library games by their
     * display name, and games no longer in the library by their
     * file stem (the same fallback the memory card manager uses
     * for unknown games). A game with neither presses nor
     * playtime gets no row. Rows are most-pressed first.
     */
    fun buildArcadeEntries(
        games: List<GameEntry>,
        stored: Map<String, ArcadeCounts>,
    ): List<ArcadeManagerEntry> {
        val known = games.associateBy { it.mainFile }
        val mainFiles = (games.map { it.mainFile } + stored.keys).distinct()
        return mainFiles
            .map { mainFile ->
                val game = known[mainFile]
                ArcadeManagerEntry(
                    mainFile = mainFile,
                    displayName = game?.effectiveName ?: displayNameFor(mainFile),
                    playtimeMillis = game?.totalPlayMillis ?: 0L,
                    counts = stored[mainFile] ?: ArcadeCounts(),
                )
            }
            .filter { it.counts.totalPresses > 0 || it.playtimeMillis > 0 }
            .sortedByDescending { it.counts.totalPresses }
    }

    /** The eight direction buckets, in the order the heatmap draws them. */
    val DIRECTION_NAMES: List<String> = listOf(
        "upLeft", "up", "upRight",
        "left", "right",
        "downLeft", "down", "downRight",
    )

    /** The six button ids, in the order the heatmap draws them. */
    val BUTTON_IDS: List<String> = listOf("A", "B", "C", "D", "Coin", "Start")

    /**
     * The direction bucket for a mask's direction set, or null
     * for neutral (no direction held). A diagonal is its own
     * bucket — holding UP+RIGHT wears the up-right zone, not
     * UP and RIGHT separately.
     */
    fun directionBucket(mask: Int): String? {
        val up = (mask and (1 shl InputMask.JOY_UP)) != 0
        val down = (mask and (1 shl InputMask.JOY_DOWN)) != 0
        val left = (mask and (1 shl InputMask.JOY_LEFT)) != 0
        val right = (mask and (1 shl InputMask.JOY_RIGHT)) != 0
        return when {
            up && left -> "upLeft"
            up && right -> "upRight"
            down && left -> "downLeft"
            down && right -> "downRight"
            up -> "up"
            down -> "down"
            left -> "left"
            right -> "right"
            else -> null
        }
    }

    /**
     * Record one mask change: the direction bucket for the new
     * direction set goes up by one when the stick actuated into
     * it (the same threshold rule as the haptic detector), and
     * each button that went from up to down goes up by one.
     * Holding a control does not count again, and a release
     * counts nothing.
     */
    fun recordTransition(previousMask: Int, newMask: Int, counts: ArcadeCounts): ArcadeCounts {
        var result = counts
        if (ActuationDetector.isActuation(previousMask, newMask)) {
            result = when (directionBucket(newMask)) {
                "up" -> result.copy(up = result.up + 1)
                "down" -> result.copy(down = result.down + 1)
                "left" -> result.copy(left = result.left + 1)
                "right" -> result.copy(right = result.right + 1)
                "upLeft" -> result.copy(upLeft = result.upLeft + 1)
                "upRight" -> result.copy(upRight = result.upRight + 1)
                "downLeft" -> result.copy(downLeft = result.downLeft + 1)
                "downRight" -> result.copy(downRight = result.downRight + 1)
                else -> result
            }
        }
        if (pressed(previousMask, newMask, InputMask.neoA())) result = result.copy(a = result.a + 1)
        if (pressed(previousMask, newMask, InputMask.neoB())) result = result.copy(b = result.b + 1)
        if (pressed(previousMask, newMask, InputMask.neoC())) result = result.copy(c = result.c + 1)
        if (pressed(previousMask, newMask, InputMask.neoD())) result = result.copy(d = result.d + 1)
        if (pressed(previousMask, newMask, 1 shl InputMask.JOY_SELECT)) result = result.copy(coin = result.coin + 1)
        if (pressed(previousMask, newMask, 1 shl InputMask.JOY_START)) result = result.copy(start = result.start + 1)
        return result
    }

    /** True when this bit went from up to down between the two masks. */
    private fun pressed(previousMask: Int, newMask: Int, bit: Int): Boolean =
        (previousMask and bit) == 0 && (newMask and bit) != 0

    /** What a number of virtual coin drops would have cost, in cents. */
    fun revenueCents(coinCount: Long): Long = coinCount * COIN_VALUE_CENTS

    /** A cent amount as dollars ("$12.50", "$0.25", "$0.00"). */
    fun formatMoney(cents: Long): String =
        String.format(Locale.US, "$%d.%02d", cents / 100, cents % 100)

    /** What a number of virtual coin drops would have cost, as dollars ("$12.50"). */
    fun formatRevenue(coinCount: Long): String = formatMoney(revenueCents(coinCount))
}

/**
 * The file-backed store for the Arcade Manager's per-game
 * counts. One line per game in `arcade-stats.txt`:
 * `mainFile|up|down|left|right|upLeft|upRight|downLeft|downRight|a|b|c|d|coin|start`.
 * A line with a bad field loads with that field as 0, so a
 * hand-edited or older file never breaks the load. Pure JVM
 * (a plain text file), unit-tested with a temp file.
 */
class ArcadeStatsStore(private val file: File) {

    /** Every game's stored counts, by main file name. */
    fun loadAll(): Map<String, ArcadeCounts> {
        val lines = try {
            if (!file.isFile) return emptyMap()
            file.readLines()
        } catch (_: Exception) {
            return emptyMap()
        }
        val result = LinkedHashMap<String, ArcadeCounts>()
        for (line in lines) {
            if (line.isBlank()) continue
            val parts = line.split("|")
            if (parts.size != 15) continue
            val mainFile = parts[0].takeIf { it.isNotBlank() } ?: continue
            fun num(index: Int): Long = parts[index].toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            result[mainFile] = ArcadeCounts(
                up = num(1),
                down = num(2),
                left = num(3),
                right = num(4),
                upLeft = num(5),
                upRight = num(6),
                downLeft = num(7),
                downRight = num(8),
                a = num(9),
                b = num(10),
                c = num(11),
                d = num(12),
                coin = num(13),
                start = num(14),
            )
        }
        return result
    }

    /** One game's stored counts, or zeros when it has none. */
    fun load(mainFile: String): ArcadeCounts = loadAll()[mainFile] ?: ArcadeCounts()

    /**
     * Store one game's counts (replacing its line, or appending
     * it). The whole file is rewritten, so a crash mid-write
     * cannot leave a half-updated line.
     */
    fun save(mainFile: String, counts: ArcadeCounts) {
        val all = loadAll().toMutableMap()
        all[mainFile] = counts
        saveAll(all)
    }

    /** Rewrite the whole file from a map of counts. */
    fun saveAll(all: Map<String, ArcadeCounts>) {
        try {
            file.parentFile?.mkdirs()
            val text = all.entries.joinToString("\n") { (mainFile, counts) ->
                listOf(
                    mainFile,
                    counts.up, counts.down, counts.left, counts.right,
                    counts.upLeft, counts.upRight, counts.downLeft, counts.downRight,
                    counts.a, counts.b, counts.c, counts.d, counts.coin, counts.start,
                ).joinToString("|")
            }
            file.writeText(if (text.isEmpty()) "" else text + "\n")
        } catch (_: Exception) {
            // A failed stats save must never fail the game; the
            // session counts stay in memory and the next persist
            // retries.
        }
    }
}
