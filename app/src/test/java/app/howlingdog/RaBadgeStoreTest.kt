package app.howlingdog

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the achievement badge disk cache
 * (RaBadgeStore).
 *
 * The cache lookup is pure file IO, so it is testable here
 * without BitmapFactory (which is a stub in android.jar):
 * [RaBadgeStore.cachedBadgeBytes] reads the cached PNG bytes,
 * and the row path serves those bytes before any network. The
 * tests prove the round-trip: a file written to the cache path
 * a fetch uses is returned by the lookup a later launch uses,
 * with no network involved (isWifi=false throughout, so any
 * network call would fail the test outright), and a missing
 * file returns null (so a fetch is still triggered for it).
 * The Bitmap decode itself (cachedBitmap / decodeBadge) is
 * Android-only and is covered by CI, not here. The file name
 * is pure string logic and is tested directly.
 */
class RaBadgeStoreTest {

    private fun achievement(id: Int, badgeUrl: String?, badgeLockedUrl: String?, unlocked: Boolean = false): RaAchievement =
        RaAchievement(
            id = id,
            title = "Ach$id",
            description = "",
            points = 5,
            state = if (unlocked) RaAchievements.STATE_UNLOCKED else RaAchievements.STATE_ACTIVE,
            unlocked = unlocked,
            badgeUrl = badgeUrl,
            badgeLockedUrl = badgeLockedUrl,
        )

    private fun freshDir(name: String): File =
        File("/tmp/hd-badge-$name").apply {
            deleteRecursively()
            mkdirs()
        }

    @Test
    fun badgeFileNameFromUnlockedUrl() {
        val store = RaBadgeStore(File("/tmp/hd-badge-unused"))
        assertEquals(
            "00235.png",
            store.badgeFileName("https://media.retroachievements.org/Badge/00235.png", 5502),
        )
    }

    @Test
    fun badgeFileNameFromLockedUrlKeepsLockSuffix() {
        val store = RaBadgeStore(File("/tmp/hd-badge-unused"))
        assertEquals(
            "00235_lock.png",
            store.badgeFileName("https://media.retroachievements.org/Badge/00235_lock.png", 5502),
        )
    }

    @Test
    fun badgeFileNameFallsBackToIdWhenNoUrl() {
        val store = RaBadgeStore(File("/tmp/hd-badge-unused"))
        assertEquals("badge-5501.png", store.badgeFileName(null, 5501))
        assertEquals("badge-5501.png", store.badgeFileName("", 5501))
        assertEquals("badge-5501.png", store.badgeFileName("https://media.retroachievements.org/Badge/", 5501))
    }

    @Test
    fun badgeFileNameStripsQueryAndSanitises() {
        val store = RaBadgeStore(File("/tmp/hd-badge-unused"))
        assertEquals(
            "00235.png",
            store.badgeFileName("https://media.retroachievements.org/Badge/00235.png?x=1", 5502),
        )
    }

    @Test
    fun cachedBadgeBytesReturnsTheSavedFileWithoutNetwork() {
        // A badge fetched once: the fetch wrote its bytes to the
        // cache file; a later launch reads them back with no
        // network call (isWifi=false throughout).
        val dir = freshDir("cached")
        val store = RaBadgeStore(dir)
        val ach = achievement(5502, "https://media.retroachievements.org/Badge/00235.png", "https://media.retroachievements.org/Badge/00235_lock.png")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val file = store.cacheFile(ach)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        assertArrayEquals(bytes, store.cachedBadgeBytes(ach))
        dir.deleteRecursively()
    }

    @Test
    fun missingBadgeFileReturnsNullSoAFetchIsTriggered() {
        val dir = freshDir("missing")
        val store = RaBadgeStore(dir)
        val ach = achievement(5502, "https://media.retroachievements.org/Badge/00235.png", "https://media.retroachievements.org/Badge/00235_lock.png")
        assertNull(store.cachedBadgeBytes(ach))
        dir.deleteRecursively()
    }

    @Test
    fun cacheFileIsStableForTheSameAchievement() {
        // The save and the lookup use the same key: the same
        // achievement always maps to the same file, so a fetch's
        // write is found by a later launch's read.
        val dir = freshDir("stable")
        val store = RaBadgeStore(dir)
        val ach = achievement(5502, "https://media.retroachievements.org/Badge/00235.png", "https://media.retroachievements.org/Badge/00235_lock.png")
        assertEquals(store.cacheFile(ach), store.cacheFile(ach))
        assertTrue(store.cacheFile(ach).name.endsWith(".png"))
        // A different achievement maps to a different file.
        val other = achievement(5501, "https://media.retroachievements.org/Badge/00234.png", "https://media.retroachievements.org/Badge/00234_lock.png")
        assertTrue(store.cacheFile(ach) != store.cacheFile(other))
        dir.deleteRecursively()
    }

    @Test
    fun lockedAndUnlockedBadgesCacheSeparately() {
        // The locked badge (_lock.png) and the unlocked badge
        // (.png) are different files, so unlocking an
        // achievement fetches its colour badge instead of
        // reusing the greyed one.
        val dir = freshDir("locksep")
        val store = RaBadgeStore(dir)
        val locked = achievement(5502, "https://media.retroachievements.org/Badge/00235.png", "https://media.retroachievements.org/Badge/00235_lock.png", unlocked = false)
        val unlocked = achievement(5502, "https://media.retroachievements.org/Badge/00235.png", "https://media.retroachievements.org/Badge/00235_lock.png", unlocked = true)
        assertTrue(store.cacheFile(locked) != store.cacheFile(unlocked))
        assertEquals("00235_lock.png", store.cacheFile(locked).name)
        assertEquals("00235.png", store.cacheFile(unlocked).name)
        dir.deleteRecursively()
    }

    @Test
    fun fetchBitmapWithNoWifiIsNull() {
        val dir = freshDir("nowifi")
        val store = RaBadgeStore(dir)
        val ach = achievement(5502, "https://media.retroachievements.org/Badge/00235.png", "https://media.retroachievements.org/Badge/00235_lock.png")
        assertNull(store.fetchBitmap(ach, isWifi = false))
        dir.deleteRecursively()
    }

    @Test
    fun fetchBitmapWithNoUrlIsNull() {
        val dir = freshDir("nourl")
        val store = RaBadgeStore(dir)
        val ach = achievement(5501, null, null)
        assertNull(store.fetchBitmap(ach, isWifi = true))
        dir.deleteRecursively()
    }
}
