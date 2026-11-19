package app.howlingdog

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Achievement Center Step 2 — RetroAchievements badge images
 * (64 px PNGs, one per achievement).
 *
 * Reuses the cover-art image path (CoverArt): the badge is
 * fetched on demand when its row is shown, then cached on the
 * device (filesDir/badges/). The rules are the same as cover
 * art (his weekly-data rule):
 * - Wi-Fi only, never on mobile data. Offline means no badge,
 *   with no error and no blocking.
 * - One small image per achievement, fetched one at a time,
 *   never a silent bulk download of the set.
 * - Any failure at any step (no Wi-Fi, no URL, bad download,
 *   undecodable bytes) returns null and the caller keeps the
 *   text-only row.
 *
 * The URL comes from the achievement itself
 * ([RaAchievement.badgeUrlForState]): the unlocked badge once
 * earned, the locked (greyed) badge until then.
 */
class RaBadgeStore(private val filesDir: File) {

    /**
     * The file name for a badge URL: the URL's last path
     * segment, sanitised. Falls back to "badge-<id>.png" when
     * the URL has no usable segment. Pure, so it is unit-tested.
     */
    fun badgeFileName(url: String?, achievementId: Int): String {
        val segment = url
            ?.substringAfterLast('/', "")
            ?.substringBefore('?')
            ?.trim()
            ?: ""
        val clean = segment.filter { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        if (clean.endsWith(".png", ignoreCase = true) && clean.length > 4) return clean
        return "badge-$achievementId.png"
    }

    /** The cache file for an achievement's badge (internal, so the cache round-trip is testable). */
    internal fun cacheFile(achievement: RaAchievement): File {
        val name = badgeFileName(achievement.badgeUrlForState, achievement.id)
        return File(File(filesDir, "badges"), name)
    }

    /**
     * The cached badge bytes for an achievement, or null when it
     * was never fetched. Pure file IO (no Bitmap), so the cache
     * round-trip is testable on the plain JVM. This is the
     * authoritative check for "does this achievement already
     * have its badge": the row path reads this FIRST, before
     * any network, so a badge fetched once is served from disk
     * on every later launch with no network call.
     */
    fun cachedBadgeBytes(achievement: RaAchievement): ByteArray? {
        val file = cacheFile(achievement)
        if (!file.isFile) return null
        return try {
            file.readBytes()
        } catch (_: Exception) {
            null
        }
    }

    /** The cached badge bitmap for an achievement, or null when it was never fetched. */
    fun cachedBitmap(achievement: RaAchievement): Bitmap? {
        val bytes = cachedBadgeBytes(achievement) ?: return null
        return decodeBadge(bytes)
    }

    /**
     * Fetch one achievement's badge (Wi-Fi only) and cache the
     * bytes. Returns the decoded bitmap, or null on any failure.
     * The bytes are capped at 64 KB — badges are 64 px, a few
     * KB; anything larger is not the file we asked for.
     */
    fun fetchBitmap(achievement: RaAchievement, isWifi: Boolean): Bitmap? {
        if (!isWifi) return null
        val url = achievement.badgeUrlForState ?: return null
        return try {
            val bytes = httpGetBytes(url, MAX_BADGE_BYTES) ?: return null
            val bitmap = decodeBadge(bytes) ?: return null
            try {
                cacheFile(achievement).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            } catch (_: Exception) {
                // The bitmap is still usable this run.
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    /** Decode badge bytes. Badges are 64 px, so no downsampling is needed; an undecodable file is null. */
    private fun decodeBadge(bytes: ByteArray): Bitmap? {
        return try {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        }
    }

    private fun httpGetBytes(url: String, maxBytes: Int): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "HowlingDog")
        }
        try {
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            val out = java.io.ByteArrayOutputStream()
            conn.inputStream.use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > maxBytes) return null // over the cap
                }
            }
            return out.toByteArray()
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Badges are a few KB; 64 KB is a generous cap. */
        const val MAX_BADGE_BYTES: Int = 64 * 1024
    }
}
