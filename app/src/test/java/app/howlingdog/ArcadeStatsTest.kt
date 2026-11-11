package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Pure-JVM unit tests for the Arcade Manager's stats
 * (2.0, feature 6): press counting (directions + buttons),
 * the revenue math, and the file-backed per-game store. No
 * Android framework calls.
 */
class ArcadeStatsTest {

    private fun mask(
        up: Boolean = false,
        down: Boolean = false,
        left: Boolean = false,
        right: Boolean = false,
        a: Boolean = false,
        b: Boolean = false,
        c: Boolean = false,
        d: Boolean = false,
        start: Boolean = false,
        coin: Boolean = false,
    ): Int = InputMask.build(up, down, left, right, a, b, c, d, start, coin)

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile()

    @Test
    fun emptyCountsAreZero() {
        val counts = ArcadeCounts()
        assertTrue(counts.isEmpty())
        assertEquals(0L, counts.totalPresses)
        assertEquals(0L, counts.coin)
        assertEquals("$0.00", ArcadeStats.formatRevenue(counts.coin))
    }

    @Test
    fun directionPressesCountTheNewZoneOnly() {
        var counts = ArcadeCounts()
        counts = ArcadeStats.recordTransition(0, mask(up = true), counts)
        assertEquals(1L, counts.up)
        assertEquals(0L, counts.upRight)
        // Moving into the diagonal wears the diagonal zone.
        counts = ArcadeStats.recordTransition(mask(up = true), mask(up = true, right = true), counts)
        assertEquals(1L, counts.up)
        assertEquals(1L, counts.upRight)
        // Holding the diagonal does not count again.
        counts = ArcadeStats.recordTransition(mask(up = true, right = true), mask(up = true, right = true), counts)
        assertEquals(1L, counts.upRight)
        // Releasing counts nothing.
        counts = ArcadeStats.recordTransition(mask(up = true, right = true), 0, counts)
        assertEquals(1L, counts.up)
        assertEquals(1L, counts.upRight)
        assertEquals(2L, counts.totalDirectionPresses)
    }

    @Test
    fun buttonPressesCountOnPressOnly() {
        var counts = ArcadeCounts()
        counts = ArcadeStats.recordTransition(0, mask(a = true), counts)
        assertEquals(1L, counts.a)
        // Holding A does not count again.
        counts = ArcadeStats.recordTransition(mask(a = true), mask(a = true), counts)
        assertEquals(1L, counts.a)
        // Pressing B while holding A counts B only.
        counts = ArcadeStats.recordTransition(mask(a = true), mask(a = true, b = true), counts)
        assertEquals(1L, counts.a)
        assertEquals(1L, counts.b)
        // Releasing counts nothing; pressing again counts again.
        counts = ArcadeStats.recordTransition(mask(a = true, b = true), 0, counts)
        counts = ArcadeStats.recordTransition(0, mask(a = true), counts)
        assertEquals(2L, counts.a)
        assertEquals(1L, counts.b)
    }

    @Test
    fun coinPressesCountAndPriceTheRevenue() {
        var counts = ArcadeCounts()
        counts = ArcadeStats.recordTransition(0, mask(coin = true), counts)
        counts = ArcadeStats.recordTransition(mask(coin = true), 0, counts)
        counts = ArcadeStats.recordTransition(0, mask(coin = true), counts)
        counts = ArcadeStats.recordTransition(mask(coin = true), 0, counts)
        assertEquals(2L, counts.coin)
        assertEquals(50L, ArcadeStats.revenueCents(counts.coin))
        assertEquals("$0.50", ArcadeStats.formatRevenue(counts.coin))
        assertEquals("$12.50", ArcadeStats.formatRevenue(50))
        assertEquals("$0.25", ArcadeStats.formatRevenue(1))
        assertEquals("$0.00", ArcadeStats.formatRevenue(0))
        assertEquals("$100.00", ArcadeStats.formatRevenue(400))
    }

    @Test
    fun directionBucketNamesEveryZone() {
        assertEquals("up", ArcadeStats.directionBucket(mask(up = true)))
        assertEquals("down", ArcadeStats.directionBucket(mask(down = true)))
        assertEquals("left", ArcadeStats.directionBucket(mask(left = true)))
        assertEquals("right", ArcadeStats.directionBucket(mask(right = true)))
        assertEquals("upLeft", ArcadeStats.directionBucket(mask(up = true, left = true)))
        assertEquals("upRight", ArcadeStats.directionBucket(mask(up = true, right = true)))
        assertEquals("downLeft", ArcadeStats.directionBucket(mask(down = true, left = true)))
        assertEquals("downRight", ArcadeStats.directionBucket(mask(down = true, right = true)))
        assertNull(ArcadeStats.directionBucket(0))
        assertNull(ArcadeStats.directionBucket(mask(a = true)))
    }

    @Test
    fun plusMergesSessionCountsIntoStoredCounts() {
        val stored = ArcadeCounts(up = 10, a = 5, coin = 3)
        val session = ArcadeCounts(up = 2, b = 1, coin = 1)
        val merged = stored + session
        assertEquals(12L, merged.up)
        assertEquals(5L, merged.a)
        assertEquals(1L, merged.b)
        assertEquals(4L, merged.coin)
        assertEquals(12L + 5L + 1L + 4L, merged.totalPresses)
    }

    @Test
    fun storeRoundTripsPerGame() {
        val dir = tempDir("arcade-store")
        val store = ArcadeStatsStore(File(dir, "arcade-stats.txt"))
        assertTrue(store.loadAll().isEmpty())
        assertEquals(ArcadeCounts(), store.load("kof98.neo"))
        val counts = ArcadeCounts(up = 12, upRight = 3, a = 40, coin = 7)
        store.save("kof98.neo", counts)
        assertEquals(counts, store.load("kof98.neo"))
        assertEquals(1, store.loadAll().size)
        // A second game does not disturb the first.
        store.save("mslug3.neo", ArcadeCounts(b = 5))
        assertEquals(counts, store.load("kof98.neo"))
        assertEquals(5L, store.load("mslug3.neo").b)
        // Saving again replaces the line.
        store.save("kof98.neo", counts + ArcadeCounts(coin = 1))
        assertEquals(8L, store.load("kof98.neo").coin)
        assertEquals(12L, store.load("kof98.neo").up)
    }

    @Test
    fun storeIgnoresBadLinesAndBadFields() {
        val dir = tempDir("arcade-store-bad")
        val file = File(dir, "arcade-stats.txt")
        file.writeText(
            "kof98.neo|10|xx|5|0|0|0|0|0|40|0|0|0|7|0\n" +
                "too|few|fields\n" +
                "\n" +
                "mslug3.neo|0|0|0|0|0|0|0|0|0|5|0|0|0|0\n",
        )
        val store = ArcadeStatsStore(file)
        val all = store.loadAll()
        assertEquals(2, all.size)
        assertEquals(10L, all.getValue("kof98.neo").up)
        assertEquals("a bad field loads as 0", 0L, all.getValue("kof98.neo").down)
        assertEquals(5L, all.getValue("kof98.neo").left)
        assertEquals(5L, all.getValue("mslug3.neo").b)
    }

    @Test
    fun directionAndButtonCountsByName() {
        val counts = ArcadeCounts(up = 3, downRight = 4, a = 5, coin = 6)
        assertEquals(3L, counts.directionCount("up"))
        assertEquals(4L, counts.directionCount("downRight"))
        assertEquals(0L, counts.directionCount("sideways"))
        assertEquals(5L, counts.buttonCount("A"))
        assertEquals(6L, counts.buttonCount("Coin"))
        assertEquals(0L, counts.buttonCount("Select"))
        assertFalse(counts.isEmpty())
    }

    @Test
    fun entriesUseTheLibraryDisplayName() {
        val games = listOf(
            GameEntry(
                mainFile = "blazstar.neo",
                displayName = "blazstar",
                files = listOf("blazstar.neo"),
                addedAt = 0L,
                customName = "Blazing Star",
            ),
            GameEntry(
                mainFile = "kof98.neo",
                displayName = "kof98",
                files = listOf("kof98.neo"),
                addedAt = 0L,
            ),
        )
        val stored = mapOf(
            "blazstar.neo" to ArcadeCounts(a = 5),
            "kof98.neo" to ArcadeCounts(a = 3),
        )
        val entries = ArcadeStats.buildArcadeEntries(games, stored)
        assertEquals(2, entries.size)
        // The renamed game shows its custom name, the other its
        // filename-derived display name — never the file stem
        // when the game is in the library.
        assertEquals("Blazing Star", entries[0].displayName)
        assertEquals("kof98", entries[1].displayName)
        assertEquals(5L, entries[0].counts.totalPresses)
    }

    @Test
    fun entriesIncludeGamesThatLeftTheLibraryByFileStem() {
        val games = listOf(
            GameEntry(
                mainFile = "kof98.neo",
                displayName = "kof98",
                files = listOf("kof98.neo"),
                addedAt = 0L,
            ),
        )
        val stored = mapOf(
            "kof98.neo" to ArcadeCounts(a = 3),
            "blazstar.neo" to ArcadeCounts(a = 9),
        )
        val entries = ArcadeStats.buildArcadeEntries(games, stored)
        assertEquals(2, entries.size)
        // The game that left the library still appears, by its
        // file stem — the same fallback the memory card manager
        // uses for unknown games.
        assertEquals("blazstar", entries[0].displayName)
        assertEquals(9L, entries[0].counts.totalPresses)
        assertEquals("kof98", entries[1].displayName)
    }

    @Test
    fun entriesLeaveOutGamesWithNoPressesOrPlaytime() {
        val games = listOf(
            GameEntry(
                mainFile = "unplayed.neo",
                displayName = "unplayed",
                files = listOf("unplayed.neo"),
                addedAt = 0L,
            ),
            GameEntry(
                mainFile = "played.neo",
                displayName = "played",
                files = listOf("played.neo"),
                addedAt = 0L,
                totalPlayMillis = 60_000L,
            ),
        )
        val entries = ArcadeStats.buildArcadeEntries(games, emptyMap())
        // Playtime alone earns a row; no presses and no playtime
        // earns none.
        assertEquals(1, entries.size)
        assertEquals("played", entries[0].displayName)
        assertEquals(60_000L, entries[0].playtimeMillis)
    }
}
