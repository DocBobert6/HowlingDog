package app.howlingdog

/**
 * The .NEO header (TerraOnion format) identity read, plus the
 * cover-art filename matching against the libretro thumbnails
 * index. Pure Kotlin, no Android — unit-testable on the plain JVM.
 *
 * Header layout, verified against Robert's own converted carts
 * (neobombe, mslug5, shocktro, kof98, galaxyfg):
 *   0x00  "NEO\x01" magic
 *   0x04  P size, 0x08 S size, 0x0C M size, 0x10 V size, 0x18 C size (u32 LE)
 *   0x1C  year (u32 LE)
 *   0x28  NGH catalogue number — stored as hex digits that read as
 *         the decimal catalogue number (0x268 = game 268, 0x93 = 093)
 *   0x2C  title, 32 bytes, NUL-terminated ASCII
 *   0x4D  maker, NUL-terminated ASCII (one pad byte at 0x4C)
 *
 * The art source is the libretro thumbnails collection, repo
 * libretro-thumbnails/SNK_-_Neo_Geo, folder Named_Boxarts. Its
 * filenames do not follow one pattern — some are a bare title
 * ("Neo Bomberman.png"), some carry an NGH/NGM suffix
 * ("Metal Slug 5 (NGH-2680).png", "Cyber-Lip (NGM-010).png"),
 * some carry a subtitle ("Galaxy Fight - Universal Warriors.png")
 * or a set/bootleg marker ("Shock Troopers (set 1).png",
 * "Metal Slug 5 Plus (bootleg).png"). So the lookup matches
 * against the folder's filename index (fetched once, cached)
 * instead of guessing one name: exact title, then an NGH/NGM
 * number with the title, then a title prefix. No match means no
 * art — the typographic tile is the fallback, never a blank tile
 * and never a broken image.
 */
data class NeoIdentity(
    /** Header title, e.g. "Metal Slug 5", "Shock Troopers (set 1)". */
    val title: String,
    /** Header maker, e.g. "SNK Playmore", "Hudson". */
    val maker: String,
    /** Header year, e.g. 2003. */
    val year: Int,
    /** 3-digit catalogue number ("268", "093", "078"), or null. */
    val catalogue: String?,
)

object NeoHeader {
    /** The .NEO header is 0x100 bytes; the identity lives in the first 0x80. */
    const val HEADER_SIZE: Int = 0x100

    /** Base URL of the box-art set (raw file per name, see [artUrl]). */
    const val THUMBNAILS_BASE: String =
        "https://raw.githubusercontent.com/libretro-thumbnails/SNK_-_Neo_Geo/master/Named_Boxarts/"

    /** GitHub API listing of the box-art folder (the filename index). */
    const val THUMBNAILS_INDEX_URL: String =
        "https://api.github.com/repos/libretro-thumbnails/SNK_-_Neo_Geo/contents/Named_Boxarts?per_page=1000"

    /** Read the identity from a .NEO file's header bytes, or null when the header is not a .NEO header. */
    fun parse(bytes: ByteArray): NeoIdentity? {
        if (bytes.size < 0x80) return null
        if (bytes[0] != 'N'.code.toByte() || bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'O'.code.toByte() || bytes[3] != 0x01.toByte()
        ) return null
        val year = u32(bytes, 0x1C)
        if (year < 1980 || year > 2100) return null
        val title = ascii(bytes, 0x2C, 32).trim()
        if (title.isEmpty()) return null
        val maker = ascii(bytes, 0x4D, 16).trim()
        val ngh = u32(bytes, 0x28)
        val catalogue = if (ngh in 1..0xFFF) "%03X".format(ngh) else null
        return NeoIdentity(title = title, maker = maker, year = year, catalogue = catalogue)
    }

    /** Lower-case, non-alphanumeric runs become one space: titles and filenames compare on words. */
    fun normalizeTitle(title: String): String {
        return title.lowercase()
            .map { c -> if (c.isLetterOrDigit()) c else ' ' }
            .joinToString("")
            .split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * Region words a disc's display name can carry as a trailing
     * tag ("Japan" in "Cyber Lip Japan EnJa", "World" in a
     * "… World" cue name). They are not part of the game's title,
     * so they are stripped before matching. Only TRAILING words
     * are stripped, so a region word inside a real title
     * ("World Heroes") is never touched. "Kong" is deliberately
     * NOT here: "Hong Kong" is a region, but a title ending in
     * "Kong" is a game.
     */
    private val REGION_WORDS = setOf(
        "japan", "usa", "us", "europe", "eu", "world", "asia",
        "korea", "brazil", "france", "germany", "spain", "italy",
        "uk", "australia", "canada", "netherlands", "china",
        "taiwan", "mexico", "argentina", "portugal",
    )

    /**
     * Two-letter codes a disc's display name can carry as a
     * trailing tag, alone or glued into a 2–4-letter word
     * ("EnJa", "JaEn", "EnUs", "JpEn"): ISO 639-1 language codes
     * (en, ja, fr, de, es, it, pt, nl, ko, zh, ru, …) plus the
     * region codes that appear in the same tags (jp, kr, cn, tw,
     * au, ca, mx, br, us, uk, eu). No Neo Geo title ends in one
     * of these as a word, and only trailing words are stripped.
     */
    private val TAG_CODES = setOf(
        "en", "ja", "fr", "de", "es", "it", "pt", "nl", "ko", "zh",
        "ru", "sv", "da", "fi", "no", "pl", "tr", "ar", "he", "cs",
        "hu", "el", "id", "ms", "th", "vi",
        "jp", "kr", "cn", "tw", "au", "ca", "mx", "br", "us", "uk", "eu",
    )

    /** A word that is a region tag, or a language/region-code word (2–4 letters, made of 2-letter codes). */
    private fun isRegionOrLangTag(word: String): Boolean {
        if (word in REGION_WORDS) return true
        return word.length in 2..4 && word.length % 2 == 0 &&
            word.chunked(2).all { it in TAG_CODES }
    }

    /**
     * Strip trailing region/language tags from a normalised
     * title ("cyber lip japan enja" -> "cyber lip"). A title
     * that IS a region word ("japan") is never stripped to
     * nothing: at least one word always remains.
     */
    fun stripRegionTags(normalized: String): String {
        val words = normalized.split(' ').filter { it.isNotEmpty() }.toMutableList()
        while (words.size > 1 && isRegionOrLangTag(words.last())) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString(" ")
    }

    /** A title ready for art matching: normalised, with trailing region/language tags stripped. */
    fun normalizeForMatch(title: String): String {
        return stripRegionTags(normalizeTitle(title))
    }

    /**
     * The box-art filename for a game, from the folder index, or
     * null when nothing in the index matches. Order: exact title,
     * then an NGH/NGM number carried with the title (the number
     * is the specific identifier; the title prefix keeps a bootleg
     * from matching its parent game's art), then a title prefix.
     * Among several prefix matches the shortest wins, and a
     * "bootleg" file only wins when the title itself says bootleg
     * (so "Metal Slug 5" never lands on "Metal Slug 5 Plus").
     *
     * Both sides are normalised for the match ([normalizeForMatch]):
     * a CD game's identity comes from its cue/disc display name,
     * which can carry the cue filename's region/language suffix
     * ("Cyber Lip Japan EnJa", "Galaxy Fight Universal Warriors
     * (Japan)"), while the art filename carries the plain title
     * ("Cyber-Lip (NGM-010)", "Galaxy Fight - Universal Warriors").
     * Stripping the suffixes makes those two match. The NGH/NGM
     * step and the bootleg guard are unaffected: a number is not
     * a region/language tag, and neither is "bootleg".
     */
    fun matchArtFile(title: String, catalogue: String?, index: List<String>): String? {
        val want = normalizeForMatch(title)
        if (want.isEmpty()) return null
        val byNorm = index.mapNotNull { name ->
            val norm = normalizeForMatch(name.removeSuffix(".png"))
            if (norm.isEmpty()) null else name to norm
        }
        // 1. Exact title.
        byNorm.firstOrNull { (_, norm) -> norm == want }?.let { return it.first }
        // 2. NGH/NGM number, with the title in front of it.
        if (catalogue != null) {
            val numbered = byNorm.filter { (_, norm) ->
                norm.startsWith(want) &&
                    (norm.contains("ngh $catalogue") || norm.contains("ngm $catalogue"))
            }
            // NGH first (the home catalogue), then the shortest name
            // (so "… (NGH-084).png" beats "… (NGH-084, alternate board).png").
            numbered.sortedWith(
                compareBy({ if ("ngh $catalogue" in it.second) 0 else 1 }, { it.first.length }),
            ).firstOrNull()?.let { return it.first }
        }
        // 3. Title prefix ("Galaxy Fight" -> "Galaxy Fight - Universal Warriors";
        // "Metal Slug 5 Plus" -> "Metal Slug 5 Plus (bootleg)").
        // A "bootleg" file only wins when the title itself says
        // bootleg — otherwise it is skipped, not fallen back to,
        // so "Metal Slug 5" never lands on "Metal Slug 5 Plus".
        val prefixed = byNorm.filter { (_, norm) -> norm.startsWith("$want ") }
        if (prefixed.isNotEmpty()) {
            if ("bootleg" in want) return prefixed.minByOrNull { it.first.length }!!.first
            prefixed.filter { (_, norm) -> "bootleg" !in norm }
                .minByOrNull { it.first.length }?.let { return it.first }
        }
        // 4. Bare title prefix, same tiebreaks.
        val bare = byNorm.filter { (_, norm) -> norm.startsWith(want) }
        if (bare.isNotEmpty()) {
            if ("bootleg" in want) return bare.minByOrNull { it.first.length }!!.first
            bare.filter { (_, norm) -> "bootleg" !in norm }
                .minByOrNull { it.first.length }?.let { return it.first }
        }
        return null
    }

    /** The filenames in a GitHub API contents listing ({"name": "..."} objects). */
    fun parseIndexJson(json: String): List<String> {
        val pattern = Regex(""""name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        return pattern.findAll(json).mapNotNull { m ->
            m.groupValues[1]
                .replace("\\\"", "\"").replace("\\\\", "\\")
                .takeIf { it.endsWith(".png", ignoreCase = true) }
        }.toList()
    }

    /** The download URL for one box-art filename. */
    fun artUrl(fileName: String): String {
        val encoded = java.net.URLEncoder.encode(fileName, "UTF-8").replace("+", "%20")
        return THUMBNAILS_BASE + encoded
    }

    private fun u32(bytes: ByteArray, off: Int): Int {
        return (bytes[off].toInt() and 0xFF) or
            ((bytes[off + 1].toInt() and 0xFF) shl 8) or
            ((bytes[off + 2].toInt() and 0xFF) shl 16) or
            ((bytes[off + 3].toInt() and 0xFF) shl 24)
    }

    private fun ascii(bytes: ByteArray, off: Int, len: Int): String {
        val end = (off until minOf(off + len, bytes.size)).firstOrNull { bytes[it] == 0.toByte() }
            ?: minOf(off + len, bytes.size)
        return bytes.copyOfRange(off, end).decodeToString()
    }
}
