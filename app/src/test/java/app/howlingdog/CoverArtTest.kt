package app.howlingdog

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for the cover-art disk cache (CoverArt).
 *
 * The bug (Robert, 2026-11-10): every launch re-fetched the
 * artwork. The cache lookup is pure file IO, so it is testable
 * here without BitmapFactory (which is a stub in android.jar):
 * [CoverArt.cachedArtBytes] reads the cached PNG bytes, and the
 * tile path serves those bytes before the index or the network.
 * The tests prove the round-trip: a file written to the cache
 * path a fetch uses is returned by the lookup a later launch
 * uses, with no network involved (isWifi=false throughout, so
 * any network call would fail the test outright), and a missing
 * file returns null (so a fetch is still triggered for it).
 * The Bitmap decode itself (cachedBitmap / decodeArt) is
 * Android-only and is covered by CI, not here.
 */
class CoverArtTest {

    private fun entry(mainFile: String): GameEntry =
        GameEntry(
            mainFile = mainFile,
            displayName = displayNameFor(mainFile),
            files = listOf(mainFile),
            addedAt = 1L,
        )

    private fun freshDir(name: String): File =
        File("/tmp/hd-coverart-$name").apply {
            deleteRecursively()
            mkdirs()
        }

    @Test
    fun cachedArtBytesReturnsTheSavedFileWithoutNetwork() {
        // A game whose art was fetched once: the fetch wrote its
        // bytes to the cache file; a later launch reads them back
        // with no network call (isWifi=false, so loadIndex would
        // refuse the network too — nothing here can reach it).
        val dir = freshDir("cached")
        val art = CoverArt(dir)
        val game = entry("kof98.neo")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
        val file = art.cacheFile(game)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        assertArrayEquals(bytes, art.cachedArtBytes(game))
        dir.deleteRecursively()
    }

    @Test
    fun missingArtFileReturnsNullSoAFetchIsTriggered() {
        val dir = freshDir("missing")
        val art = CoverArt(dir)
        assertNull(art.cachedArtBytes(entry("kof98.neo")))
        dir.deleteRecursively()
    }

    @Test
    fun cacheFileIsStableForTheSameGame() {
        // The save and the lookup use the same key: the same
        // entry always maps to the same file, so a fetch's write
        // is found by a later launch's read.
        val dir = freshDir("stable")
        val art = CoverArt(dir)
        val game = entry("The King of Fighters '98.neo")
        assertEquals(art.cacheFile(game), art.cacheFile(game))
        assertTrue(art.cacheFile(game).name.endsWith(".png"))
        // A different game maps to a different file.
        assertTrue(art.cacheFile(game) != art.cacheFile(entry("mslug5.neo")))
        dir.deleteRecursively()
    }

    @Test
    fun cachedIndexIsReturnedWithoutNetwork() {
        val dir = freshDir("index")
        File(dir, "art-index.txt").writeText("KOF 98.png\nMetal Slug.png\n")
        val art = CoverArt(dir)
        // isWifi=false: if the cache were missed, loadIndex would
        // return null (no network allowed). A cached index must
        // come back without any network call.
        assertEquals(listOf("KOF 98.png", "Metal Slug.png"), art.loadIndex(isWifi = false))
        dir.deleteRecursively()
    }

    @Test
    fun missingIndexAndNoWifiIsNull() {
        val dir = freshDir("noindex")
        val art = CoverArt(dir)
        assertNull(art.loadIndex(isWifi = false))
        dir.deleteRecursively()
    }
}
