package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure tests for [RaLeaderboards] — the JSON shapes the native layer builds. */
class RaLeaderboardsTest {

    @Test
    fun `parseLeaderboards reads both boards`() {
        val json = """[{"id":123,"title":"Fastest Clear","description":"Clear the game fast","format":0,"lower_is_better":1,"state":1,"tracker_value":""},
            {"id":124,"title":"High Score","description":"Most points","format":1,"lower_is_better":0,"state":0,"tracker_value":"12345"}]"""
        val boards = RaLeaderboards.parseLeaderboards(json)
        assertEquals(2, boards.size)
        val time = boards[0]
        assertEquals(123, time.id)
        assertEquals("Fastest Clear", time.title)
        assertEquals(RaLeaderboardFormats.TIME, time.format)
        assertTrue(time.lowerIsBetter)
        assertEquals("", time.trackerValue)
        val score = boards[1]
        assertEquals(124, score.id)
        assertEquals(RaLeaderboardFormats.SCORE, score.format)
        assertEquals(false, score.lowerIsBetter)
        assertEquals("12345", score.trackerValue)
    }

    @Test
    fun `parseLeaderboards empty and malformed are empty`() {
        assertTrue(RaLeaderboards.parseLeaderboards("[]").isEmpty())
        assertTrue(RaLeaderboards.parseLeaderboards("not json").isEmpty())
        assertTrue(RaLeaderboards.parseLeaderboards("{\"id\":1}").isEmpty())
    }

    @Test
    fun `parseLeaderboards skips a malformed entry`() {
        // A stray string entry is skipped; the good board still parses.
        val json = """["oops",{"id":7,"title":"Only Board","description":"","format":2,"lower_is_better":0,"state":0,"tracker_value":""}]"""
        val boards = RaLeaderboards.parseLeaderboards(json)
        assertEquals(1, boards.size)
        assertEquals(7, boards[0].id)
        assertEquals(RaLeaderboardFormats.VALUE, boards[0].format)
    }

    @Test
    fun `parseEntries reads ranks and the user index`() {
        val json = """{"entries":[
            {"user":"DocBobbert","display":"1,234,500","rank":3,"index":2,"submitted":1794000000},
            {"user":"Speedy","display":"2,000,000","rank":1,"index":0,"submitted":1793900000}],
            "total_entries":42,"user_index":2}"""
        val list = RaLeaderboards.parseEntries(json)
        assertEquals(2, list?.entries?.size)
        assertEquals(42, list?.totalEntries)
        assertEquals(2, list?.userIndex)
        val first = list?.entries?.get(0)
        assertEquals("DocBobbert", first?.user)
        assertEquals("1,234,500", first?.display)
        assertEquals(3, first?.rank)
        assertEquals(1794000000L, first?.submitted)
    }

    @Test
    fun `parseEntries malformed or missing entries is null`() {
        assertNull(RaLeaderboards.parseEntries("not json"))
        assertNull(RaLeaderboards.parseEntries("{\"total_entries\":0}"))
        assertNull(RaLeaderboards.parseEntries("[]"))
    }

    @Test
    fun `parseScoreboard reads the submitted score and top entries`() {
        val json = """{"submitted_score":"1,234,500","best_score":"1,500,000","new_rank":3,"num_entries":42,
            "top":[{"user":"Speedy","display":"2,000,000","rank":1},{"user":"DocBobbert","display":"1,234,500","rank":3}]}"""
        val board = RaLeaderboards.parseScoreboard(json)
        assertEquals("1,234,500", board?.submittedScore)
        assertEquals("1,500,000", board?.bestScore)
        assertEquals(3, board?.newRank)
        assertEquals(42, board?.numEntries)
        assertEquals(2, board?.top?.size)
        assertEquals("Speedy", board?.top?.get(0)?.user)
        assertEquals(1, board?.top?.get(0)?.rank)
    }

    @Test
    fun `parseScoreboard malformed is null`() {
        assertNull(RaLeaderboards.parseScoreboard("not json"))
        assertNull(RaLeaderboards.parseScoreboard("[]"))
    }

    @Test
    fun `parseTrackerDisplay reads the display string`() {
        assertEquals("TIME 01:23.45", RaLeaderboards.parseTrackerDisplay("{\"display\":\"TIME 01:23.45\"}"))
        assertNull(RaLeaderboards.parseTrackerDisplay(null))
        assertNull(RaLeaderboards.parseTrackerDisplay("{\"display\":\"\"}"))
        assertNull(RaLeaderboards.parseTrackerDisplay("not json"))
    }

    @Test
    fun `formatName names the three formats`() {
        assertEquals("Time", RaLeaderboards.formatName(RaLeaderboardFormats.TIME))
        assertEquals("Score", RaLeaderboards.formatName(RaLeaderboardFormats.SCORE))
        assertEquals("Value", RaLeaderboards.formatName(RaLeaderboardFormats.VALUE))
        assertEquals("Score", RaLeaderboards.formatName(99))
    }
}
