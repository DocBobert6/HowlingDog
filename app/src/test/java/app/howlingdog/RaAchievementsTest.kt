package app.howlingdog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the RetroAchievements list model: the JSON
 * the native layer builds from the rc_client list (see
 * ra_client.c) parses into achievements with the right fields,
 * malformed JSON never crashes the list screen, and the header
 * summary counts unlocked achievements.
 */
class RaAchievementsTest {

    // The native layer's shape: unlocked is an int (0/1), not a
    // JSON boolean, and measured_progress is "" when there is
    // none. Badge URLs are "" when there is no badge; rarity is
    // 0.0 when the server sent none. Mirrors the mock game in the
    // ra_client host test (two achievements, 5 and 2 points).
    private val sampleJson = """[
        {"id":5501,"title":"Ach1","description":"First one","points":5,"state":1,"unlocked":0,"badge_url":"","badge_locked_url":"","rarity":0.0,"rarity_hardcore":0.0,"measured_progress":"","measured_percent":0.0},
        {"id":5502,"title":"Ach2","description":"Second one","points":2,"state":2,"unlocked":1,"badge_url":"https://media.retroachievements.org/Badge/00235.png","badge_locked_url":"https://media.retroachievements.org/Badge/00235_lock.png","rarity":12.5,"rarity_hardcore":4.0,"measured_progress":"3/5","measured_percent":0.6}
    ]"""

    @Test
    fun parseSampleJson() {
        val list = RaAchievements.parse(sampleJson)
        assertEquals(2, list.size)
        val first = list[0]
        assertEquals(5501, first.id)
        assertEquals("Ach1", first.title)
        assertEquals("First one", first.description)
        assertEquals(5, first.points)
        assertEquals(RaAchievements.STATE_ACTIVE, first.state)
        assertFalse(first.unlocked)
        assertFalse(first.isUnlocked)
        assertNull(first.measuredProgress)
        // Empty badge URLs parse to null (no badge), and a 0.0
        // rarity is kept as a real server value.
        assertNull(first.badgeUrl)
        assertNull(first.badgeLockedUrl)
        assertNull(first.badgeUrlForState)
        assertEquals(0.0, first.rarity!!, 0.001)
        assertEquals(0.0, first.rarityHardcore!!, 0.001)
        val second = list[1]
        assertEquals(5502, second.id)
        assertEquals(2, second.points)
        assertEquals(RaAchievements.STATE_UNLOCKED, second.state)
        assertTrue(second.unlocked)
        assertTrue(second.isUnlocked)
        assertEquals("3/5", second.measuredProgress)
        assertEquals(0.6, second.measuredPercent!!, 0.001)
        assertEquals("https://media.retroachievements.org/Badge/00235.png", second.badgeUrl)
        assertEquals("https://media.retroachievements.org/Badge/00235_lock.png", second.badgeLockedUrl)
        // Unlocked: the state badge is the unlocked badge.
        assertEquals("https://media.retroachievements.org/Badge/00235.png", second.badgeUrlForState)
        assertEquals(12.5, second.rarity!!, 0.001)
        assertEquals(4.0, second.rarityHardcore!!, 0.001)
    }

    @Test
    fun lockedBadgeUrlForState() {
        // Locked: the state badge is the locked (greyed) badge.
        val list = RaAchievements.parse(
            """[{"id":1,"title":"Locked","description":"","points":1,"state":1,"unlocked":0,"badge_url":"https://media.retroachievements.org/Badge/00234.png","badge_locked_url":"https://media.retroachievements.org/Badge/00234_lock.png","rarity":25.0}]""",
        )
        assertEquals(1, list.size)
        assertEquals("https://media.retroachievements.org/Badge/00234_lock.png", list[0].badgeUrlForState)
    }

    @Test
    fun missingBadgeAndRarityParseToNull() {
        // Old JSON shape (no badge/rarity fields at all) still
        // parses; the new fields are null, never a crash.
        val list = RaAchievements.parse(
            """[{"id":1,"title":"Old","description":"","points":1,"state":1,"unlocked":0}]""",
        )
        assertEquals(1, list.size)
        assertNull(list[0].badgeUrl)
        assertNull(list[0].badgeLockedUrl)
        assertNull(list[0].badgeUrlForState)
        assertNull(list[0].rarity)
        assertNull(list[0].rarityHardcore)
    }

    // --- Center sort, rarity tiers, header (Step 3) ---

    private fun ach(
        id: Int,
        points: Int,
        unlocked: Boolean = false,
        measuredProgress: String? = null,
        measuredPercent: Double? = null,
        rarity: Double? = null,
    ): RaAchievement = RaAchievement(
        id = id,
        title = "Ach$id",
        description = "",
        points = points,
        state = if (unlocked) RaAchievements.STATE_UNLOCKED else RaAchievements.STATE_ACTIVE,
        unlocked = unlocked,
        measuredProgress = measuredProgress,
        measuredPercent = measuredPercent,
        rarity = rarity,
    )

    @Test
    fun pointsSummaryAndCompletionFraction() {
        val list = listOf(ach(1, 10, unlocked = true), ach(2, 30), ach(3, 10, unlocked = true))
        assertEquals("20 / 50 pts", RaAchievements.pointsSummary(list))
        assertEquals(2f / 3f, RaAchievements.completionFraction(list), 0.001f)
        assertEquals("0 / 0 pts", RaAchievements.pointsSummary(emptyList()))
        assertEquals(0f, RaAchievements.completionFraction(emptyList()), 0.001f)
    }

    @Test
    fun rarityTierBands() {
        assertNull(RaAchievements.rarityTier(null))
        assertEquals(RaAchievements.RarityTier.VERY_RARE, RaAchievements.rarityTier(5.0))
        assertEquals(RaAchievements.RarityTier.VERY_RARE, RaAchievements.rarityTier(1.2))
        assertEquals(RaAchievements.RarityTier.RARE, RaAchievements.rarityTier(5.1))
        assertEquals(RaAchievements.RarityTier.RARE, RaAchievements.rarityTier(10.0))
        assertEquals(RaAchievements.RarityTier.COMMON, RaAchievements.rarityTier(10.1))
        assertEquals(RaAchievements.RarityTier.COMMON, RaAchievements.rarityTier(87.0))
        assertEquals("Very rare", RaAchievements.RarityTier.VERY_RARE.label)
        assertEquals("Rare", RaAchievements.RarityTier.RARE.label)
        assertEquals("Common", RaAchievements.RarityTier.COMMON.label)
    }

    @Test
    fun unlockHapticLevelByTierCappedByUserLevel() {
        val veryRare = RaAchievements.RarityTier.VERY_RARE
        val rare = RaAchievements.RarityTier.RARE
        val common = RaAchievements.RarityTier.COMMON
        // The tier's level, when the user allows it.
        assertEquals(HapticLevel.HEAVY, RaAchievements.unlockHapticLevel(veryRare, HapticLevel.HEAVY))
        assertEquals(HapticLevel.MEDIUM, RaAchievements.unlockHapticLevel(rare, HapticLevel.HEAVY))
        assertEquals(HapticLevel.LIGHT, RaAchievements.unlockHapticLevel(common, HapticLevel.HEAVY))
        // Never stronger than the user's own level.
        assertEquals(HapticLevel.LIGHT, RaAchievements.unlockHapticLevel(veryRare, HapticLevel.LIGHT))
        assertEquals(HapticLevel.LIGHT, RaAchievements.unlockHapticLevel(rare, HapticLevel.LIGHT))
        assertEquals(HapticLevel.MEDIUM, RaAchievements.unlockHapticLevel(veryRare, HapticLevel.MEDIUM))
        // OFF means silence, even for a very rare unlock.
        assertEquals(HapticLevel.OFF, RaAchievements.unlockHapticLevel(veryRare, HapticLevel.OFF))
        // An unknown rarity reads as common.
        assertEquals(HapticLevel.LIGHT, RaAchievements.unlockHapticLevel(null, HapticLevel.HEAVY))
    }

    @Test
    fun sortClosestPutsNearestLockedFirstAndUnlockedLast() {
        // Closest first: the locked measured achievement nearest
        // completion, then the other locked measured one, then the
        // locked unmeasured one, then the unlocked ones.
        val near = ach(1, 5, measuredProgress = "9/10", measuredPercent = 0.9, rarity = 20.0)
        val far = ach(2, 5, measuredProgress = "1/10", measuredPercent = 0.1, rarity = 20.0)
        val plain = ach(3, 10, rarity = 20.0)
        val done = ach(4, 50, unlocked = true, measuredProgress = "10/10", measuredPercent = 1.0)
        val input = listOf(done, plain, far, near)
        val sorted = RaAchievements.sortAchievements(input, RaAchievements.Sort.CLOSEST)
        assertEquals(listOf(1, 2, 3, 4), sorted.map { it.id })
    }

    @Test
    fun sortDefaultKeepsLoadedOrder() {
        val input = listOf(ach(3, 5), ach(1, 10), ach(2, 1))
        assertEquals(listOf(3, 1, 2), RaAchievements.sortAchievements(input, RaAchievements.Sort.DEFAULT).map { it.id })
    }

    @Test
    fun sortPointsDescending() {
        val input = listOf(ach(1, 5), ach(2, 50), ach(3, 10))
        assertEquals(listOf(2, 3, 1), RaAchievements.sortAchievements(input, RaAchievements.Sort.POINTS).map { it.id })
    }

    @Test
    fun sortRarityRarestFirstUnknownLast() {
        val input = listOf(
            ach(1, 5, rarity = 50.0),
            ach(2, 5, rarity = null),
            ach(3, 5, rarity = 2.0),
            ach(4, 5, rarity = 10.0),
        )
        assertEquals(listOf(3, 4, 1, 2), RaAchievements.sortAchievements(input, RaAchievements.Sort.RARITY).map { it.id })
    }

    @Test
    fun searchForHelpUrl() {
        assertEquals(
            "https://duckduckgo.com/?q=Metal+Slug+3+First+Blood+RetroAchievements",
            RaAchievements.searchForHelpUrl("Metal Slug 3", "First Blood"),
        )
        // No game title: the query is just the achievement plus the site.
        assertEquals(
            "https://duckduckgo.com/?q=First+Blood+RetroAchievements",
            RaAchievements.searchForHelpUrl(null, "First Blood"),
        )
    }

    @Test
    fun parseMalformedJsonIsEmpty() {
        assertTrue(RaAchievements.parse("").isEmpty())
        assertTrue(RaAchievements.parse("not json").isEmpty())
        assertTrue(RaAchievements.parse("{}").isEmpty())
        // One bad entry is skipped, the good one still parses.
        val mixed = """[{"id":1,"title":"Good","description":"","points":1,"state":1,"unlocked":0}, "oops"]"""
        val list = RaAchievements.parse(mixed)
        assertEquals(1, list.size)
        assertEquals("Good", list[0].title)
    }

    @Test
    fun summaryCountsUnlocked() {
        assertEquals("0 / 0 unlocked", RaAchievements.summary(emptyList()))
        assertEquals("1 / 2 unlocked", RaAchievements.summary(RaAchievements.parse(sampleJson)))
    }

    @Test
    fun inactiveIsNotUnlocked() {
        val list = RaAchievements.parse(
            """[{"id":1,"title":"Never","description":"","points":1,"state":0,"unlocked":0}]""",
        )
        assertEquals(1, list.size)
        assertEquals(RaAchievements.STATE_INACTIVE, list[0].state)
        assertFalse(list[0].isUnlocked)
        assertEquals("0 / 1 unlocked", RaAchievements.summary(list))
    }
}
