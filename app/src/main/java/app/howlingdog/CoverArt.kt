package app.howlingdog

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Cover art, fetched at runtime from the libretro thumbnails
 * collection (SNK - Neo Geo, Named_Boxarts) — never bundled in
 * the repo or the APK. This lifts the Phase 0 "no art is ever
 * fetched" rule for the game tiles ONLY, on Robert's explicit
 * call; the app's own branding (mascot, icon) stays original art.
 *
 * Rules (his weekly-data rule):
 * - Wi-Fi only, never on mobile data. Offline means the
 *   typographic tile, with no error and no blocking.
 * - One small image per game, fetched on demand when its tile
 *   is shown, then cached on the device (filesDir/art/). Never
 *   a silent bulk download of the library.
 * - The filename index is fetched once and cached
 *   (filesDir/art-index.txt); the art itself is only ever
 *   fetched one file at a time, per game.
 * - Any failure at any step (no Wi-Fi, no index, no match, bad
 *   download, undecodable bytes) returns null and the caller
 *   keeps the typographic tile.
 */
class CoverArt(private val filesDir: File) {

    /** The box-art filename for a game, from the index — no network. Null when the game has no art. */
    fun resolveArtFile(entry: GameEntry, gamesDir: File, index: List<String>): String? {
        val (title, catalogue) = identityFor(entry, gamesDir)
        return NeoHeader.matchArtFile(title, catalogue, index)
    }

    /**
     * The identity a game is looked up by: the .NEO header for
     * carts, the display name for discs. This deliberately reads
     * [GameEntry.displayName] — the filename-derived ORIGINAL —
     * never the user's custom name (Phase 9, item 4): a renamed
     * game keeps its art, because the lookup never sees the rename.
     */
    fun identityFor(entry: GameEntry, gamesDir: File): Pair<String, String?> {
        if (entry.mainFile.endsWith(".neo", ignoreCase = true)) {
            val file = File(gamesDir, entry.mainFile)
            if (file.isFile) {
                try {
                    val head = ByteArray(NeoHeader.HEADER_SIZE)
                    file.inputStream().use { it.read(head) }
                    NeoHeader.parse(head)?.let { return it.title to it.catalogue }
                } catch (_: Exception) {
                    // Fall through to the display name.
                }
            }
        }
        return entry.displayName to null
    }

    /** The cached index, or fetch it (Wi-Fi only) and cache it. Null on any failure. */
    fun loadIndex(isWifi: Boolean): List<String>? {
        val cached = readIndexFile()
        if (cached != null) return cached
        if (!isWifi) return null
        return try {
            val json = httpGet(NeoHeader.THUMBNAILS_INDEX_URL)
                ?: return null
            val names = NeoHeader.parseIndexJson(json)
            if (names.isEmpty()) return null
            try {
                indexFile().writeText(names.joinToString("\n") + "\n")
            } catch (_: Exception) {
                // The in-memory list is still usable this run.
            }
            names
        } catch (_: Exception) {
            null
        }
    }

    /** The cached art bitmap for a game, or null when it was never fetched. */
    fun cachedBitmap(entry: GameEntry): Bitmap? {
        val file = cacheFile(entry)
        if (!file.isFile) return null
        return try {
            BitmapFactory.decodeFile(file.absolutePath)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Fetch one game's art (Wi-Fi only) and cache the bytes.
     * Returns the decoded bitmap, or null on any failure. The
     * bytes are capped at 2 MB — box art is a few hundred KB;
     * anything larger is not the file we asked for.
     */
    fun fetchBitmap(fileName: String, entry: GameEntry): Bitmap? {
        return try {
            val bytes = httpGetBytes(NeoHeader.artUrl(fileName), MAX_ART_BYTES) ?: return null
            val bitmap = decodeArt(bytes) ?: return null
            try {
                cacheFile(entry).apply { parentFile?.mkdirs() }.writeBytes(bytes)
            } catch (_: Exception) {
                // The bitmap is still usable this run.
            }
            bitmap
        } catch (_: Exception) {
            null
        }
    }

    private fun indexFile(): File = File(filesDir, "art-index.txt")

    private fun readIndexFile(): List<String>? {
        val file = indexFile()
        if (!file.isFile) return null
        return try {
            file.readLines().map { it.trim() }.filter { it.endsWith(".png", ignoreCase = true) }
                .takeIf { it.isNotEmpty() }
        } catch (_: Exception) {
            null
        }
    }

    private fun cacheFile(entry: GameEntry): File {
        val stem = sanitize(entry.mainFile.substringBeforeLast('.', entry.mainFile))
        return File(File(filesDir, "art"), "$stem.png")
    }

    private fun httpGet(url: String): String? {
        val bytes = httpGetBytes(url, MAX_INDEX_BYTES) ?: return null
        return bytes.decodeToString()
    }

    private fun httpGetBytes(url: String, maxBytes: Int): ByteArray? {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "HowlingDog")
            setRequestProperty("Accept", "application/vnd.github+json")
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

    /** Decode box art downsampled to at most [MAX_ART_DIM] on a side, so a tile never holds a full-size image. */
    fun decodeArt(bytes: ByteArray): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (bounds.outWidth / sample > MAX_ART_DIM || bounds.outHeight / sample > MAX_ART_DIM) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Box art is a few hundred KB; the index JSON is tens of KB. */
        const val MAX_ART_BYTES: Int = 2 * 1024 * 1024
        const val MAX_INDEX_BYTES: Int = 512 * 1024
        const val MAX_ART_DIM: Int = 512

        /** True when the active network is Wi-Fi. False with no network, or on mobile data. */
        fun isWifi(context: Context): Boolean {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return false
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        }
    }
}
